package dev.bagja.spatialaudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.PermissionState
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import dev.bagja.spatialaudio.audio.FloatingBubbleService
import dev.bagja.spatialaudio.audio.SpatialHostBridge
import dev.bagja.spatialaudio.audio.SystemGlobalEqManager
import dev.bagja.spatialaudio.audio.Universal8DService
import dev.bagja.spatialaudio.data.BUILTIN_PRESETS
import dev.bagja.spatialaudio.data.EQ_PRESETS
import dev.bagja.spatialaudio.data.MixerRepository
import dev.bagja.spatialaudio.data.MixerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(
    name = "SpatialAudio",
    permissions = [Permission(alias = "audio", strings = [Manifest.permission.RECORD_AUDIO])]
)
class SpatialAudioPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun load() {
        super.load()
        MixerRepository.init(context)
        scope.launch {
            var lastFrameEvent = 0L
            MixerRepository.frame.collectLatest { frame ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameEvent < 33L) return@collectLatest
                lastFrameEvent = now
                val payload = JSObject()
                payload.put("x", frame.x)
                payload.put("z", frame.z)
                payload.put("progress", frame.progress)
                payload.put("bassEnergy", frame.bassEnergy)
                payload.put("spectrum", JSArray().apply { frame.spectrum.forEach { put(it) } })
                notifyListeners("frame", payload)
            }
        }
        scope.launch {
            MixerRepository.settings.collectLatest { emitStatus() }
        }
        scope.launch { MixerRepository.universalCaptureActive.collectLatest { emitStatus() } }
        scope.launch { MixerRepository.globalEqActive.collectLatest { emitStatus() } }
        scope.launch { MixerRepository.floatingBubbleEnabled.collectLatest { emitStatus() } }
        scope.launch { MixerRepository.floatingLyricsEnabled.collectLatest { emitStatus() } }
        scope.launch { SystemGlobalEqManager.statusMessage.collectLatest { emitStatus() } }
    }

    @PluginMethod
    fun getState(call: PluginCall) {
        MixerRepository.init(context)
        call.resolve(stateObject())
    }

    @PluginMethod
    fun setSettings(call: PluginCall) {
        val raw = call.data.optJSONObject("settings") ?: call.data
        runCatching {
            val parsed = MixerSettings.fromJson(raw)
            MixerRepository.set(parsed)
            if (MixerRepository.globalEqActive.value) SystemGlobalEqManager.apply(parsed)
            call.resolve(stateObject())
        }.onFailure { call.reject("Pengaturan tidak valid: ${it.message}") }
    }

    @PluginMethod
    fun applyPreset(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("ID preset tidak diberikan")
        if (!MixerRepository.applyPresetById(id)) return call.reject("Preset tidak ditemukan")
        if (MixerRepository.globalEqActive.value) SystemGlobalEqManager.apply(MixerRepository.settings.value)
        call.resolve(stateObject())
    }

    @PluginMethod
    fun savePreset(call: PluginCall) {
        val name = call.getString("name")?.trim().orEmpty()
        if (name.isBlank()) return call.reject("Nama preset wajib diisi")
        val raw = call.data.optJSONObject("settings")
        val current = if (raw != null) MixerSettings.fromJson(raw) else MixerRepository.settings.value
        val saved = MixerRepository.savePreset(name, current)
        call.resolve(JSObject().put("id", saved.id).put("name", saved.name))
    }

    @PluginMethod
    fun deletePreset(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("ID preset tidak diberikan")
        MixerRepository.deletePreset(id)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun exportPreset(call: PluginCall) {
        val name = call.getString("name") ?: "Preset Kruzen"
        val raw = call.data.optJSONObject("settings")
        val settings = if (raw != null) MixerSettings.fromJson(raw) else MixerRepository.settings.value
        val json = JSONObject().put("app", "Kruzen Spatial Audio")
            .put("author", "DEV BAGJA / KruzenLab")
            .put("name", name).put("settings", settings.toJson()).toString(2)
        call.resolve(JSObject().put("json", json))
    }

    @PluginMethod
    fun importPreset(call: PluginCall) {
        val raw = call.getString("json") ?: return call.reject("Data JSON kosong")
        MixerRepository.importPresetJson(raw).fold(
            onSuccess = { call.resolve(JSObject().put("id", it.id).put("name", it.name)) },
            onFailure = { call.reject("Gagal mengimpor preset: ${it.message}") }
        )
    }

    @PluginMethod
    fun setFloatingBubble(call: PluginCall) {
        MixerRepository.init(context)
        val enabled = call.getBoolean("enabled", false) ?: false
        if (enabled && !Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve(JSObject().put("permissionRequired", true))
            return
        }
        MixerRepository.floatingBubbleEnabled.value = enabled
        if (enabled) FloatingBubbleService.start(context) else FloatingBubbleService.stop(context)
        call.resolve(JSObject().put("enabled", enabled))
        emitStatus()
    }

    @PluginMethod
    fun setFloatingLyrics(call: PluginCall) {
        MixerRepository.init(context)
        val enabled = call.getBoolean("enabled", false) ?: false
        if (enabled && !Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve(JSObject().put("permissionRequired", true))
            return
        }
        MixerRepository.floatingLyricsEnabled.value = enabled
        if (enabled || MixerRepository.floatingBubbleEnabled.value) FloatingBubbleService.start(context)
        else FloatingBubbleService.stop(context)
        FloatingBubbleService.syncLyrics(context)
        call.resolve(JSObject().put("enabled", enabled))
        emitStatus()
    }


    @PluginMethod
    fun chooseImpulseResponse(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(call, intent, "impulseResponseResult")
    }

    @ActivityCallback
    private fun impulseResponseResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Pemilihan file impulse response dibatalkan.")
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Impulse Response.wav"
        val updated = MixerRepository.settings.value.copy(customIrUri = uri.toString(), customIrName = name)
        MixerRepository.set(updated)
        call.resolve(JSObject().put("uri", uri.toString()).put("name", name).put("settings", updated.toJson()))
        emitStatus()
    }

    @PluginMethod
    fun setNowPlaying(call: PluginCall) {
        SpatialHostBridge.currentTitle.value = call.getString("title")
        SpatialHostBridge.currentLyrics.value = call.getString("lyrics")
        FloatingBubbleService.syncLyrics(context)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun requestPlaybackToggle(call: PluginCall) {
        val callback = SpatialHostBridge.onTogglePlayback
        if (callback == null) call.reject("Pemutar host belum menghubungkan callback playback.")
        else { callback.invoke(); call.resolve(JSObject().put("ok", true)) }
    }

    @PluginMethod
    fun requestPlaybackPause(call: PluginCall) {
        val callback = SpatialHostBridge.onPausePlayback
        if (callback == null) call.reject("Pemutar host belum menghubungkan callback pause.")
        else { callback.invoke(); call.resolve(JSObject().put("ok", true)) }
    }

    @PluginMethod
    fun setGlobalEq(call: PluginCall) {
        val enabled = call.getBoolean("enabled", false) ?: false
        SystemGlobalEqManager.setEnabled(enabled, MixerRepository.settings.value)
        call.resolve(JSObject().put("active", MixerRepository.globalEqActive.value)
            .put("message", SystemGlobalEqManager.statusMessage.value ?: ""))
        emitStatus()
    }

    @PluginMethod
    fun requestUniversalCapture(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.unavailable("Universal Capture membutuhkan Android 10 atau lebih baru.")
            return
        }
        if (getPermissionState("audio") != PermissionState.GRANTED) {
            requestPermissionForAlias("audio", call, "audioPermissionResult")
            return
        }
        launchProjectionConsent(call)
    }

    @PermissionCallback
    private fun audioPermissionResult(call: PluginCall) {
        if (getPermissionState("audio") == PermissionState.GRANTED) launchProjectionConsent(call)
        else call.reject("Izin audio diperlukan untuk Universal 8D Capture.")
    }

    private fun launchProjectionConsent(call: PluginCall) {
        val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(call, manager.createScreenCaptureIntent(), "projectionConsentResult")
    }

    @ActivityCallback
    private fun projectionConsentResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            call.reject("Persetujuan capture dibatalkan.")
            return
        }
        Universal8DService.start(context, result.resultCode, data)
        call.resolve(JSObject().put("active", true))
        emitStatus()
    }

    @PluginMethod
    fun stopUniversalCapture(call: PluginCall) {
        Universal8DService.stop(context)
        call.resolve(JSObject().put("active", false))
        emitStatus()
    }

    private fun stateObject(): JSObject {
        val settings = MixerRepository.settings.value
        val saved = JSONArray().apply {
            MixerRepository.loadPresets().forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("settings", p.settings.toJson()))
            }
        }
        val builtins = JSONArray().apply {
            BUILTIN_PRESETS.forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("description", p.description))
            }
        }
        val frame = MixerRepository.frame.value
        val spectrum = JSArray().apply { frame.spectrum.forEach { put(it) } }
        return JSObject().put("settings", settings.toJson())
            .put("savedPresets", saved).put("builtinPresets", builtins)
            .put("eqPresetNames", JSONArray().apply { EQ_PRESETS.forEach { put(it.label) } })
            .put("universalCaptureActive", MixerRepository.universalCaptureActive.value)
            .put("globalEqActive", MixerRepository.globalEqActive.value)
            .put("globalEqMessage", SystemGlobalEqManager.statusMessage.value ?: "")
            .put("floatingBubbleEnabled", MixerRepository.floatingBubbleEnabled.value)
            .put("floatingLyricsEnabled", MixerRepository.floatingLyricsEnabled.value)
            .put("frame", JSObject().put("x", frame.x).put("z", frame.z).put("progress", frame.progress)
                .put("bassEnergy", frame.bassEnergy).put("spectrum", spectrum))
    }

    private fun emitStatus() {
        notifyListeners("status", stateObject())
    }
}
