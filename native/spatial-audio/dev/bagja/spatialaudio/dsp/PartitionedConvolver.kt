package dev.bagja.spatialaudio.dsp

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT radix-2 sederhana (in-place) untuk ukuran pangkat dua. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** inverse=true menghasilkan hasil yang sudah dibagi n. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) { re[i] *= s; im[i] *= s }
        }
    }
}

/**
 * Konvolusi untuk reverb & Custom Impulse Response (.wav IR Loader).
 * Impuls panjang dipecah jadi potongan 1024 sampel dan dihitung lewat FFT (overlap-save).
 */
class PartitionedConvolver(ir: FloatArray) {
    private val b = BLOCK
    private val f = 2 * BLOCK
    private val k = BLOCK + 1
    private val parts = maxOf(1, (ir.size + b - 1) / b)
    private val fft = Fft(f)

    private val hRe = FloatArray(parts * k)
    private val hIm = FloatArray(parts * k)
    private val xRe = FloatArray(parts * k)
    private val xIm = FloatArray(parts * k)
    private var head = 0

    private val win = FloatArray(f)
    private val tRe = FloatArray(f); private val tIm = FloatArray(f)
    private val aRe = FloatArray(f); private val aIm = FloatArray(f)
    private val inBlock = FloatArray(b); private val outBlock = FloatArray(b)
    private var pos = 0

    init {
        for (p in 0 until parts) {
            tRe.fill(0f); tIm.fill(0f)
            val from = p * b
            val len = minOf(b, maxOf(0, ir.size - from))
            if (len > 0) System.arraycopy(ir, from, tRe, 0, len)
            fft.transform(tRe, tIm, false)
            System.arraycopy(tRe, 0, hRe, p * k, k)
            System.arraycopy(tIm, 0, hIm, p * k, k)
        }
    }

    fun tick(x: Float): Float {
        inBlock[pos] = x
        val y = outBlock[pos]
        pos++
        if (pos == b) { pos = 0; computeBlock() }
        return y
    }

    private fun computeBlock() {
        System.arraycopy(win, b, win, 0, b)
        System.arraycopy(inBlock, 0, win, b, b)
        System.arraycopy(win, 0, tRe, 0, f); tIm.fill(0f)
        fft.transform(tRe, tIm, false)

        head = (head - 1 + parts) % parts
        System.arraycopy(tRe, 0, xRe, head * k, k)
        System.arraycopy(tIm, 0, xIm, head * k, k)

        aRe.fill(0f, 0, k); aIm.fill(0f, 0, k)
        for (p in 0 until parts) {
            val xo = ((head + p) % parts) * k
            val ho = p * k
            for (i in 0 until k) {
                val xr = xRe[xo + i]; val xi = xIm[xo + i]
                val hr = hRe[ho + i]; val hi = hIm[ho + i]
                aRe[i] += xr * hr - xi * hi
                aIm[i] += xr * hi + xi * hr
            }
        }
        for (i in 1 until b) { aRe[f - i] = aRe[i]; aIm[f - i] = -aIm[i] }
        fft.transform(aRe, aIm, true)
        System.arraycopy(aRe, b, outBlock, 0, b)
    }

    fun reset() {
        xRe.fill(0f); xIm.fill(0f); win.fill(0f); inBlock.fill(0f); outBlock.fill(0f)
        pos = 0; head = 0
    }

    companion object {
        const val BLOCK = 1024

        /** Impuls gema standar / preset ruang akustik (Studio, Car Audio, Concert Hall, Sound Lapangan, Cathedral). */
        fun buildPresetIr(roomPresetId: String, seconds: Float, sampleRate: Int, seed: Long = 7L): Array<FloatArray> {
            val dur = when (roomPresetId) {
                "studio" -> minOf(seconds, 1.2f)
                "car" -> minOf(seconds, 0.7f)
                "hall" -> maxOf(seconds, 2.8f)
                "field" -> maxOf(seconds, 1.8f)
                "cathedral" -> maxOf(seconds, 5.2f)
                else -> seconds
            }.coerceIn(0.25f, 8.0f)

            val len = maxOf(1, (sampleRate * dur).toInt())
            val rnd = Random(seed)
            val ir = Array(2) { FloatArray(len) }
            var sumSq = 0.0

            val power = when (roomPresetId) {
                "studio" -> 4.2
                "car" -> 5.0
                "field" -> 2.4
                "cathedral" -> 2.2
                else -> 3.0
            }

            // Early reflections spesifik tiap ruangan
            val earlyTapsMs = when (roomPresetId) {
                "studio" -> listOf(8, 17, 29, 43)
                "car" -> listOf(3, 7, 11, 18, 25)
                "hall" -> listOf(22, 45, 68, 95, 130)
                "field" -> listOf(45, 90, 155, 220)
                "cathedral" -> listOf(35, 72, 115, 168, 240, 320)
                else -> emptyList()
            }

            for (c in 0 until 2) {
                var lp = 0.0
                val damp = when (roomPresetId) {
                    "car" -> 0.45
                    "cathedral" -> 0.35
                    "studio" -> 0.75
                    else -> 0.85
                }
                for (i in 0 until len) {
                    val env = (1.0 - i.toDouble() / len).pow(power)
                    val noise = (rnd.nextDouble() * 2 - 1) * env
                    lp += damp * (noise - lp)
                    val v = lp.toFloat()
                    ir[c][i] = v
                    sumSq += v.toDouble() * v
                }
                earlyTapsMs.forEachIndexed { idx, ms ->
                    val pos = (ms * sampleRate / 1000) + (c * 13)
                    if (pos in 0 until len) {
                        val amp = (0.35 * exp(-idx * 0.35)).toFloat() * (if (idx % 2 == 0) 1f else -1f)
                        ir[c][pos] += amp
                        sumSq += amp * amp
                    }
                }
            }

            val rms = sqrt(sumSq / (2.0 * len)).coerceAtLeast(0.000125)
            var scale = (1.0 / rms) * 10.0.pow(-58.0 / 20.0)
            if (sampleRate != 44100) scale *= 44100.0 / sampleRate
            for (c in 0 until 2) for (i in 0 until len) ir[c][i] = (ir[c][i] * scale).toFloat()
            return ir
        }

        fun buildReverbIr(seconds: Float, sampleRate: Int, seed: Long = 7L): Array<FloatArray> =
            buildPresetIr("custom", seconds, sampleRate, seed)

        /** Muat file .wav Impulse Response kustom dari penyimpanan HP (16/24/32-bit PCM & IEEE Float). */
        fun loadWavIr(context: Context, uri: Uri, targetSampleRate: Int): Array<FloatArray>? = runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            if (bytes.size < 44) return@runCatching null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) return@runCatching null // RIFF / WAVE

