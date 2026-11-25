package dev.bagja.spatialaudio.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.bagja.spatialaudio.data.MixerRepository
import dev.bagja.spatialaudio.data.MixerSettings
import dev.bagja.spatialaudio.data.PanFrame
import dev.bagja.spatialaudio.dsp.MixerEngine
import dev.bagja.spatialaudio.dsp.PathLib
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Jembatan antara ExoPlayer dan MixerEngine.
 * Mendukung ReplayGain & Sleep Fade-Out volume scaling secara real-time.
 */
@UnstableApi
class MixerProcessor(private val context: Context? = null) : BaseAudioProcessor() {

    @Volatile var settings: MixerSettings = MixerSettings()
    @Volatile var path: FloatArray = PathLib.builtinById("lingkaran")!!.build()
    @Volatile var externalVolumeScale: Float = 1f
    @Volatile var replayGainEnabled: Boolean = false

    private var engine: MixerEngine? = null
    private var inChannels = 2
    private val inL = FloatArray(BLOCK); private val inR = FloatArray(BLOCK)
    private val outL = FloatArray(BLOCK); private val outR = FloatArray(BLOCK)
    private var rgEnv = 0.25f
    @Volatile private var cachedPathKey: String = ""

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inChannels = inputAudioFormat.channelCount
        engine = MixerEngine(inputAudioFormat.sampleRate, context) { frame ->
            MixerRepository.frame.value = frame
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * inChannels)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4)
        val eng = engine
        // Selalu baca state terbaru dari repository agar kontrol HTML/Capacitor langsung
        // memengaruhi jalur PCM tanpa menunggu host menyetel ulang processor.
        val s = MixerRepository.settings.value
        settings = s
        val pathKey = "${s.pathId}|${s.lissajousFreqX}|${s.lissajousFreqZ}|${s.lissajousPhase}|${s.smoothLevel}|${s.customRaw.hashCode()}"
        if (pathKey != cachedPathKey) {
            path = when (s.pathId) {
                PathLib.LISSAJOUS -> PathLib.lissajous(s.lissajousFreqX, s.lissajousFreqZ, s.lissajousPhase)
                PathLib.CUSTOM -> PathLib.smoothCustom(s.customRaw, s.smoothLevel)
                else -> PathLib.builtinById(s.pathId)?.build() ?: PathLib.builtinById("lingkaran")!!.build()
            }
            cachedPathKey = pathKey
        }
        val resolvedPath = path
        val volScale = externalVolumeScale

        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            var peak = 0f
            for (i in 0 until n) {
                val l = inputBuffer.short / 32768f
                val r = if (inChannels == 2) inputBuffer.short / 32768f else l
                inL[i] = l; inR[i] = r
                val p = maxOf(abs(l), abs(r))
                if (p > peak) peak = p
            }

            val rgGain = if (replayGainEnabled) {
                rgEnv = rgEnv * 0.992f + peak * 0.008f
                (0.28f / rgEnv.coerceAtLeast(0.08f)).coerceIn(0.6f, 1.8f)
            } else 1f

            if (eng != null && s.enabled) {
                eng.process(inL, inR, outL, outR, n, s, resolvedPath, MixerRepository.override)
                for (i in 0 until n) {
                    out.putShort(toShort(outL[i] * volScale * rgGain))
                    out.putShort(toShort(outR[i] * volScale * rgGain))
                }
            } else {
                for (i in 0 until n) {
                    out.putShort(toShort(inL[i] * volScale * rgGain))
                    out.putShort(toShort(inR[i] * volScale * rgGain))
                }
            }
            done += n
        }
        out.flip()
    }

    private fun toShort(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    override fun onFlush() { engine?.reset() }

    override fun onReset() { engine = null; cachedPathKey = "" }

    private companion object { const val BLOCK = 128 }
}
