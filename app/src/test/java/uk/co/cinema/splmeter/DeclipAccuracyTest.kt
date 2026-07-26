package uk.co.cinema.splmeter

import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.cinema.splmeter.dsp.Repair
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures declipping accuracy against samples we know are true.
 *
 * Comparing a reconstruction against another declipper only shows the two
 * agree. Here the ground truth is exact: take real programme material that has
 * headroom, amplify it until it clips, and compare what is recovered against
 * the samples that were there before clipping.
 *
 * Errors are reported per clipped run rather than for the loudest peak alone.
 * A single global peak is one sample and says nothing about the distribution —
 * it is whichever run happened to be reconstructed best or worst.
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*DeclipAccuracy*" \
 *   -Dwav=reference.wav -Dat=2560 -Dspan=30
 * ```
 */
class DeclipAccuracyTest {

    private val boosts by lazy {
        System.getProperty("boosts")?.split(",")?.map { it.trim().toDouble() }?.toDoubleArray()
            ?: doubleArrayOf(9.0, 12.0, 16.0, 20.0, 26.0, 32.0)
    }

    @Test
    fun `measure declipping error per clipped run`() {
        val path = System.getProperty("wav")
        assumeTrue("set -Dwav=<a recording with headroom> to run", path != null)
        val file = File(path!!)
        assumeTrue("no such file: $path", file.exists())

        val at = System.getProperty("at")?.toDouble() ?: 0.0
        val span = System.getProperty("span")?.toDouble() ?: 30.0
        val region = TestAudio.readRegion(file, at, span)
        val truth = region.samples
        assumeTrue("nothing read", truth.size > 1000)

        var truePeak = 0f
        for (v in truth) if (abs(v) > truePeak) truePeak = abs(v)

        println("=".repeat(92))
        println("Source : ${file.name}   ${fmt(at)}..${fmt(at + span)}   " +
            "${region.rate} Hz, ${truth.size} samples, peak ${"%.2f".format(Locale.UK, 20 * log10(truePeak.toDouble()))} dBFS")
        println("Ground truth is the signal before clipping, so every error is absolute.")
        println("Per-run peak error: reconstructed run peak minus true run peak, in dB.")
        println("=".repeat(92))

        val methods: List<Pair<String, (FloatArray) -> Unit>> = listOf(
            "left clipped" to { _ -> },
            "current" to { x -> Repair.declip(x) },
            "A-SPADE" to { x -> Aspade.declip(x) }
        )

        for (boost in boosts) {
            val gain = Math.pow(10.0, boost / 20.0).toFloat()
            val scaled = FloatArray(truth.size) { truth[it] * gain }
            val clipped = FloatArray(truth.size) { scaled[it].coerceIn(-1f, 1f) }
            val runs = findRuns(clipped)
            if (runs.isEmpty()) {
                println("\n  +%.0f dB — no clipping".format(Locale.UK, boost))
                continue
            }
            val clippedSamples = runs.sumOf { it.second - it.first + 1 }

            println()
            println("  +%.0f dB boost · %.3f%% of samples clipped · %d runs · median run %d samples"
                .format(Locale.UK, boost, 100.0 * clippedSamples / truth.size, runs.size,
                    runs.map { it.second - it.first + 1 }.sorted()[runs.size / 2]))
            println("    %-14s %8s %8s %8s %8s %8s %9s %8s".format(
                Locale.UK, "method", "mean", "median", "p90", "worst", "best", "globalpk", "ms"))

            for ((name, apply) in methods) {
                val test = clipped.copyOf()
                val t0 = System.nanoTime()
                apply(test)
                val ms = (System.nanoTime() - t0) / 1_000_000

                val errors = runs.map { (s, e) ->
                    db(peak(test, s, e)) - db(peak(scaled, s, e))
                }.sorted()
                val globalPk = db(peak(test, 0, test.size - 1)) - db(peak(scaled, 0, scaled.size - 1))

                println("    %-14s %+8.2f %+8.2f %+8.2f %+8.2f %+8.2f %+9.2f %8d".format(
                    Locale.UK, name,
                    errors.average(),
                    errors[errors.size / 2],
                    errors[(errors.size * 10) / 100],   // p90 = 10th percentile of a negative error
                    errors.first(),
                    errors.last(),
                    globalPk, ms
                ))
            }
        }
        println()
        println("  worst/best are the most and least negative run errors. p90 is the level")
        println("  90% of runs do better than. ms is wall-clock for the whole region.")
        println("=".repeat(92))
    }

    private fun findRuns(x: FloatArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < x.size) {
            if (abs(x[i]) < Repair.CLIP_THRESHOLD) { i++; continue }
            val sign = x[i] >= 0f
            var end = i
            while (end + 1 < x.size && abs(x[end + 1]) >= Repair.CLIP_THRESHOLD &&
                (x[end + 1] >= 0f) == sign) end++
            if (end > i) out.add(i to end)
            i = end + 1
        }
        return out
    }

    private fun peak(x: FloatArray, from: Int, to: Int): Double {
        var p = 0f
        for (i in from..min(to, x.size - 1)) if (abs(x[i]) > p) p = abs(x[i])
        return p.toDouble()
    }

    private fun db(v: Double) = if (v <= 0.0) -200.0 else 20.0 * log10(v)

    private fun fmt(s: Double): String {
        val t = s.toInt()
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }
}

