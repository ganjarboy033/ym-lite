package dev.bagja.spatialaudio.data

/** Frekuensi & label 10 band EQ. */
val EQ_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

class EqPreset(val label: String, val gains: List<Float>)

val EQ_PRESETS = listOf(
    EqPreset("Datar", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass", listOf(9f, 8f, 6f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Elektro", listOf(7f, 6f, 2f, 0f, -2f, -1f, 2f, 4f, 6f, 6f)),
    EqPreset("Rock", listOf(6f, 5f, 2f, -1f, -2f, 0f, 3f, 4f, 5f, 5f)),
    EqPreset("Pop", listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, 2f, 3f)),
    EqPreset("Vokal", listOf(-3f, -2f, -1f, 0f, 3f, 5f, 5f, 3f, 1f, -1f)),
    EqPreset("Jazz", listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 2f, 3f, 4f)),
    EqPreset("Cerah", listOf(-4f, -3f, -2f, -1f, 0f, 2f, 4f, 6f, 7f, 7f)),
    EqPreset("Klasik", listOf(4f, 3f, 2f, 1f, 0f, 0f, -1f, -2f, -2f, -3f)),
)

data class RoomPreset(val id: String, val label: String, val decay: Float, val mix: Float, val dampingHz: Float)

val ROOM_PRESETS = listOf(
    RoomPreset("custom", "Standar", 2.5f, 0.25f, 18000f),
    RoomPreset("studio", "Studio", 0.9f, 0.16f, 14000f),
    RoomPreset("car", "Car Audio", 0.6f, 0.22f, 9500f),
    RoomPreset("hall", "Concert Hall", 3.4f, 0.36f, 12000f),
    RoomPreset("field", "Sound Lapangan", 1.8f, 0.28f, 16000f),
    RoomPreset("cathedral", "Cathedral", 6.2f, 0.48f, 8000f),
)

class BuiltinPreset(val id: String, val name: String, val description: String, val build: () -> MixerSettings)

val BUILTIN_PRESETS = listOf(
    BuiltinPreset("klasik", "Klasik 8D", "Lingkaran pelan dengan ruang gema lembut") {
        MixerSettings(pathId = "lingkaran", rotationSeconds = 10f, orbitSpeedHz = 0.1f, reverbMix = 0.25f, reverbDecay = 2.5f)
    },
    BuiltinPreset("bolak-balik", "Bolak-balik Kiri Kanan", "Arah berubah setiap 2 detik di jalur kiri ke kanan") {
        MixerSettings(pathId = "kiri-kanan", movement = Movement.PINGPONG, intervalMs = 2000f, rotationSeconds = 6f, orbitSpeedHz = 0.167f, reverbMix = 0.15f)
    },
    BuiltinPreset("angka-8", "Angka 8 Melayang", "Jalur angka 8 dengan gema panjang dan bass hangat") {
        MixerSettings(
            pathId = "angka-8", rotationSeconds = 14f, orbitSpeedHz = 0.071f,
            reverbMix = 0.4f, reverbDecay = 4.5f, roomPresetId = "hall", bassDb = 6f, subBassPunch = 0.35f,
            eq = listOf(5f, 4f, 2f, 0f, 0f, 0f, 0f, 1f, 2f, 2f)
        )
    },
    BuiltinPreset("slowed-reverb", "Slowed + Reverb", "Tempo santai 0.85x, nada hangat, dan gema Cathedral luas") {
        MixerSettings(
            pathId = "lingkaran", rotationSeconds = 16f, orbitSpeedHz = 0.0625f,
            tempo = 0.85f, keepPitch = false, roomPresetId = "cathedral",
            reverbMix = 0.48f, reverbDecay = 6.0f, bassDb = 4f, lowpassHz = 12000f
        )
    },
    BuiltinPreset("nightcore", "Nightcore Boost", "Tempo cepat 1.25x dengan vokal jernih dan bass punch") {
        MixerSettings(
            pathId = "spiral", rotationSeconds = 6f, orbitSpeedHz = 0.167f,
            tempo = 1.25f, keepPitch = false, vocalExciter = 0.4f, subBassPunch = 0.4f,
            reverbMix = 0.15f, eq = listOf(4f, 3f, 1f, 0f, 1f, 2f, 3f, 4f, 5f, 4f)
        )
    },
    BuiltinPreset("lorong", "Lorong Gema", "Depan ke belakang dengan echo dan ruang luas") {
        MixerSettings(
            pathId = "depan-belakang", movement = Movement.PINGPONG, intervalMs = 4000f, rotationSeconds = 8f,
            reverbMix = 0.5f, reverbDecay = 6f, echoMs = 420f, echoFeedback = 0.45f, echoMix = 0.3f
        )
    },
    BuiltinPreset("malam", "Malam Hangat", "Elips pelan, bass tebal, nada lembut untuk TWS/Headset") {
        MixerSettings(
            pathId = "elips", rotationSeconds = 18f, bassDb = 7f, subBassPunch = 0.4f, lowpassHz = 9000f,
            eq = listOf(5f, 4f, 2f, 1f, 0f, -1f, -1f, -2f, -3f, -4f),
            reverbMix = 0.35f, reverbDecay = 3.5f, compressor = 0.3f
        )
    },
    BuiltinPreset("speaker-boost", "Speaker Clarity", "Khusus speaker HP: suara kencang, vokal tegas, bebas pecah") {
        MixerSettings(
            pathId = "pendulum", width = 0.4f, preampDb = 4f, vocalExciter = 0.55f,
            reverbMix = 0.08f, compressor = 0.45f, softLimiter = true,
            eq = listOf(-2f, 0f, 2f, 3f, 3f, 4f, 4f, 3f, 2f, 1f)
        )
    },
)
