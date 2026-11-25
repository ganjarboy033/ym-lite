package dev.bagja.spatialaudio.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Posisi bola suara saat ini + energi spektrum real-time (untuk kanvas 8D & visualizer). */
data class PanFrame(
    val x: Float = 0f,
    val z: Float = 0f,
    val progress: Float = 0f,
    val bassEnergy: Float = 0f,
    val spectrum: List<Float> = List(24) { 0f },
)

enum class UniversalAudioMode(val label: String) {
    OFF("Nonaktif"),
    CAPTURE_8D("Mode A: Full Universal 8D Capture"),
    GLOBAL_EQ("Mode B: Zero-Latency Global EQ"),
}

class SavedPreset(val id: String, val name: String, val settings: MixerSettings)

/**
 * Pusat data Mixer yang dipakai bersama UI, PlaybackService, Universal8DService, dan GlobalEqManager.
 */
object MixerRepository {
    val settings = MutableStateFlow(MixerSettings())
    val frame = MutableStateFlow(PanFrame())

    val universalMode = MutableStateFlow(UniversalAudioMode.OFF)
    val universalCaptureActive = MutableStateFlow(false)
    val globalEqActive = MutableStateFlow(false)
    val floatingBubbleEnabled = MutableStateFlow(false)
    val floatingLyricsEnabled = MutableStateFlow(false)
    val activeAudioOutput = MutableStateFlow("Speaker HP")

    /** Bila tidak null, bola diarahkan manual oleh jari (x,z). Dibaca thread audio. */
    @Volatile var override: FloatArray? = null

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = prefs().getString(KEY_LAST, null)
        if (raw != null) runCatching { settings.value = MixerSettings.fromJson(JSONObject(raw)) }
    }

    fun update(change: (MixerSettings) -> MixerSettings) {
        settings.update(change)
        scheduleSave()
    }

    fun set(s: MixerSettings) {
        settings.value = s
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(450)
            prefs().edit().putString(KEY_LAST, settings.value.toJson().toString()).apply()
        }
    }

    // ---- Preset buatan sendiri & JSON Import/Export ----
    fun loadPresets(): List<SavedPreset> = runCatching {
        val arr = JSONArray(prefs().getString(KEY_PRESETS, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SavedPreset(o.getString("id"), o.getString("name"), MixerSettings.fromJson(o.getJSONObject("settings")))
        }
    }.getOrDefault(emptyList())

    fun savePreset(name: String, customSettings: MixerSettings = settings.value): SavedPreset {
        val created = SavedPreset(UUID.randomUUID().toString(), name, customSettings)
        writePresets(listOf(created) + loadPresets())
        return created
    }

    fun deletePreset(id: String) = writePresets(loadPresets().filter { it.id != id })

    fun applyPresetById(id: String): Boolean {
        BUILTIN_PRESETS.firstOrNull { it.id == id }?.let {
            set(it.build())
            return true
        }
        loadPresets().firstOrNull { it.id == id }?.let {
            set(it.settings)
            return true
        }
        return false
    }

    /** Ekspor preset aktif (atau semua preset) ke format JSON siap simpan/bagikan. */
    fun exportCurrentPresetJson(presetName: String = "Preset Laguku"): String {
        return JSONObject().apply {
            put("app", "Kruzen Spatial Audio")
            put("author", "DEV BAGJA / KruzenLab")
            put("name", presetName)
            put("settings", settings.value.toJson())
        }.toString(2)
    }

    /** Impor preset dari teks JSON; otomatis simpan ke daftar Preset Saya & terapkan. */
    fun importPresetJson(rawJson: String): Result<SavedPreset> = runCatching {
        val trimmed = rawJson.trim()
        val obj = JSONObject(trimmed)
        val settingsObj = if (obj.has("settings")) obj.getJSONObject("settings") else obj
        val name = obj.optString("name").ifBlank { "Impor ${System.currentTimeMillis() % 10000}" }
        val parsed = MixerSettings.fromJson(settingsObj)
        set(parsed)
        savePreset(name, parsed)
    }

    fun overwriteAllPresets(list: List<SavedPreset>) = writePresets(list)

    private fun writePresets(list: List<SavedPreset>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("settings", it.settings.toJson()))
        }
        prefs().edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    private fun prefs() = appContext!!.getSharedPreferences("mixer", Context.MODE_PRIVATE)
    private const val KEY_LAST = "last"
    private const val KEY_PRESETS = "presets"
}
