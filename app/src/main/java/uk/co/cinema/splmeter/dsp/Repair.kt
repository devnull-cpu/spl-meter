package uk.co.cinema.splmeter.dsp

import kotlin.math.abs
import kotlin.math.min

/**
 * In-place waveform repair applied to each capture buffer before analysis.
 *
 * Order matters: declick first (isolated spikes would otherwise look like the
 * start of a clipped run and poison the polynomial fit), then declip.
 */
object Repair {

    /** Result of a repair pass over one buffer. */
    data class Stats(val clicksFixed: Int, val clipRuns: Int, val clippedSamples: Int)

    /** Samples at or beyond this fraction of full scale count as clipped. */
    const val CLIP_THRESHOLD = 0.992f

    private const val MAX_CLICK_RUN = 5
    private const val CLICK_SIGMA = 20.0f
    private const val CLICK_BLOCK = 256
    /** Residuals below this are never worth chasing — roughly -54 dBFS. */
    private const val MIN_CLICK_RESIDUAL = 0.002f
    private const val MAX_CLIP_RUN = 4096

    /**
     * Numerical backstop on a reconstructed sample: 12 dB above full scale.
     *
     * Deliberately loose. The real constraint is [sineAmplitude], which bounds
     * each run by the geometry of its own clipped arc; this only exists to stop
     * a runaway fit, and a runaway is orders of magnitude out, not decibels.
     *
     * It was 2.0 (+6 dB) when the geometric bound did not yet exist, which put
     * the ceiling at 132 dB for a phone clipping at 126 — inside the range of
     * real peaks in loud environments, so it was truncating measurements rather
     * than catching errors. A reconstruction this far over full scale is not
     * trustworthy anyway: the mic's diaphragm is distorting, not just the ADC
     * saturating. The report says so rather than the repair pretending otherwise.
     */
    private const val MAX_RECONSTRUCTION = 4.0f

    /**
     * Polynomial order and how much unclipped audio to fit against.
     *
     * Both have to scale with the width of the gap. What matters is not how many
     * samples are missing but how much of a cycle they represent: a peak 4 dB
     * over full scale removes about 90 degrees of arc, and a cubic anchored on a
     * short stub either side is far too stiff to carry a sine apex across that —
     * it flattens, and the reconstructed peak comes in low.
     */
    private const val FIT_ORDER = 5
    private const val MIN_FIT_POINTS = 96
    private const val MAX_FIT_POINTS = 512

    /**
     * How far past the sinusoidal estimate a polynomial fit is allowed to go
     * before it is treated as ringing rather than reconstruction. Real peaks sit
     * a little above the pure-tone figure because there is always some higher
     * frequency content riding on the fundamental, so this is not 1.0.
     */
    private const val SINE_MARGIN = 1.4
    private const val MAX_HALF_PERIOD = 4800

    fun repair(x: FloatArray, doDeclick: Boolean = true, doDeclip: Boolean = true): Stats {
        val clicks = if (doDeclick) declick(x) else 0
        // Count clipped samples even when not repairing them, so the report can
        // still say the measurement was clipped.
        val (runs, samples) = if (doDeclip) declip(x) else countClipped(x)
        return Stats(clicks, runs, samples)
    }

    /** Clip statistics without altering the signal. */
    fun countClipped(x: FloatArray): Pair<Int, Int> {
        var runs = 0
        var clipped = 0
        var i = 0
        while (i < x.size) {
            if (abs(x[i]) < CLIP_THRESHOLD) { i++; continue }
            val sign = x[i] >= 0f
            var end = i
            while (end + 1 < x.size && abs(x[end + 1]) >= CLIP_THRESHOLD && (x[end + 1] >= 0f) == sign) end++
            clipped += end - i + 1
            runs++
            i = end + 1
        }
        return runs to clipped
    }