            var pos = 12
            var audioFormat = 1
            var channels = 1
            var sr = targetSampleRate
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (pos + 8 <= bytes.size) {
                val chunkId = bb.getInt(pos)
                val chunkSize = bb.getInt(pos + 4)
                val bodyPos = pos + 8
                if (chunkId == 0x20746d66 && bodyPos + 16 <= bytes.size) { // "fmt "
                    audioFormat = bb.getShort(bodyPos).toInt() and 0xFFFF
                    channels = (bb.getShort(bodyPos + 2).toInt() and 0xFFFF).coerceIn(1, 2)
                    sr = bb.getInt(bodyPos + 4).coerceIn(8000, 192000)
                    bitsPerSample = bb.getShort(bodyPos + 14).toInt() and 0xFFFF
                } else if (chunkId == 0x61746164) { // "data"
                    dataOffset = bodyPos
                    dataSize = minOf(chunkSize, bytes.size - bodyPos)
                    break
                }
                pos = bodyPos + chunkSize + (chunkSize and 1)
            }

            if (dataOffset < 0 || dataSize <= 0) return@runCatching null
            val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
            val frameSize = bytesPerSample * channels
            val totalFrames = minOf(dataSize / frameSize, sr * 8) // Maksimal 8 detik agar aman RAM
            if (totalFrames <= 0) return@runCatching null

            val rawL = FloatArray(totalFrames)
            val rawR = FloatArray(totalFrames)
            for (i in 0 until totalFrames) {
                for (c in 0 until channels) {
                    val off = dataOffset + i * frameSize + c * bytesPerSample
                    val sample = when {
                        audioFormat == 3 && bitsPerSample == 32 -> bb.getFloat(off).coerceIn(-1f, 1f)
                        bitsPerSample == 16 -> bb.getShort(off) / 32768f
                        bitsPerSample == 24 -> {
                            val b0 = bytes[off].toInt() and 0xFF
                            val b1 = bytes[off + 1].toInt() and 0xFF
                            val b2 = bytes[off + 2].toInt()
                            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                        }
                        bitsPerSample == 32 -> bb.getInt(off) / 2147483648f
                        else -> ((bytes[off].toInt() and 0xFF) - 128) / 128f
                    }
                    if (c == 0) rawL[i] = sample else rawR[i] = sample
                }
                if (channels == 1) rawR[i] = rawL[i]
            }

            val resL = resample(rawL, sr, targetSampleRate)
            val resR = resample(rawR, sr, targetSampleRate)

            // Normalisasi level konvolusi agar volume konsisten
            var sumSq = 0.0
            for (i in resL.indices) {
                sumSq += resL[i] * resL[i] + resR[i] * resR[i]
            }
            val rms = sqrt(sumSq / (2.0 * resL.size)).coerceAtLeast(1e-4)
            val scale = ((1.0 / rms) * 10.0.pow(-52.0 / 20.0)).toFloat()
            for (i in resL.indices) {
                resL[i] *= scale
                resR[i] *= scale
            }
            arrayOf(resL, resR)
        }.getOrNull()

        private fun resample(src: FloatArray, fromSr: Int, toSr: Int): FloatArray {
            if (fromSr == toSr || src.isEmpty()) return src
            val outLen = maxOf(1, (src.size.toLong() * toSr / fromSr).toInt())
            val out = FloatArray(outLen)
            val ratio = fromSr.toDouble() / toSr.toDouble()
            for (i in 0 until outLen) {
                val p = i * ratio
                val i0 = p.toInt().coerceIn(0, src.size - 1)
                val i1 = (i0 + 1).coerceIn(0, src.size - 1)
                val frac = (p - i0).toFloat()
                out[i] = src[i0] * (1f - frac) + src[i1] * frac
            }
            return out
        }
    }
}
