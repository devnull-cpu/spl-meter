package uk.co.cinema.splmeter.dsp

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10

/**
 * Everything computed for one capture window.
 *
 * All levels are **uncalibrated** — dB relative to digital full scale. The SPL
 * offset and the frequency-response correction are applied at display and
 * report time, which is what makes recalibrating an old recording possible.
 */
class WindowResult(
    val tSec: Float,
    val laeq: Float,
    val lceq: Float,
    val lzeq: Float,
    val peak: Float,
    /** Same three levels with the frequency-response curve applied — display only. */
    val laeqCal: Float,
    val lceqCal: Float,
    val lzeqCal: Float,
    val lasMax: Float,
    val lasMin: Float,
    val lcsMax: Float,
    val lcsMin: Float,
    /** dB per 1 Hz bin, [Bands.SUB_LOW_HZ]..[Bands.SUB_HIGH_HZ]. */
    val sub: FloatArray,
    /** dB per stored 1/3 octave band, [Bands.THIRD_OCTAVE_STORED]. */
    val third: FloatArray,
    val clipRuns: Int,
    val clippedSamples: Int,
    val clicksFixed: Int
)

/**
 * Turns a repaired capture buffer into a [WindowResult].
 *
 * One full-window FFT gives the stored spectrum and the broadband Leq values;
 * a run of short FFTs gives properly exponentially-averaged Fast time-weighted
 * levels, which is what LASmax/LASmin actually mean.
 */
