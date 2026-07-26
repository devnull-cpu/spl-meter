package uk.co.cinema.splmeter

import org.jtransforms.fft.DoubleFFT_1D
import uk.co.cinema.splmeter.dsp.Repair
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A-SPADE, the analysis/cosparse Sparse Audio Declipper.
 *
 * Transcribed from the reference MATLAB (`tight_aspade.m` in
 * github.com/andryr/spade-declipping, implementing Kitic/Bertin/Gribonval
 * arXiv:1506.01830), rather than written from a description of it — an earlier
 * attempt from memory left out the dual variable entirely, which is the part
 * that makes it converge rather than oscillate.
 *
 * ```
 * zi = hard_thresh(Axi + ui, k)
 * v  = frsyn(zi - ui);  v(reliable) = yc;  v(clipped+) = max(v, yc);  ...
 * xi = v;  Axi = frana(xi)
 * if norm(Axi - zi) <= eps: stop
 * ui = ui + Axi - zi
 * every r iterations: k = k + s
 * ```
 *
 * The transform is a Parseval-tight redundant DFT: zero-pad by [REDUNDANCY] and
 * scale by 1/sqrt(N). Overcompleteness matters — a critically sampled FFT of a
 * rectangular window leaks badly, and leakage is exactly what destroys the
 * sparsity the method depends on.
 */
object Aspade {

    private const val WINDOW = 4096
    private const val REDUNDANCY = 2
    private const val N = WINDOW * REDUNDANCY

    /** Sparsity step and how often it is relaxed, `s` and `r` in the paper. */
    private const val S = 1
    private const val R = 1
    private const val MAX_ITER = 200
    /** Convergence threshold, relative to the window's norm. */
    private const val REL_EPS = 0.01

    private val fft = DoubleFFT_1D(N.toLong())

    var lastIterations = 0L
        private set
    var lastRuns = 0L
        private set

    fun declip(x: FloatArray, clipLevel: Float = Repair.CLIP_THRESHOLD) {
        lastIterations = 0; lastRuns = 0
        if (x.size < WINDOW) return
        var i = 0
        while (i < x.size) {
            if (abs(x[i]) < clipLevel) { i++; continue }
            val sign = x[i] >= 0f
            var end = i
            while (end + 1 < x.size && abs(x[end + 1]) >= clipLevel &&
                (x[end + 1] >= 0f) == sign) end++
            if (end > i) repairRun(x, i, end, clipLevel)
            i = end + 1
        }
    }

    private fun repairRun(x: FloatArray, start: Int, end: Int, clipLevel: Float) {
        val centre = (start + end) / 2
        val from = (centre - WINDOW / 2).coerceIn(0, x.size - WINDOW)

        val yc = DoubleArray(WINDOW) { x[from + it].toDouble() }
        // masks.Ir / Icp / Icm
        val reliable = BooleanArray(WINDOW) { abs(yc[it]) < clipLevel }
        val positive = BooleanArray(WINDOW) { !reliable[it] && yc[it] > 0 }

        val xi = yc.copyOf()
        var axi = frana(xi)
        val ui = DoubleArray(2 * N)
        var k = S

        var norm = 0.0
        for (v in yc) norm += v * v
        val eps = REL_EPS * sqrt(norm)

        val zi = DoubleArray(2 * N)
        val diff = DoubleArray(2 * N)
        var iter = 1
        while (iter <= MAX_ITER) {
            // zi = hard_thresh(Axi + ui, k)
            for (j in 0 until 2 * N) zi[j] = axi[j] + ui[j]
            hardThreshold(zi, k)

            // v = frsyn(zi - ui), then project onto what is known
            for (j in 0 until 2 * N) diff[j] = zi[j] - ui[j]
            val v = frsyn(diff)
            for (j in 0 until WINDOW) {
                xi[j] = when {
                    reliable[j] -> yc[j]
                    positive[j] -> maxOf(v[j], yc[j])
                    else -> minOf(v[j], yc[j])
                }
            }

            axi = frana(xi)
            var d = 0.0
            for (j in 0 until 2 * N) { val e = axi[j] - zi[j]; d += e * e }
            lastIterations++
            if (sqrt(d) <= eps) break

            for (j in 0 until 2 * N) ui[j] += axi[j] - zi[j]
            iter++
            if (iter % R == 0) k += S
        }
        lastRuns++

        for (j in start..end) x[j] = xi[j - from].toFloat()
    }

    /** Analysis: zero-pad to N, forward DFT, scale for a Parseval-tight frame. */
    private fun frana(x: DoubleArray): DoubleArray {
        val c = DoubleArray(2 * N)
        for (j in x.indices) c[2 * j] = x[j]
        fft.complexForward(c)
        val scale = 1.0 / sqrt(N.toDouble())
        for (j in c.indices) c[j] *= scale
        return c
    }

    /** Synthesis: the adjoint of [frana] — inverse DFT, rescale, truncate. */
    private fun frsyn(c: DoubleArray): DoubleArray {
        val work = c.copyOf()
        fft.complexInverse(work, false)
        val scale = sqrt(N.toDouble()) / N
        return DoubleArray(WINDOW) { work[2 * it] * scale }
    }

    private val mag = DoubleArray(N)
    private val scratch = DoubleArray(N)

    /**
     * Keep the k largest coefficients by magnitude, zero the rest.
     *
     * Selection rather than a sort: this runs every iteration of every clipped
     * run, and sorting 8192 magnitudes to find one threshold dominated the whole
     * algorithm's cost.
     */
    private fun hardThreshold(c: DoubleArray, k: Int) {
        if (k >= N) return
        for (j in 0 until N) {
            val re = c[2 * j]; val im = c[2 * j + 1]
            mag[j] = re * re + im * im
        }
        System.arraycopy(mag, 0, scratch, 0, N)
        val cut = select(scratch, N - k)
        for (j in 0 until N) {
            if (mag[j] < cut) { c[2 * j] = 0.0; c[2 * j + 1] = 0.0 }
        }
    }

    /** Quickselect: value that would be at [target] if the array were sorted. */
    private fun select(a: DoubleArray, target: Int): Double {
        var lo = 0
        var hi = a.size - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++; j--
                }
            }
            if (target <= j) hi = j else if (target >= i) lo = i else return a[target]
        }
        return a[target]
    }
}
