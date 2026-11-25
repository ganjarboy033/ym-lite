package dev.bagja.spatialaudio.data

import org.json.JSONArray
import org.json.JSONObject

/** Satu titik pada bidang atas (x kiri-kanan, z depan-belakang), rentang -1..1. */
data class Pt(val x: Float, val z: Float)

enum class Movement(val id: String, val label: String) {
    LOOP("loop", "Putar"),
    PINGPONG("pingpong", "Bolak-balik"),
    RANDOM("random", "Acak");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: LOOP
    }
}

/**
 * Pengaturan lengkap DSP Mixer 8D & Remixer Laguku (KruzenLab / bagja.dev).
 * Semua parameter dapat dikustomisasi secara real-time lewat StateFlow.
 */
data class MixerSettings(
    val enabled: Boolean = true,
    // --- Jalur 8D ---
    val pathId: String = "lingkaran",
    val customRaw: List<Pt> = emptyList(),
    val smoothLevel: Int = 4,
    val lissajousFreqX: Int = 3,
    val lissajousFreqZ: Int = 2,
    val lissajousPhase: Int = 90,
    val movement: Movement = Movement.LOOP,
    val clockwise: Boolean = true,
    val intervalMs: Float = 3000f,
    val rotationSeconds: Float = 10f,
    val orbitSpeedHz: Float = 0.1f,          // 0.02Hz - 1.5Hz
    // --- Ruang & posisi ---
    val width: Float = 1f,                   // 0.0 - 2.0 (0% - 200%)
    val room: Float = 1f,                    // 0.2 - 3.0 (Close - Far)
    val offsetX: Float = 0f,
    val offsetZ: Float = 0f,
    val transitionSeconds: Float = 0.8f,
    // --- Equalizer 10-Band (-15dB..+15dB) & Remixer ---
    val eq: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,                // -12dB..+12dB
    val bassDb: Float = 0f,                  // 0dB..+15dB
    val subBassPunch: Float = 0f,            // 0f..1f
    val vocalExciter: Float = 0f,            // 0f..1f (Vocal Clarity / Exciter)
    val highpassHz: Float = 20f,
    val lowpassHz: Float = 20000f,
    val channelBalance: Float = 0f,          // -1f (Kiri) .. +1f (Kanan)
    val softLimiter: Boolean = true,         // Anti-clipping soft limiter
    val vocalIsolatorMode: Int = 0,          // 0=Mati, 1=Karaoke (Redam Vokal), 2=Acapella (Isolasi Vokal), 3=Bass Isolator
    // --- Karakter ---
    val drive: Float = 0f,
    val compressor: Float = 0f,
    // --- Gema, Room IR & Echo ---
    val roomPresetId: String = "custom",     // custom, studio, car, hall, field, cathedral
    val customIrUri: String? = null,
    val customIrName: String? = null,
    val reverbMix: Float = 0.25f,
    val reverbDecay: Float = 2.5f,
    val echoMs: Float = 350f,
    val echoFeedback: Float = 0.3f,
    val echoMix: Float = 0f,
    // --- Tempo & nada ---
    val tempo: Float = 1f,                   // 0.5x - 2.0x
    val keepPitch: Boolean = true,
    val pitchSemitones: Float = 0f,          // -12..+12 semitone
    // --- Volume utama ---
    val volume: Float = 0.9f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("pathId", pathId)
        put("customRaw", JSONArray().also { a ->
            customRaw.forEach { a.put(JSONArray().put(it.x.toDouble()).put(it.z.toDouble())) }
        })
        put("smoothLevel", smoothLevel)
        put("lissajousFreqX", lissajousFreqX)
        put("lissajousFreqZ", lissajousFreqZ)
        put("lissajousPhase", lissajousPhase)
        put("movement", movement.id)
        put("clockwise", clockwise)
        put("intervalMs", intervalMs.toDouble())
        put("rotationSeconds", rotationSeconds.toDouble())
        put("orbitSpeedHz", orbitSpeedHz.toDouble())
        put("width", width.toDouble())
        put("room", room.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetZ", offsetZ.toDouble())
        put("transitionSeconds", transitionSeconds.toDouble())
        put("eq", JSONArray().also { a -> eq.forEach { a.put(it.toDouble()) } })
        put("preampDb", preampDb.toDouble())
        put("bassDb", bassDb.toDouble())
        put("subBassPunch", subBassPunch.toDouble())
        put("vocalExciter", vocalExciter.toDouble())
        put("highpassHz", highpassHz.toDouble())
        put("lowpassHz", lowpassHz.toDouble())
        put("channelBalance", channelBalance.toDouble())
        put("softLimiter", softLimiter)
        put("vocalIsolatorMode", vocalIsolatorMode)
        put("drive", drive.toDouble())
        put("compressor", compressor.toDouble())
        put("roomPresetId", roomPresetId)
        put("customIrUri", customIrUri ?: JSONObject.NULL)
        put("customIrName", customIrName ?: JSONObject.NULL)
        put("reverbMix", reverbMix.toDouble())
        put("reverbDecay", reverbDecay.toDouble())
        put("echoMs", echoMs.toDouble())
        put("echoFeedback", echoFeedback.toDouble())
        put("echoMix", echoMix.toDouble())
        put("tempo", tempo.toDouble())
        put("keepPitch", keepPitch)
        put("pitchSemitones", pitchSemitones.toDouble())
        put("volume", volume.toDouble())
    }

    companion object {
        fun fromJson(j: JSONObject): MixerSettings {
            val d = MixerSettings()
            fun f(k: String, def: Float) = if (j.has(k) && !j.isNull(k)) j.optDouble(k, def.toDouble()).toFloat() else def
            fun i(k: String, def: Int) = if (j.has(k) && !j.isNull(k)) j.optInt(k, def) else def
            fun b(k: String, def: Boolean) = if (j.has(k) && !j.isNull(k)) j.optBoolean(k, def) else def
            fun s(k: String): String? = if (j.has(k) && !j.isNull(k)) j.optString(k).takeIf { it.isNotBlank() } else null

            val eqArr = j.optJSONArray("eq")
            val eq = if (eqArr != null && eqArr.length() == 10) List(10) { eqArr.optDouble(it, 0.0).toFloat().coerceIn(-15f, 15f) } else d.eq
            val rawArr = j.optJSONArray("customRaw")
            val raw = if (rawArr != null) List(rawArr.length()) { n ->
                val p = rawArr.getJSONArray(n)
                Pt(p.optDouble(0).toFloat(), p.optDouble(1).toFloat())
            } else emptyList()

            val rotSec = f("rotationSeconds", d.rotationSeconds).coerceIn(0.66f, 60f)
            val hz = f("orbitSpeedHz", (1f / rotSec).coerceIn(0.02f, 1.5f))

            return MixerSettings(
                enabled = b("enabled", d.enabled),
                pathId = j.optString("pathId", d.pathId),
                customRaw = raw,
                smoothLevel = i("smoothLevel", d.smoothLevel),
                lissajousFreqX = i("lissajousFreqX", d.lissajousFreqX),
                lissajousFreqZ = i("lissajousFreqZ", d.lissajousFreqZ),
                lissajousPhase = i("lissajousPhase", d.lissajousPhase),
                movement = Movement.of(j.optString("movement", "loop")),
                clockwise = b("clockwise", d.clockwise),
                intervalMs = f("intervalMs", d.intervalMs),
                rotationSeconds = rotSec,
                orbitSpeedHz = hz,
                width = f("width", d.width).coerceIn(0f, 2f),
                room = f("room", d.room).coerceIn(0.2f, 3f),
                offsetX = f("offsetX", d.offsetX),
                offsetZ = f("offsetZ", d.offsetZ),
                transitionSeconds = f("transitionSeconds", d.transitionSeconds),
                eq = eq,
                preampDb = f("preampDb", d.preampDb).coerceIn(-12f, 12f),
                bassDb = f("bassDb", d.bassDb).coerceIn(0f, 15f),
                subBassPunch = f("subBassPunch", d.subBassPunch).coerceIn(0f, 1f),
                vocalExciter = f("vocalExciter", d.vocalExciter).coerceIn(0f, 1f),
                highpassHz = f("highpassHz", d.highpassHz),
                lowpassHz = f("lowpassHz", d.lowpassHz),
                channelBalance = f("channelBalance", d.channelBalance).coerceIn(-1f, 1f),
                softLimiter = b("softLimiter", d.softLimiter),
                vocalIsolatorMode = i("vocalIsolatorMode", d.vocalIsolatorMode).coerceIn(0, 3),
                drive = f("drive", d.drive),
                compressor = f("compressor", d.compressor),
                roomPresetId = j.optString("roomPresetId", d.roomPresetId),
                customIrUri = s("customIrUri"),
                customIrName = s("customIrName"),
                reverbMix = f("reverbMix", d.reverbMix),
                reverbDecay = f("reverbDecay", d.reverbDecay),
                echoMs = f("echoMs", d.echoMs),
                echoFeedback = f("echoFeedback", d.echoFeedback),
                echoMix = f("echoMix", d.echoMix),
                tempo = f("tempo", d.tempo).coerceIn(0.5f, 2.0f),
                keepPitch = b("keepPitch", d.keepPitch),
                pitchSemitones = f("pitchSemitones", d.pitchSemitones).coerceIn(-12f, 12f),
                volume = f("volume", d.volume),
            )
        }
    }
}