    /**
     * Replace isolated outliers (runs of 1..5 samples that jump far away from
     * what their neighbours predict) with a linear interpolation across the run.
     *
     * The threshold is a robust estimate of how big that prediction error
     * normally is *locally* — the median residual over a short block around the
     * sample.
     *
     * Making it local rather than whole-buffer matters a lot. The midpoint
     * predictor is a lowpass, so genuine content near Nyquist produces large
     * residuals of its own; measured against a whole-window median, a loud
     * cymbal crash reads as a solid second of clicks and gets smeared. Against
     * its own block's median it raises its own bar and is left alone, while a
     * spike in an otherwise quiet block still stands out by a mile.
     */
    fun declick(x: FloatArray): Int {
        if (x.size < 32) return 0
        val scratch = FloatArray(CLICK_BLOCK)
        var fixed = 0
        var blockStart = 1

        while (blockStart < x.size - 1) {
            val blockEnd = min(blockStart + CLICK_BLOCK, x.size - 1)
            var n = 0
            for (i in blockStart until blockEnd) {
                scratch[n++] = abs(x[i] - 0.5f * (x[i - 1] + x[i + 1]))
            }
            if (n == 0) break
            val sorted = scratch.copyOf(n)
            sorted.sort()
            val limit = maxOf(CLICK_SIGMA * sorted[n / 2], MIN_CLICK_RESIDUAL)

            var i = blockStart
            while (i < blockEnd) {
                if (abs(x[i] - 0.5f * (x[i - 1] + x[i + 1])) <= limit) {
                    i++
                    continue
                }
                // Grow the run while samples keep deviating, up to MAX_CLICK_RUN.
                var end = i
                while (end + 1 < x.size - 1 && end - i + 1 < MAX_CLICK_RUN) {
                    val p = 0.5f * (x[end] + x[end + 2])
                    if (abs(x[end + 1] - p) > limit) end++ else break
                }
                // A run that hits the length cap is not a click — it is content.
                if (end - i + 1 < MAX_CLICK_RUN) {
                    val before = x[i - 1]
                    val after = x[min(end + 1, x.size - 1)]
                    val span = end - i + 2
                    for (j in i..end) {
                        val t = (j - i + 1).toFloat() / span
                        x[j] = before + (after - before) * t
                    }
                    fixed += end - i + 1
                }
                i = end + 2
            }
            blockStart = blockEnd
        }
        return fixed
    }

    /**
     * Reconstruct flat-topped clipped regions by least-squares fitting a cubic
     * through the unclipped samples either side and evaluating it across the gap.
     *
     * At 48 kHz a 35 Hz cycle is ~1370 samples, so even a badly clipped sub peak
     * still has hundreds of good samples to fit against. The reconstruction is
     * only accepted if it moves the peak outwards — a fit that would pull the
     * sample back below full scale is rejected in favour of the clipped value.
     */
    fun declip(x: FloatArray): Pair<Int, Int> {
        var runs = 0
        var clipped = 0
        var i = 0
        while (i < x.size) {
            if (abs(x[i]) < CLIP_THRESHOLD) {
                i++
                continue
            }
            val sign = if (x[i] >= 0f) 1f else -1f
            var end = i
            while (end + 1 < x.size && abs(x[end + 1]) >= CLIP_THRESHOLD &&
                (x[end + 1] >= 0f) == (sign > 0f)
            ) end++

            val runLen = end - i + 1
            clipped += runLen
            runs++

            // Single-sample "clips" are almost always just a loud peak that
            // happens to touch full scale — leave them alone.
            if (runLen in 2..MAX_CLIP_RUN) {
                fitAcross(x, i, end, sign)
            }
            i = end + 1
        }
        return runs to clipped
    }

    private fun fitAcross(x: FloatArray, start: Int, end: Int, sign: Float) {
        val n = x.size
        val runLength = end - start + 1
        // Give the fit a lever arm proportional to the gap it has to span.
        val fitPoints = (runLength * 2).coerceIn(MIN_FIT_POINTS, MAX_FIT_POINTS)

        val xs = DoubleArray(2 * fitPoints)
        val ys = DoubleArray(2 * fitPoints)
        var count = 0
        val centre = (start + end) / 2.0

        // Only the contiguous unclipped stretch either side. Walking past the
        // next clipped peak to fill a quota would drag samples from a
        // neighbouring cycle into the fit, and no low-order polynomial tracks a
        // sine across more than a cycle — the fit degrades and gets rejected.
        var j = start - 1
        var taken = 0
        while (j >= 0 && taken < fitPoints && abs(x[j]) < CLIP_THRESHOLD) {
            xs[count] = j - centre
            ys[count] = x[j].toDouble()
            count++
            taken++
            j--
        }
        j = end + 1
        taken = 0
        while (j < n && taken < fitPoints && abs(x[j]) < CLIP_THRESHOLD) {
            xs[count] = j - centre
            ys[count] = x[j].toDouble()
            count++
            taken++
            j++
        }
        if (count < 12) return

        val coeffs = leastSquaresPolynomial(xs, ys, count, FIT_ORDER) ?: return

        val fitted = FloatArray(end - start + 1)
        var fitPeak = 0.0
        for (k in start..end) {
            val t = k - centre
            var acc = 0.0
            var power = 1.0
            for (c in coeffs.indices) {
                acc += coeffs[c] * power
                power *= t
            }
            if (!acc.isFinite()) return
            fitted[k - start] = acc.toFloat()
            if (abs(acc) > fitPeak) fitPeak = abs(acc)
        }

        // Cross-check the fit against the geometry of the clipped arc, and fall
        // back to that geometry when the two disagree. See [sineAmplitude].
        val geometric = sineAmplitude(x, start, end, sign)
        val ceiling = if (geometric > 0.0) minOf(SINE_MARGIN * geometric, MAX_RECONSTRUCTION.toDouble())
        else MAX_RECONSTRUCTION.toDouble()

        if (fitPeak > ceiling) {
            if (geometric <= 0.0 || geometric > MAX_RECONSTRUCTION) return
            fillHalfSine(x, start, end, sign, geometric)
            return
        }

        for (k in start..end) {
            val v = fitted[k - start]
            // Only accept a reconstruction that pushes the peak further out.
            if (sign > 0f && v > x[k]) x[k] = v
            if (sign < 0f && v < x[k]) x[k] = v
        }
    }

