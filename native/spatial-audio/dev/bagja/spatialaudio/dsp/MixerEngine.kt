package dev.bagja.spatialaudio.dsp

import android.content.Context
import androidx.core.net.toUri
import dev.bagja.spatialaudio.data.EQ_FREQUENCIES
import dev.bagja.spatialaudio.data.MixerSettings
import dev.bagja.spatialaudio.data.Movement
import dev.bagja.spatialaudio.data.PanFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * MESIN EFEK DSP UTAMA LAGUKU (KruzenLab / bagja.dev).
 *
 * Rantai pemrosesan stereo real-time (blok <= 128 sampel):
 *  Preamp -> Isolator Vokal/Bass -> EQ 10-Band (-15/+15dB) -> Bass & Sub-Bass Punch
 *  -> Vocal Exciter -> High/Low-Pass -> Drive -> Kompresor -> Echo
 *  -> Pemosisi 8D -> [Dry | Reverb/Custom IR]
 *  -> Balance L/R -> Volume Utama -> Soft Limiter -> Analisis Spektrum 24-Band
 */
class MixerEngine(
    private val sampleRate: Int,
    private val context: Context? = null,
    private val onFrame: (PanFrame) -> Unit,
) {
    private val fs = sampleRate.toDouble()

    private class Smooth(v: Double) {
        var cur = v; var tgt = v
        fun step(a: Double): Double { cur += (tgt - cur) * a; return cur }
    }

    // ---------- Preamp, EQ, Bass, Exciter & Filter ----------
    private val preampGain = Smooth(1.0)
    private val eqGain = Array(10) { Smooth(0.0) }
    private val eqL = Array(10) { Biquad() }
    private val eqR = Array(10) { Biquad() }
    private val eqLast = DoubleArray(10) { Double.NaN }

    private val bassGain = Smooth(0.0); private val bassL = Biquad(); private val bassR = Biquad(); private var bassLast = Double.NaN
    private val subBassGain = Smooth(0.0); private val subL = Biquad(); private val subR = Biquad(); private var subLast = Double.NaN
    private val exciterGain = Smooth(0.0); private val excL = Biquad(); private val excR = Biquad(); private var excLast = Double.NaN

    private val hpFreq = Smooth(20.0); private val hpL = Biquad(); private val hpR = Biquad(); private var hpLast = Double.NaN
    private val lpFreq = Smooth(20000.0); private val lpL = Biquad(); private val lpR = Biquad(); private var lpLast = Double.NaN

    // ---------- Isolator Vokal & Bass (Mid/Side + Crossover) ----------
    private var isolatorMode = 0
    private val isoBassL = Biquad(); private val isoBassR = Biquad()
    private val isoMidL = Biquad(); private val isoMidR = Biquad()

    // ---------- Saturasi & Kompresor ----------
    private var driveK = 0.0
    private var driveNorm = 1.0
    private var compOn = false
    private var compThr = 0.0
    private var compRatio = 1.0
    private var compMakeupDb = 0.0
    private var grDb = 0.0
    private val atkCoef = 1 - exp(-1.0 / (0.003 * fs))
    private val relCoef = 1 - exp(-1.0 / (0.25 * fs))

    // ---------- Echo ----------
    private val echoBufL = FloatArray((fs * 2).toInt() + 8)
    private val echoBufR = FloatArray(echoBufL.size)
    private var echoW = 0
    private val echoDelay = Smooth(0.35 * fs); private val echoFb = Smooth(0.3); private val echoMix = Smooth(0.0)

    // ---------- 8D, Reverb, Balance & Limiter ----------
    private val panner = BinauralPanner(sampleRate)
    private val motion = MotionOrbit()
    private val dryGain = Smooth(0.75); private val wetGain = Smooth(0.25); private val master = Smooth(0.9)
    private val balL = Smooth(1.0); private val balR = Smooth(1.0)
    private var softLimiterOn = true

    @Volatile private var convL: PartitionedConvolver? = null
    @Volatile private var convR: PartitionedConvolver? = null
    private var room = 1f

    // ---------- Visualizer 24-Band ----------
    private val vizFft = Fft(128)
    private val vizRe = FloatArray(128)
    private val vizIm = FloatArray(128)
    private val smoothSpec = FloatArray(24)

    private var lastSettings: MixerSettings? = null
    private var lastPath: FloatArray? = null
    private var sinceReport = 0

    init {
        isoBassL.lowpass(fs, 180.0, 0.707); isoBassR.lowpass(fs, 180.0, 0.707)
        isoMidL.bandpass(fs, 1400.0, 0.65); isoMidR.bandpass(fs, 1400.0, 0.65)
    }

    private fun applySettings(s: MixerSettings) {
        preampGain.tgt = 10.0.pow(s.preampDb / 20.0)
        for (i in 0 until 10) eqGain[i].tgt = s.eq.getOrElse(i) { 0f }.toDouble().coerceIn(-15.0, 15.0)
        bassGain.tgt = s.bassDb.toDouble()
        subBassGain.tgt = (s.subBassPunch * 10.0).toDouble()
        exciterGain.tgt = (s.vocalExciter * 8.0).toDouble()
        hpFreq.tgt = s.highpassHz.toDouble(); lpFreq.tgt = s.lowpassHz.toDouble()
        isolatorMode = s.vocalIsolatorMode

        if (s.drive > 0f) { driveK = 1.0 + s.drive * 40.0; driveNorm = tanh(driveK) } else driveK = 0.0

        val c = s.compressor.toDouble()
        compOn = c > 0.001
        compThr = if (c > 0) -10.0 - 30.0 * c else 0.0
        compRatio = 1.0 + 7.0 * c
        compMakeupDb = -0.6 * gainReductionDb(0.0)

        echoDelay.tgt = s.echoMs / 1000.0 * fs
        echoFb.tgt = s.echoFeedback.toDouble(); echoMix.tgt = s.echoMix.toDouble()


        dryGain.tgt = 1.0 - s.reverbMix; wetGain.tgt = s.reverbMix.toDouble()
        master.tgt = s.volume.toDouble()
        softLimiterOn = s.softLimiter
        room = s.room

        // Channel Balance L/R
        balL.tgt = (1.0 - max(0.0, s.channelBalance.toDouble())).coerceIn(0.0, 1.0)
        balR.tgt = (1.0 - max(0.0, -s.channelBalance.toDouble())).coerceIn(0.0, 1.0)

        motion.movement = when (s.movement) { Movement.LOOP -> 0; Movement.PINGPONG -> 1; Movement.RANDOM -> 2 }
        motion.direction = if (s.clockwise) 1 else -1
        motion.intervalSeconds = s.intervalMs / 1000f
        motion.rotationSeconds = if (s.orbitSpeedHz > 0.015f) (1f / s.orbitSpeedHz).coerceIn(0.66f, 60f) else s.rotationSeconds
        motion.width = s.width
        motion.offsetX = s.offsetX
        motion.offsetZ = s.offsetZ
        motion.transitionSeconds = s.transitionSeconds
        motion.roomRadius = ROOM_RADIUS

        val irKey = "${s.roomPresetId}|${s.reverbDecay}|${s.customIrUri.orEmpty()}"
        if (irKey != builtIrKey) requestReverb(irKey, s.roomPresetId, s.reverbDecay, s.customIrUri)
    }

    private fun gainReductionDb(x: Double): Double {
        val over = x - compThr
        return when {
            2 * over < -KNEE -> 0.0
            2 * abs(over) <= KNEE -> (1 / compRatio - 1) * (over + KNEE / 2) * (over + KNEE / 2) / (2 * KNEE)
            else -> over / compRatio - over
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "reverb-ir").apply { isDaemon = true } }
    private val building = AtomicBoolean(false)
    @Volatile private var wantedIrKey = ""
    @Volatile private var builtIrKey = ""
    @Volatile private var pendingPreset = "custom"
    @Volatile private var pendingDecay = 2.5f
    @Volatile private var pendingIrUri: String? = null

    private fun requestReverb(key: String, presetId: String, decay: Float, irUri: String?) {
        wantedIrKey = key
        pendingPreset = presetId
        pendingDecay = decay
        pendingIrUri = irUri
        if (!building.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val k = wantedIrKey
                    val p = pendingPreset
                    val d = pendingDecay
                    val u = pendingIrUri
                    val customIr = if (!u.isNullOrBlank() && context != null) {
                        PartitionedConvolver.loadWavIr(context, u.toUri(), sampleRate)
                    } else null
                    val ir = customIr ?: PartitionedConvolver.buildPresetIr(p, d, sampleRate)
                    convL = PartitionedConvolver(ir[0])
                    convR = PartitionedConvolver(ir[1])
                    builtIrKey = k
                    if (k == wantedIrKey) break
                }
            } finally {
                building.set(false)
                if (wantedIrKey != builtIrKey) requestReverb(wantedIrKey, pendingPreset, pendingDecay, pendingIrUri)
            }
        }
    }

    fun reset() {
        eqL.forEach { it.reset() }; eqR.forEach { it.reset() }
        bassL.reset(); bassR.reset(); subL.reset(); subR.reset(); excL.reset(); excR.reset()
        hpL.reset(); hpR.reset(); lpL.reset(); lpR.reset()
        isoBassL.reset(); isoBassR.reset(); isoMidL.reset(); isoMidR.reset()
        echoBufL.fill(0f); echoBufR.fill(0f)
        panner.reset(); grDb = 0.0
        convL?.reset(); convR?.reset()
    }

    private fun readDelay(buf: FloatArray, w: Int, d: Double): Float {
        val dd = d.coerceIn(1.0, (buf.size - 3).toDouble())
        val di = dd.toInt()
        val frac = (dd - di).toFloat()
        var i0 = w - di; if (i0 < 0) i0 += buf.size
        var i1 = i0 - 1; if (i1 < 0) i1 += buf.size
        return buf[i0] * (1 - frac) + buf[i1] * frac
    }

    private fun shape(x: Float): Float {
        val c = x.coerceIn(-1f, 1f)
        return (tanh(c * driveK) / driveNorm).toFloat()
    }

    fun process(
        inL: FloatArray,
        inR: FloatArray,
        outL: FloatArray,
        outR: FloatArray,
        n: Int,
        s: MixerSettings,
        path: FloatArray,
        override: FloatArray?,
    ) {
        if (s !== lastSettings) { applySettings(s); lastSettings = s }
        if (path !== lastPath) { motion.path = path; lastPath = path }
        motion.override = override

        val blockSec = n / fs
        val a = 1 - exp(-blockSec / 0.03)
        preampGain.step(a)

        for (i in 0 until 10) {
            val g = eqGain[i].step(a)
            if (abs(g - eqLast[i]) > 1e-4 || eqLast[i].isNaN()) {
                eqL[i].peaking(fs, EQ_FREQUENCIES[i].toDouble(), 1.1, g)
                eqR[i].peaking(fs, EQ_FREQUENCIES[i].toDouble(), 1.1, g)
                eqLast[i] = g
            }
        }
        bassGain.step(a).let { if (abs(it - bassLast) > 1e-4 || bassLast.isNaN()) { bassL.lowShelf(fs, 100.0, it); bassR.lowShelf(fs, 100.0, it); bassLast = it } }
        subBassGain.step(a).let { if (abs(it - subLast) > 1e-4 || subLast.isNaN()) { subL.lowShelf(fs, 55.0, it); subR.lowShelf(fs, 55.0, it); subLast = it } }
        exciterGain.step(a).let { if (abs(it - excLast) > 1e-4 || excLast.isNaN()) { excL.peaking(fs, 3600.0, 0.8, it); excR.peaking(fs, 3600.0, 0.8, it); excLast = it } }
        hpFreq.step(a).let { if (abs(it - hpLast) > 0.01 || hpLast.isNaN()) { hpL.highpass(fs, it, 0.707); hpR.highpass(fs, it, 0.707); hpLast = it } }
        lpFreq.step(a).let { if (abs(it - lpLast) > 0.5 || lpLast.isNaN()) { lpL.lowpass(fs, it, 0.707); lpR.lowpass(fs, it, 0.707); lpLast = it } }

        echoDelay.step(a); echoFb.step(a); echoMix.step(a)
        dryGain.step(a); wetGain.step(a); master.step(a); balL.step(a); balR.step(a)

        motion.step(blockSec)
        panner.setPosition(motion.outX, motion.outZ, room)

        val cl = convL; val cr = convR
        val reverbActive = cl != null && cr != null && (wetGain.cur > 0.001 || wetGain.tgt > 0)
        val echoSize = echoBufL.size
        val twoPi = 2 * PI
        val panActive = s.width > 0.01f

        for (i in 0 until n) {
            var l = (inL[i] * preampGain.cur).toFloat()
            var r = (inR[i] * preampGain.cur).toFloat()

            // Isolator Vokal / Bass real-time
            when (isolatorMode) {
                1 -> { // Karaoke: Redam vokal tengah (Mid-Side cancellation), pertahankan bass <180Hz
                    val lowL = isoBassL.process(l); val lowR = isoBassR.process(r)
                    val side = (l - r) * 0.75f
                    l = lowL + side; r = lowR - side
                }
                2 -> { // Acapella: Isolasi vokal tengah (Mid band)
                    val mid = (l + r) * 0.5f
                    val vocal = isoMidL.process(mid) * 1.4f
                    l = vocal; r = vocal
                }
                3 -> { // Bass Isolator: Hanya nada rendah
                    l = isoBassL.process(l) * 1.3f; r = isoBassR.process(r) * 1.3f
                }
            }

            for (b in 0 until 10) { l = eqL[b].process(l); r = eqR[b].process(r) }
            l = bassL.process(l); r = bassR.process(r)
            l = subL.process(l); r = subR.process(r)
            l = excL.process(l); r = excR.process(r)
            l = hpL.process(l); r = hpR.process(r)
            l = lpL.process(l); r = lpR.process(r)

            if (driveK > 0) { l = shape(l); r = shape(r) }

            if (compOn) {
                val det = max(abs(l), abs(r))
                val xdb = if (det < 1e-6f) -120.0 else 20.0 * log10(det.toDouble())
                val target = gainReductionDb(xdb)
                grDb += (target - grDb) * (if (target < grDb) atkCoef else relCoef)
                val g = exp((grDb + compMakeupDb) * 0.11512925).toFloat()
                l *= g; r *= g
            }

            // Echo
            val dl = readDelay(echoBufL, echoW, echoDelay.cur)
            val dr = readDelay(echoBufR, echoW, echoDelay.cur)
            echoBufL[echoW] = l + (echoFb.cur * dl).toFloat(); echoBufR[echoW] = r + (echoFb.cur * dr).toFloat()
            echoW = if (echoW + 1 == echoSize) 0 else echoW + 1
            l += (echoMix.cur * dl).toFloat(); r += (echoMix.cur * dr).toFloat()

            // Pemosisi 8D (jika width = 0% tetap stereo asli)
            val pl: Float; val pr: Float
            if (panActive) {
                panner.process((l + r) * 0.5f)
                pl = panner.outL; pr = panner.outR
            } else {
                pl = l; pr = r
            }

            var sumL = (dryGain.cur * pl).toFloat()
            var sumR = (dryGain.cur * pr).toFloat()
            if (reverbActive) {
                sumL += (wetGain.cur * cl!!.tick(pl)).toFloat()
                sumR += (wetGain.cur * cr!!.tick(pr)).toFloat()
            }

            sumL *= (master.cur * balL.cur).toFloat()
            sumR *= (master.cur * balR.cur).toFloat()

            // Soft Limiter anti-clipping
            if (softLimiterOn) {
                if (abs(sumL) > 0.88f) sumL = (tanh((sumL / 0.88f).toDouble()) * 0.96).toFloat()
                if (abs(sumR) > 0.88f) sumR = (tanh((sumR / 0.88f).toDouble()) * 0.96).toFloat()
            }

            outL[i] = sumL
            outR[i] = sumR
        }

        sinceReport += n
        if (sinceReport >= sampleRate / 30) {
            sinceReport = 0
            val spec = computeSpectrum(outL, outR, n)
            val bassE = ((spec.getOrElse(0) { 0f } + spec.getOrElse(1) { 0f } + spec.getOrElse(2) { 0f }) / 3f).coerceIn(0f, 1f)
            onFrame(PanFrame(motion.normX, motion.normZ, motion.progress.toFloat(), bassE, spec))
        }
    }

    private fun computeSpectrum(l: FloatArray, r: FloatArray, n: Int): List<Float> {
        vizRe.fill(0f); vizIm.fill(0f)
        val count = minOf(n, 128)
        for (i in 0 until count) {
            val hann = (0.5 * (1.0 - kotlin.math.cos(2.0 * PI * i / count))).toFloat()
            vizRe[i] = ((l[i] + r[i]) * 0.5f) * hann
        }
        vizFft.transform(vizRe, vizIm, false)
        val out = ArrayList<Float>(24)
        for (b in 0 until 24) {
            val binStart = 1 + (b * 2)
            val binEnd = minOf(63, binStart + 2)
            var mag = 0f
            for (k in binStart..binEnd) {
                mag += sqrt(vizRe[k] * vizRe[k] + vizIm[k] * vizIm[k])
            }
            val norm = (mag / (binEnd - binStart + 1) / 8f).coerceIn(0f, 1f)
            smoothSpec[b] = smoothSpec[b] * 0.65f + norm * 0.35f
            out.add(smoothSpec[b])
        }
        return out
    }

    companion object {
        private const val ROOM_RADIUS = 2.5f
        private const val KNEE = 30.0
    }
}