class WindowAnalyzer(
    private val sampleRate: Int,
    private val windowSamples: Int,
    /**
     * Optional per-bin power multipliers for the mic's frequency response.
     * Only used for the live display — what gets stored stays uncorrected so a
     * recording can be re-analysed with a different cal file later.
     */
    private val correction: DoubleArray? = null,
    /** Lower edge of the weighted sums. See [Weighting.MIN_HZ]. */
    private val minHz: Double = Weighting.MIN_HZ,
    /** Subtract the window mean before analysis. */
    private val removeDc: Boolean = true
) {
    private val nBins = windowSamples / 2 + 1
    private val binHz = sampleRate.toDouble() / windowSamples

    private val fft = DoubleFFT_1D(windowSamples.toLong())
    private val work = DoubleArray(windowSamples)
    private val binPower = DoubleArray(nBins)

    private val aCurve = Weighting.powerCurve(windowSamples, sampleRate, Weighting.A, minHz)
    private val cCurve = Weighting.powerCurve(windowSamples, sampleRate, Weighting.C, minHz)
    // Z is flat but still band-limited: without this the sum runs down to DC.
    private val zCurve = Weighting.powerCurve(windowSamples, sampleRate, Weighting.Z, minHz)

    private val subStart = IntArray(Bands.SUB_COUNT)
    private val subEnd = IntArray(Bands.SUB_COUNT)
    private val thirdStart = IntArray(Bands.THIRD_OCTAVE_STORED.size)
    private val thirdEnd = IntArray(Bands.THIRD_OCTAVE_STORED.size)

    // --- Fast (125 ms) time weighting ------------------------------------
    private val blockSamples = sampleRate / 20 // 50 ms
    private val blockFft = DoubleFFT_1D(blockSamples.toLong())
    private val blockWork = DoubleArray(blockSamples)
    private val blockACurve = Weighting.powerCurve(blockSamples, sampleRate, Weighting.A, minHz)
    private val blockCCurve = Weighting.powerCurve(blockSamples, sampleRate, Weighting.C, minHz)
    private val fastAlpha = 1.0 - exp(-0.05 / 0.125)
    private var fastA = -1.0
    private var fastC = -1.0

    init {
        for (i in 0 until Bands.SUB_COUNT) {
            val f = (Bands.SUB_LOW_HZ + i).toDouble()
            subStart[i] = binIndexAtOrAbove(f - 0.5)
            subEnd[i] = binIndexAtOrAbove(f + 0.5) - 1
        }
        for (i in Bands.THIRD_OCTAVE_STORED.indices) {
            val c = Bands.THIRD_OCTAVE_STORED[i]
            thirdStart[i] = binIndexAtOrAbove(Bands.lowEdge(c))
            thirdEnd[i] = binIndexAtOrAbove(Bands.highEdge(c)) - 1
        }
    }

    private fun binIndexAtOrAbove(hz: Double): Int =
        ceil(hz / binHz - 1e-9).toInt().coerceIn(0, nBins)

    /**
     * @param x repaired samples, normalised to +/-1.0 full scale. Length must be
     *          [windowSamples]. Not modified.
     */
    fun analyze(x: FloatArray, tSec: Float, stats: Repair.Stats): WindowResult {
        require(x.size == windowSamples) { "expected $windowSamples samples, got ${x.size}" }

        val (lasMax, lasMin, lcsMax, lcsMin) = fastLevels(x)

        // A DC offset is not sound. Left in it inflates the peak reading, biases
        // clip detection towards one polarity, and lands in the Z sum.
        var mean = 0.0
        if (removeDc) {
            for (v in x) mean += v
            mean /= windowSamples
        }
        for (i in 0 until windowSamples) work[i] = x[i] - mean
        fft.realForward(work)

        // JTransforms real packing: work[0] = Re[0], work[1] = Re[N/2],
        // work[2k] = Re[k], work[2k+1] = Im[k] for k = 1..N/2-1.
        val n2 = windowSamples.toDouble() * windowSamples
        binPower[0] = work[0] * work[0] / n2
        binPower[nBins - 1] = work[1] * work[1] / n2
        for (k in 1 until nBins - 1) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            binPower[k] = 2.0 * (re * re + im * im) / n2
        }

        var zPower = 0.0
        var aPower = 0.0
        var cPower = 0.0
        var zCal = 0.0
        var aCal = 0.0
        var cCal = 0.0
        for (k in 0 until nBins) {
            val p = binPower[k]
            zPower += p * zCurve[k]
            aPower += p * aCurve[k]
            cPower += p * cCurve[k]
            if (correction != null) {
                val pc = p * correction[k]
                zCal += pc * zCurve[k]
                aCal += pc * aCurve[k]
                cCal += pc * cCurve[k]
            }
        }

        val sub = FloatArray(Bands.SUB_COUNT) { i -> bandDb(subStart[i], subEnd[i]) }
        val third = FloatArray(Bands.THIRD_OCTAVE_STORED.size) { i -> bandDb(thirdStart[i], thirdEnd[i]) }

        var peakAmp = 0f
        for (v in x) { val a = abs(v - mean); if (a > peakAmp) peakAmp = a.toFloat() }

        return WindowResult(
            tSec = tSec,
            laeq = toDb(aPower),
            lceq = toDb(cPower),
            lzeq = toDb(zPower),
            laeqCal = if (correction != null) toDb(aCal) else toDb(aPower),
            lceqCal = if (correction != null) toDb(cCal) else toDb(cPower),
            lzeqCal = if (correction != null) toDb(zCal) else toDb(zPower),
            peak = if (peakAmp > 0f) (20.0 * log10(peakAmp.toDouble())).toFloat() else -200f,
            lasMax = lasMax, lasMin = lasMin, lcsMax = lcsMax, lcsMin = lcsMin,
            sub = sub, third = third,
            clipRuns = stats.clipRuns,
            clippedSamples = stats.clippedSamples,
            clicksFixed = stats.clicksFixed
        )
    }

    private data class Fast(val aMax: Float, val aMin: Float, val cMax: Float, val cMin: Float)



    /**
     * Exponentially averaged A/C levels with a 125 ms time constant, tracked
     * across 50 ms sub-blocks. State carries over between windows so the
     * smoothing is continuous for the whole session.
     */
    private fun fastLevels(x: FloatArray): Fast {
        var aMax = Float.NaN; var aMin = Float.NaN
        var cMax = Float.NaN; var cMin = Float.NaN
        val bins = blockSamples / 2 + 1
        val n2 = blockSamples.toDouble() * blockSamples

        var offset = 0
        while (offset + blockSamples <= x.size) {
            for (i in 0 until blockSamples) blockWork[i] = x[offset + i].toDouble()
            blockFft.realForward(blockWork)

            var aP = blockWork[0] * blockWork[0] / n2 * blockACurve[0]
            var cP = blockWork[0] * blockWork[0] / n2 * blockCCurve[0]
            val nyq = blockWork[1] * blockWork[1] / n2
            aP += nyq * blockACurve[bins - 1]
            cP += nyq * blockCCurve[bins - 1]
            for (k in 1 until bins - 1) {
                val re = blockWork[2 * k]
                val im = blockWork[2 * k + 1]
                val p = 2.0 * (re * re + im * im) / n2
                aP += p * blockACurve[k]
                cP += p * blockCCurve[k]
            }

            fastA = if (fastA < 0.0) aP else fastA + fastAlpha * (aP - fastA)
            fastC = if (fastC < 0.0) cP else fastC + fastAlpha * (cP - fastC)

            val aDb = toDb(fastA)
            val cDb = toDb(fastC)
            if (aDb > SILENCE_FLOOR_DB) {
                if (aMax.isNaN() || aDb > aMax) aMax = aDb
                if (aMin.isNaN() || aDb < aMin) aMin = aDb
            }
            if (cDb > SILENCE_FLOOR_DB) {
                if (cMax.isNaN() || cDb > cMax) cMax = cDb
                if (cMin.isNaN() || cDb < cMin) cMin = cDb
            }

            offset += blockSamples
        }
        // NaN means no block in this window carried a usable level.
        return Fast(aMax, aMin, cMax, cMin)
    }

    private fun bandDb(start: Int, end: Int): Float {
        if (start > end || start >= nBins) return -200f
        var p = 0.0
        for (k in start..minOf(end, nBins - 1)) p += binPower[k]
        return toDb(p)
    }

    companion object {
        /**
         * Below this, a block is not a measurement.
         *
         * A running minimum is permanently captured by its lowest sample, so a
         * single block of digital silence — before the capture stream is really
         * flowing, say — pins LASmin to the floor for the whole session and it
         * can never recover. Genuinely quiet audio must still count, or the
         * minimum is biased upwards, but a phone microphone always has self
         * noise: a level down here means no data rather than a silent room.
         */
        const val SILENCE_FLOOR_DB = -120.0f

        fun toDb(power: Double): Float =
            if (power <= 1e-20) -200f else (10.0 * log10(power)).toFloat()
    }
}