    /**
     * Amplitude implied by how much of the cycle the clipping removed.
     *
     * For a half cycle of length H clipped at level c over a run of L samples,
     * the waveform crosses the clipping level at pi*(H-L)/(2H) radians, so
     *
     *     A = c / sin(pi * (H - L) / (2 * H))
     *
     * H comes from the zero crossings bracketing the run. This is the strongest
     * constraint available without knowing the signal: a peak clipped for a
     * quarter of its cycle simply cannot have been 10 dB over full scale. It
     * only holds while one frequency dominates the peak — which for a low
     * frequency transient is exactly the case, and is where clipping happens.
     *
     * @return 0.0 if no usable half cycle could be measured
     */
    private fun sineAmplitude(x: FloatArray, start: Int, end: Int, sign: Float): Double {
        var left = start - 1
        while (left >= 0 && start - left < MAX_HALF_PERIOD && sign * x[left] > 0f) left--
        var right = end + 1
        while (right < x.size && right - end < MAX_HALF_PERIOD && sign * x[right] > 0f) right++
        if (left < 0 || right >= x.size) return 0.0

        val halfPeriod = (right - left).toDouble()
        val runLength = (end - start + 1).toDouble()
        if (halfPeriod <= runLength) return 0.0

        val phase = Math.PI * (halfPeriod - runLength) / (2.0 * halfPeriod)
        val s = kotlin.math.sin(phase)
        if (s <= 1e-6) return 0.0
        return CLIP_THRESHOLD / s
    }

    /** Replaces the run with the peak of a half sine of the measured geometry. */
    private fun fillHalfSine(x: FloatArray, start: Int, end: Int, sign: Float, amplitude: Double) {
        var left = start - 1
        while (left >= 0 && start - left < MAX_HALF_PERIOD && sign * x[left] > 0f) left--
        var right = end + 1
        while (right < x.size && right - end < MAX_HALF_PERIOD && sign * x[right] > 0f) right++
        val halfPeriod = (right - left).toDouble()
        if (halfPeriod <= 0.0) return

        for (k in start..end) {
            val v = (sign * amplitude * kotlin.math.sin(Math.PI * (k - left) / halfPeriod)).toFloat()
            if (sign > 0f && v > x[k]) x[k] = v
            if (sign < 0f && v < x[k]) x[k] = v
        }
    }

    /** Normal-equation solve for y = c0 + c1 t + ... + c_order t^order. */
    private fun leastSquaresPolynomial(
        xs: DoubleArray,
        ys: DoubleArray,
        count: Int,
        order: Int
    ): DoubleArray? {
        val terms = order + 1
        if (count < terms * 2) return null

        // Scale t to +/-1 so the higher powers stay conditioned.
        var maxT = 1e-9
        for (i in 0 until count) if (abs(xs[i]) > maxT) maxT = abs(xs[i])

        val a = Array(terms) { DoubleArray(terms + 1) }
        val p = DoubleArray(terms)
        for (i in 0 until count) {
            val t = xs[i] / maxT
            var power = 1.0
            for (k in 0 until terms) { p[k] = power; power *= t }
            for (r in 0 until terms) {
                for (c in 0 until terms) a[r][c] += p[r] * p[c]
                a[r][terms] += p[r] * ys[i]
            }
        }
        val c = gaussianSolve(a) ?: return null
        // Undo the scaling: the coefficient of t^k picks up 1/maxT^k.
        var scale = 1.0
        return DoubleArray(terms) { k ->
            if (k > 0) scale *= maxT
            c[k] / scale
        }
    }

    private fun gaussianSolve(a: Array<DoubleArray>): DoubleArray? {
        val n = a.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

}
