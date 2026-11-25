package dev.bagja.spatialaudio.audio

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import dev.bagja.spatialaudio.data.EQ_FREQUENCIES
import dev.bagja.spatialaudio.data.MixerRepository
import dev.bagja.spatialaudio.data.MixerSettings
import dev.bagja.spatialaudio.data.UniversalAudioMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mode B: Zero-Latency System Global EQ (0ms latency) memakai Android DynamicsProcessing
 * yang ditempelkan pada Global Output Mix (audioSessionId = 0).
 * Bekerja di seluruh sistem (Game, YouTube, TikTok, Spotify, dll.) tanpa izin rekam layar.
 * Karya DEV BAGJA / KruzenLab (bagja.dev).
 */
object SystemGlobalEqManager {
    private const val TAG = "SystemGlobalEq"
    private var processor: DynamicsProcessing? = null

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun setEnabled(enabled: Boolean, settings: MixerSettings = MixerRepository.settings.value) {
        if (!enabled) {
            release()
            MixerRepository.globalEqActive.value = false
            if (MixerRepository.universalMode.value == UniversalAudioMode.GLOBAL_EQ) {
                MixerRepository.universalMode.value = UniversalAudioMode.OFF
            }
            _statusMessage.value = null
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _statusMessage.value = "Global EQ membutuhkan Android 9 (API 28) ke atas."
            MixerRepository.globalEqActive.value = false
            return
        }

        runCatching {
            if (processor == null) {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 0,          // preEq mati
                    true, 1,          // MBC 1 band (sub-bass/kompresor)
                    true, 10,         // postEq 10 band
                    true              // Limiter aktif
                ).build()
                processor = DynamicsProcessing(0, 0, cfg)
            }
            processor?.enabled = true
            apply(settings)
            MixerRepository.globalEqActive.value = true
            MixerRepository.universalMode.value = UniversalAudioMode.GLOBAL_EQ
            _statusMessage.value = "Global System EQ aktif (0ms latensi pada output sistem)."
        }.onFailure { err ->
            Log.w(TAG, "DynamicsProcessing session 0 failed", err)
            release()
            MixerRepository.globalEqActive.value = false
            if (MixerRepository.universalMode.value == UniversalAudioMode.GLOBAL_EQ) {
                MixerRepository.universalMode.value = UniversalAudioMode.OFF
            }
            _statusMessage.value = "Perangkat ini membatasi Global EQ session 0 (${err.message ?: "tidak didukung"}). Gunakan Mode A (Universal 8D Capture)."
        }
    }

    fun apply(s: MixerSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dp = processor ?: return
        if (!MixerRepository.globalEqActive.value) return

        runCatching {
            dp.enabled = s.enabled
            val channelCount = dp.channelCount
            for (ch in 0 until channelCount) {
                // Input Gain (Preamp)
                dp.setInputGainbyChannel(ch, s.preampDb.coerceIn(-12f, 12f))

                // 10-Band Post EQ + Bass Boost + Sub-Bass + Vocal Exciter
                val postEq = dp.getPostEqByChannelIndex(ch)
                postEq.isEnabled = true
                for (b in 0 until 10) {
                    val band = postEq.getBand(b)
                    val extra = when (b) {
                        0 -> s.bassDb * 0.65f + s.subBassPunch * 8f
                        1 -> s.bassDb * 0.85f + s.subBassPunch * 4f
                        2 -> s.bassDb * 0.45f
                        6, 7 -> s.vocalExciter * 5f
                        else -> 0f
                    }
                    band.isEnabled = true
                    band.cutoffFrequency = EQ_FREQUENCIES[b]
                    band.gain = (s.eq.getOrElse(b) { 0f } + extra).coerceIn(-15f, 15f)
                    postEq.setBand(b, band)
                }
                dp.setPostEqByChannelIndex(ch, postEq)

                // Soft Limiter anti-clipping
                val limiter = dp.getLimiterByChannelIndex(ch)
                limiter.isEnabled = s.softLimiter
                limiter.threshold = -1.5f
                limiter.ratio = 8f
                limiter.attackTime = 3f
                limiter.releaseTime = 60f
                limiter.postGain = 0f
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed updating DynamicsProcessing", err)
        }
    }

    fun release() {
        runCatching {
            processor?.enabled = false
            processor?.release()
        }
        processor = null
    }
}
