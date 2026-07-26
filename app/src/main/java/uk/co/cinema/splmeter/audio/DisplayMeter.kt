package uk.co.cinema.splmeter.audio

import org.jtransforms.fft.DoubleFFT_1D
import uk.co.cinema.splmeter.dsp.Weighting
import uk.co.cinema.splmeter.dsp.WindowAnalyzer

/**
 * Live level meter for the display only.
 *
 * Deliberately separate from the analysis window. The stored window is a
 * measurement choice — 2 s buys 0.5 Hz of FFT resolution, which is what makes
 * the sub spectrum worth keeping — but it is far too slow to watch. A real SPL
 * meter moves several times a second.
 *
 * So this runs on the capture thread over short blocks with proper Fast (125 ms)
 * exponential weighting, and touches nothing that gets written to disk. The
 * numbers on screen and the numbers in the log come from two independent paths
 * over the same audio: this one is unrepaired and responsive, that one is
 * declicked, declipped and exact.
 */
class DisplayMeter(
    private val sampleRate: Int,
    private val minHz: Double = Weighting.MIN_HZ,
    private val removeDc: Boolean = true,
    /**
     * Per-bin power multipliers for the mic's frequency response.
     *
     * The display has to apply the same correction the window path applies, or
     * the two disagree — and they disagree most on A weighting, because A is set
     * by the 1-5 kHz region where a phone's response error is largest, while C
     * is set by the bass where the correction is near zero. Leaving this out
     * showed A several dB high while C looked right.
     */
    private val correction: DoubleArray? = null
) {

    private val blockSamples = sampleRate / 40 // 25 ms
    private val bins = blockSamples / 2 + 1
    private val fft = DoubleFFT_1D(blockSamples.toLong())
    private val work = DoubleArray(blockSamples)
    private val aCurve = Weighting.powerCurve(blockSamples, sampleRate, Weighting.A, minHz)
    private val cCurve = Weighting.powerCurve(blockSamples, sampleRate, Weighting.C, minHz)
    private val zCurve = Weighting.powerCurve(blockSamples, sampleRate, Weighting.Z, minHz)

    private val alpha = 1.0 - Math.exp(-(blockSamples.toDouble() / sampleRate) / 0.125)
    private var fastA = -1.0
    private var fastC = -1.0
    private var fastZ = -1.0
    private var filled = 0

    /** Latest Fast-weighted levels, dBFS. */
    var aDb = Float.NaN
        private set
    var cDb = Float.NaN
        private set
    var zDb = Float.NaN
        private set

    fun reset() {
        fastA = -1.0; fastC = -1.0; fastZ = -1.0
        filled = 0
        aDb = Float.NaN; cDb = Float.NaN; zDb = Float.NaN
    }

    /**
     * @return true if at least one complete block was processed, i.e. the levels
     *         have moved on.
     */
    fun feed(chunk: ShortArray, count: Int): Boolean {
        var updated = false
        var i = 0
        while (i < count) {
            val take = minOf(count - i, blockSamples - filled)
            for (j in 0 until take) work[filled + j] = chunk[i + j] / 32768.0
            filled += take
            i += take
            if (filled == blockSamples) {
                process()
                filled = 0
                updated = true
            }
        }
        return updated
    }

    private fun process() {
        if (removeDc) {
            var mean = 0.0
            for (v in work) mean += v
            mean /= blockSamples
            for (i in work.indices) work[i] -= mean
        }
        fft.realForward(work)
        val n2 = blockSamples.toDouble() * blockSamples

        val dc = work[0] * work[0] / n2 * (correction?.get(0) ?: 1.0)
        var zP = dc * zCurve[0]
        var aP = dc * aCurve[0]
        var cP = dc * cCurve[0]
        val nyq = work[1] * work[1] / n2 * (correction?.get(bins - 1) ?: 1.0)
        zP += nyq * zCurve[bins - 1]
        aP += nyq * aCurve[bins - 1]
        cP += nyq * cCurve[bins - 1]
        for (k in 1 until bins - 1) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            var p = 2.0 * (re * re + im * im) / n2
            if (correction != null) p *= correction[k]
            zP += p * zCurve[k]
            aP += p * aCurve[k]
            cP += p * cCurve[k]
        }

        fastA = if (fastA < 0.0) aP else fastA + alpha * (aP - fastA)
        fastC = if (fastC < 0.0) cP else fastC + alpha * (cP - fastC)
        fastZ = if (fastZ < 0.0) zP else fastZ + alpha * (zP - fastZ)

        aDb = WindowAnalyzer.toDb(fastA)
        cDb = WindowAnalyzer.toDb(fastC)
        zDb = WindowAnalyzer.toDb(fastZ)
    }
}
