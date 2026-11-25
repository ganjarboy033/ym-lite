package dev.bagja.spatialaudio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import dev.bagja.spatialaudio.data.MixerSettings
import dev.bagja.spatialaudio.data.TrackAnalysis
import dev.bagja.spatialaudio.dsp.Fft
import dev.bagja.spatialaudio.dsp.MixerEngine
import dev.bagja.spatialaudio.dsp.PathLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

enum class ExportAudioFormat(val label: String, val ext: String) {
    M4A("M4A (AAC 256kbps)", "m4a"),
    WAV("WAV (Lossless PCM)", "wav"),
    MP3_COMPAT("MP3 / Kompatibel", "mp3"),
}

/**
 * Mesin Pemrosesan Audio Offline Laguku (DEV BAGJA / KruzenLab):
 * - Render lagu dengan seluruh efek DSP (8D, EQ, Slowed+Reverb, Nightcore) ke file baru.
 * - Ringtone Cutter (potong durasi start-end & jadikan nada dering).
 * - Ekstraktor Waveform untuk Scrubber interaktif.
 * - Detektor BPM & Nada Dasar (Key) otomatis.
 */
object AudioRenderer {

    private data class DecodedPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /** Dekode berkas audio URI ke PCM 16-bit (opsional trim startMs..endMs & batas durasi analisis). */
    private fun decodeToPcm(
        context: Context,
        uri: Uri,
        startMs: Long = 0L,
        endMs: Long = -1L,
        maxSeconds: Int = 600,
    ): DecodedPcm? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            if (startMs > 0L) extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outStream = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxBytes = sr * ch * 2 * maxSeconds
            val endUs = if (endMs > startMs) endMs * 1000L else Long.MAX_VALUE

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val pts = extractor.sampleTime
                        if (sampleSize < 0 || pts > endUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0 && info.presentationTimeUs >= startMs * 1000L) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk)
                            outStream.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || outStream.size() >= maxBytes) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()

            val rawBytes = outStream.toByteArray()
            val shortCount = rawBytes.size / 2
            val shorts = ShortArray(shortCount)
            ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            DecodedPcm(shorts, sr, ch)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Render lagu lokal dengan seluruh efek 8D, EQ, Reverb, & Tempo/Pitch menjadi file baru. */
    suspend fun renderWithEffects(
        context: Context,
        sourceUri: Uri,
        title: String,
        format: ExportAudioFormat,
        settings: MixerSettings,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        applyDsp: Boolean = true,
        subFolder: String = "Laguku/Remixes",
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.1f)
            val pcm = decodeToPcm(context, sourceUri, trimStartMs, trimEndMs)
                ?: error("Format audio tidak dapat didekode.")
            onProgress(0.35f)

            val sr = pcm.sampleRate
            val inFrames = pcm.samples.size / pcm.channels
            require(inFrames > 0) { "Berkas audio kosong." }

            // Resample sederhana untuk Tempo + Pitch (Slowed+Reverb / Nightcore)
            val rateScale = if (applyDsp) {
                val pitchFactor = 2.0.pow(settings.pitchSemitones / 12.0).toFloat()
                (settings.tempo * (if (settings.keepPitch) 1f else pitchFactor)).coerceIn(0.45f, 2.2f)
            } else 1f

            val stereoFrames = (inFrames / rateScale).toInt().coerceAtLeast(1)
            val outShorts = ShortArray(stereoFrames * 2)

            if (applyDsp && settings.enabled) {
                val engine = MixerEngine(sr, context) {}
                val path = PathLib.resolve(
                    settings.pathId, settings.customRaw, settings.smoothLevel,
                    settings.lissajousFreqX, settings.lissajousFreqZ, settings.lissajousPhase
                )
                val block = 128
                val inL = FloatArray(block); val inR = FloatArray(block)
                val outL = FloatArray(block); val outR = FloatArray(block)

                var done = 0
                while (done < stereoFrames) {
                    val n = minOf(block, stereoFrames - done)
                    for (i in 0 until n) {
                        val srcPos = ((done + i) * rateScale).toInt().coerceIn(0, inFrames - 1)
                        val l = pcm.samples[srcPos * pcm.channels] / 32768f
                        val r = if (pcm.channels == 2) pcm.samples[srcPos * 2 + 1] / 32768f else l
                        inL[i] = l; inR[i] = r
                    }
                    engine.process(inL, inR, outL, outR, n, settings, path, null)
                    for (i in 0 until n) {
                        val idx = (done + i) * 2
                        outShorts[idx] = (outL[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        outShorts[idx + 1] = (outR[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    done += n
                }
            } else {
                for (i in 0 until stereoFrames) {
                    val srcPos = (i * rateScale).toInt().coerceIn(0, inFrames - 1)
                    val l = pcm.samples[srcPos * pcm.channels]
                    val r = if (pcm.channels == 2) pcm.samples[srcPos * 2 + 1] else l
                    outShorts[i * 2] = l
                    outShorts[i * 2 + 1] = r
                }
            }

            onProgress(0.7f)
            val safeName = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ").trim().take(64).ifBlank { "Laguku_Export" }
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), subFolder)
            if (!musicDir.exists()) musicDir.mkdirs()

            val actualExt = if (format == ExportAudioFormat.WAV) "wav" else "m4a"
            val suffix = if (applyDsp) "_8D" else "_Cut"
            val outFile = File(musicDir, "${safeName}${suffix}_${System.currentTimeMillis() % 10000}.$actualExt")

            if (actualExt == "wav") {
                writeWav(outFile, outShorts, sr, 2)
            } else {
                encodeAacM4a(outFile, outShorts, sr, 2)
            }

            onProgress(0.95f)
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            onProgress(1f)
            outFile
        }
    }

    /** Potong bagian lagu & opsional pasang sebagai nada dering. */
    suspend fun cutAndSetRingtone(
        context: Context,
        sourceUri: Uri,
        title: String,
        startMs: Long,
        endMs: Long,
        setAsDefaultRingtone: Boolean,
    ): Result<File> = withContext(Dispatchers.IO) {
        val rendered = renderWithEffects(
            context = context,
            sourceUri = sourceUri,
            title = title,
            format = ExportAudioFormat.M4A,
            settings = MixerSettings(enabled = false),
            trimStartMs = startMs,
            trimEndMs = endMs,
            applyDsp = false,
            subFolder = "Laguku/Ringtones",
        )
        if (setAsDefaultRingtone && rendered.isSuccess && Settings.System.canWrite(context)) {
            val file = rendered.getOrThrow()
            RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, Uri.fromFile(file))
        }
        rendered
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val dataSize = samples.size * 2
        FileOutputStream(file).use { fos ->
            val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put("RIFF".toByteArray())
            hdr.putInt(36 + dataSize)
            hdr.put("WAVEfmt ".toByteArray())
            hdr.putInt(16)
            hdr.putShort(1)
            hdr.putShort(channels.toShort())
            hdr.putInt(sampleRate)
            hdr.putInt(sampleRate * channels * 2)
            hdr.putShort((channels * 2).toShort())
            hdr.putShort(16)
            hdr.put("data".toByteArray())
            hdr.putInt(dataSize)
            fos.write(hdr.array())

            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            var idx = 0
            while (idx < samples.size) {
                buf.clear()
                while (idx < samples.size && buf.remaining() >= 2) {
                    buf.putShort(samples[idx++])
                }
                fos.write(buf.array(), 0, buf.position())
            }
        }
    }

    private fun encodeAacM4a(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val totalBytes = samples.size * 2
        val byteBuf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        byteBuf.asShortBuffer().put(samples)
        val pcmBytes = byteBuf.array()

        var offset = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        val bytesPerSec = sampleRate * channels * 2

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val remaining = totalBytes - offset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, (offset.toLong() * 1_000_000L) / bytesPerSec, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val toWrite = minOf(remaining, inBuf.capacity())
                            inBuf.put(pcmBytes, offset, toWrite)
                            val ptsUs = (offset.toLong() * 1_000_000L) / bytesPerSec
                            codec.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                            offset += toWrite
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop(); codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** Ekstrak 72 batang gelombang suara (waveform) untuk Scrubber interaktif. */
    suspend fun extractWaveform(context: Context, uri: Uri, bars: Int = 72): List<Float> = withContext(Dispatchers.IO) {
        val pcm = decodeToPcm(context, uri, startMs = 0L, endMs = -1L, maxSeconds = 180)
            ?: return@withContext List(bars) { i -> 0.25f + 0.55f * abs(kotlin.math.sin(i * 0.45)).toFloat() }
        val step = (pcm.samples.size / bars).coerceAtLeast(1)
        val raw = FloatArray(bars)
        var maxPeak = 0.01f
        for (b in 0 until bars) {
            val from = b * step
            val to = minOf(pcm.samples.size, from + step)
            var sum = 0f
            var count = 0
            var j = from
            while (j < to) {
                sum += abs(pcm.samples[j] / 32768f)
                count++
                j += 16
            }
            val avg = if (count > 0) sum / count else 0.1f
            raw[b] = avg
            if (avg > maxPeak) maxPeak = avg
        }
        raw.map { (it / maxPeak).coerceIn(0.12f, 1f) }
    }

    /** Deteksi otomatis BPM & Nada Dasar (Key) musik dari cuplikan audio lokal. */
    suspend fun analyzeBpmAndKey(context: Context, uri: Uri): TrackAnalysis = withContext(Dispatchers.IO) {
        val pcm = decodeToPcm(context, uri, startMs = 10_000L, endMs = 40_000L, maxSeconds = 30)
            ?: return@withContext TrackAnalysis(120, "C Mayor")

        val sr = pcm.sampleRate
        val ch = pcm.channels
        val frameCount = pcm.samples.size / ch
        if (frameCount < sr * 2) return@withContext TrackAnalysis(120, "C Mayor")

        // 1. Estimasi BPM via onset energy autocorrelation (jendela 20ms)
        val hop = (sr * 0.02).toInt().coerceAtLeast(128)
        val envLen = frameCount / hop
        val env = FloatArray(envLen)
        for (i in 0 until envLen) {
            var e = 0f
            val base = i * hop * ch
            for (k in 0 until hop) {
                val s = pcm.samples[base + k * ch] / 32768f
                e += s * s
            }
            env[i] = sqrt(e / hop)
        }
        val diff = FloatArray(envLen)
        for (i in 1 until envLen) diff[i] = (env[i] - env[i - 1]).coerceAtLeast(0f)

        val framesPerSec = sr.toDouble() / hop
        val minLag = (framesPerSec * 60.0 / 175.0).toInt().coerceAtLeast(1)
        val maxLag = (framesPerSec * 60.0 / 70.0).toInt().coerceAtLeast(minLag + 1)
        var bestLag = (framesPerSec * 60.0 / 120.0).toInt()
        var bestCorr = -1.0

        for (lag in minLag..minOf(maxLag, envLen / 2)) {
            var corr = 0.0
            for (i in 0 until envLen - lag) corr += diff[i] * diff[i + lag]
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        val bpm = ((60.0 * framesPerSec / bestLag).toInt()).coerceIn(70, 175)

        // 2. Estimasi Key via 12-bin chromagram sederhana
        val fftSize = 2048
        val fft = Fft(fftSize)
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val chroma = DoubleArray(12)
        val numWindows = minOf(12, frameCount / fftSize)
        for (w in 0 until numWindows) {
            val off = w * fftSize * ch
            for (i in 0 until fftSize) {
                val win = 0.5 * (1.0 - cos(2.0 * PI * i / fftSize))
                re[i] = (pcm.samples[off + i * ch] / 32768f) * win.toFloat()
                im[i] = 0f
            }
            fft.transform(re, im, false)
            for (bin in 3 until fftSize / 2) {
                val freq = bin.toDouble() * sr / fftSize
                if (freq in 65.0..1800.0) {
                    val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin]).toDouble()
                    val midi = 69.0 + 12.0 * kotlin.math.log2(freq / 440.0)
                    val pc = ((midi.toInt() % 12) + 12) % 12
                    chroma[pc] += mag
                }
            }
        }

        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val majorProf = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minorProf = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        var bestKey = "C Mayor"
        var bestScore = -1.0
        for (root in 0 until 12) {
            var maj = 0.0; var min = 0.0
            for (i in 0 until 12) {
                val c = chroma[(root + i) % 12]
                maj += c * majorProf[i]
                min += c * minorProf[i]
            }
            if (maj > bestScore) { bestScore = maj; bestKey = "${noteNames[root]} Mayor" }
            if (min > bestScore) { bestScore = min; bestKey = "${noteNames[root]} Minor" }
        }

        TrackAnalysis(bpm, bestKey)
    }
}
