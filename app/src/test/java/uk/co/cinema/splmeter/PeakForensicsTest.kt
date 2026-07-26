package uk.co.cinema.splmeter

import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.cinema.splmeter.dsp.Repair
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pulls one moment out of a clipped recording and, optionally, out of a
 * separately repaired version of the same recording, and compares the
 * reconstruction sample by sample.
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*PeakForensics*" \
 *   -Dwav=recording.wav -Dref=reference.wav -DrefGain=10 \
 *   -Dat=2605 -Dspan=10 -Doffset=126.06
 * ```
 */
class PeakForensicsTest {

    @Test
    fun `compare a clipped peak against a reference repair`() {
        val wavPath = System.getProperty("wav")
        assumeTrue("set -Dwav= to run", wavPath != null)

        val at = System.getProperty("at")?.toDouble() ?: 0.0
        val span = System.getProperty("span")?.toDouble() ?: 10.0
        val offset = System.getProperty("offset")?.toDouble() ?: 0.0
        val refPath = System.getProperty("ref")
        val refGain = System.getProperty("refGain")?.toDouble() ?: 0.0

        val original = readRegion(File(wavPath!!), at, span)
        val rate = original.rate
        val clipped = original.samples
        val ours = clipped.copyOf()
        Repair.declick(ours)
        val (runs, clippedSamples) = Repair.declip(ours)

        val refScale = 10.0.pow(refGain / 20.0).toFloat()
        val reference = refPath?.let { readRegion(File(it), at, span).samples.map { v -> v * refScale }.toFloatArray() }

        println("=".repeat(74))
        println("Region    : ${fmtTime(at)} .. ${fmtTime(at + span)}  ($rate Hz)")
        println("Offset    : ${"%+.2f".format(Locale.UK, offset)} dB")
        println("Clip runs : $runs covering $clippedSamples samples")
        if (reference != null) println("Reference : ${File(refPath).name} scaled ${"%+.1f".format(Locale.UK, refGain)} dB")
        println("=".repeat(74))

        // Every clipped run in the region, longest first.
        val runList = findRuns(clipped)
        println("\n--- clipped runs, longest first ------------------------------")
        println("  %8s %6s %8s %9s %9s %9s %7s".format(
            Locale.UK, "at", "len", "cycleHz", "clipped", "ours", "ref", "ours-ref"))
        runList.sortedByDescending { it.length }.take(12).forEach { r ->
            val mid = (r.start + r.end) / 2
            val hz = dominantHz(clipped, maxOf(0, mid - 4096), 8192, rate)
            val oursPeak = peakIn(ours, r.start, r.end)
            val refPeak = reference?.let { peakIn(it, r.start, r.end) }
            println("  %8s %6d %8.1f %9.3f %9.3f %9s %7s".format(
                Locale.UK,
                fmtTime(at + r.start.toDouble() / rate),
                r.length,
                hz,
                abs(peakIn(clipped, r.start, r.end)),
                abs(oursPeak),
                refPeak?.let { "%.3f".format(Locale.UK, abs(it)) } ?: "-",
                refPeak?.let { "%+.2fdB".format(Locale.UK, 20.0 * log10(abs(oursPeak) / abs(it))) } ?: "-"
            ))
        }

        // The single loudest instant in the region.
        val biggest = runList.maxByOrNull { abs(peakIn(ours, it.start, it.end)) }
        if (biggest != null) {
            val mid = (biggest.start + biggest.end) / 2
            val hz = dominantHz(clipped, maxOf(0, mid - 8192), 16384, rate)
            println("\n--- loudest reconstructed peak -------------------------------")
            println("  time            : ${fmtTime(at + biggest.start.toDouble() / rate)}")
            println("  run length      : ${biggest.length} samples (${"%.2f".format(Locale.UK, biggest.length * 1000.0 / rate)} ms)")
            println("  dominant freq   : ${"%.1f".format(Locale.UK, hz)} Hz  " +
                "(${"%.0f".format(Locale.UK, rate / hz)} samples per cycle, " +
                "clip spans ${"%.1f".format(Locale.UK, 360.0 * biggest.length * hz / rate)}°)")
            println("  clipped         : ${db(abs(peakIn(clipped, biggest.start, biggest.end)), offset)}")
            println("  ours            : ${db(abs(peakIn(ours, biggest.start, biggest.end)), offset)}")
            reference?.let {
                println("  reference       : ${db(abs(peakIn(it, biggest.start, biggest.end)), offset)}")
            }

            println("\n--- waveform through the run ---------------------------------")
            println("  %8s %9s %9s %9s %8s".format(Locale.UK, "sample", "clipped", "ours", "ref", "ours-ref"))
            val from = maxOf(0, biggest.start - 6)
            val to = minOf(clipped.size - 1, biggest.end + 6)
            val step = maxOf(1, (to - from) / 40)
            var i = from
            while (i <= to) {
                println("  %8d %9.4f %9.4f %9s %8s".format(
                    Locale.UK, i, clipped[i], ours[i],
                    reference?.let { "%.4f".format(Locale.UK, it[i]) } ?: "-",
                    reference?.let { "%+.4f".format(Locale.UK, ours[i] - it[i]) } ?: "-"
                ))
                i += step
            }
        }

        // Where the energy of the difference sits.
        if (reference != null) {
            println("\n--- ours vs reference over the whole region ------------------")
            val diff = FloatArray(ours.size) { ours[it] - reference[it] }
            println("  rms(ours - ref) : ${db(rms(diff), offset)}")
            println("  rms(ref)        : ${db(rms(reference), offset)}")
            println("  peak ours       : ${db(peak(ours), offset)}")
            println("  peak ref        : ${db(peak(reference), offset)}")
            println("  peak clipped    : ${db(peak(clipped), offset)}")
        }
        println("=".repeat(74))
    }

    private data class Run(val start: Int, val end: Int) {
        val length: Int get() = end - start + 1
    }

    private fun findRuns(x: FloatArray): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < x.size) {
            if (abs(x[i]) < Repair.CLIP_THRESHOLD) { i++; continue }
            val sign = x[i] >= 0f
            var end = i
            while (end + 1 < x.size && abs(x[end + 1]) >= Repair.CLIP_THRESHOLD && (x[end + 1] >= 0f) == sign) end++
            if (end > i) out.add(Run(i, end))
            i = end + 1
        }
        return out
    }

    private fun peakIn(x: FloatArray, from: Int, to: Int): Float {
        var best = 0f
        for (i in from..minOf(to, x.size - 1)) if (abs(x[i]) > abs(best)) best = x[i]
        return best
    }

    private fun peak(x: FloatArray): Float {
        var m = 0f
        for (v in x) if (abs(v) > m) m = abs(v)
        return m
    }

    private fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size).toFloat()
    }

    private fun db(amplitude: Float, offset: Double): String =
        "%.4f  (%.1f dB)".format(Locale.UK, amplitude, 20.0 * log10(abs(amplitude).toDouble()) + offset)

    private fun dominantHz(x: FloatArray, from: Int, n: Int, rate: Int): Double {
        val size = minOf(n, x.size - from)
        if (size < 64) return 0.0
        val buf = DoubleArray(size)
        for (i in 0 until size) {
            val w = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (size - 1))
            buf[i] = x[from + i] * w
        }
        DoubleFFT_1D(size.toLong()).realForward(buf)
        var bestK = 0
        var bestP = 0.0
        for (k in 1 until size / 2) {
            val re = buf[2 * k]
            val im = buf[2 * k + 1]
            val p = re * re + im * im
            if (p > bestP) { bestP = p; bestK = k }
        }
        return bestK.toDouble() * rate / size
    }

    private fun fmtTime(s: Double): String {
        val t = s.toInt()
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    private class Region(val samples: FloatArray, val rate: Int)

    private fun readRegion(file: File, atSeconds: Double, spanSeconds: Double): Region {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20)).use { input ->
            fun tag(): String { val b = ByteArray(4); input.readFully(b); return String(b, Charsets.US_ASCII) }
            fun i32(): Int { val b = ByteArray(4); input.readFully(b)
                return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                    ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24) }
            fun i16(): Int { val b = ByteArray(2); input.readFully(b)
                return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) }

            require(tag() == "RIFF"); i32(); require(tag() == "WAVE")
            var rate = 0; var ch = 0; var bits = 0
            while (true) {
                val t = tag()
                val size = i32().toLong() and 0xFFFFFFFFL
                if (t == "fmt ") {
                    i16(); ch = i16(); rate = i32(); i32(); i16(); bits = i16()
                    if (size > 16) input.skipBytes((size - 16).toInt())
                } else if (t == "data") break
                else { var left = size + (size and 1L); while (left > 0) { val s = input.skip(left); if (s <= 0) break; left -= s } }
            }

            val bytesPerFrame = ch * bits / 8
            var toSkip = (atSeconds * rate).toLong() * bytesPerFrame
            while (toSkip > 0) { val s = input.skip(toSkip); if (s <= 0) break; toSkip -= s }

            val count = (spanSeconds * rate).toInt()
            val out = FloatArray(count)
            val frame = ByteArray(bytesPerFrame)
            for (i in 0 until count) {
                var read = 0
                while (read < bytesPerFrame) {
                    val n = input.read(frame, read, bytesPerFrame - read)
                    if (n < 0) return Region(out.copyOf(i), rate)
                    read += n
                }
                val b0 = frame[0].toInt() and 0xFF
                val b1 = frame[1].toInt() and 0xFF
                out[i] = when (bits) {
                    16 -> ((b1 shl 8) or b0).toShort() / 32768f
                    24 -> {
                        var v = ((frame[2].toInt() and 0xFF) shl 16) or (b1 shl 8) or b0
                        if (v and 0x800000 != 0) v = v or -0x1000000
                        v / 8388608f
                    }
                    else -> {
                        val v = ((frame[3].toInt() and 0xFF) shl 24) or ((frame[2].toInt() and 0xFF) shl 16) or
                            (b1 shl 8) or b0
                        v / 2147483648f
                    }
                }
            }
            return Region(out, rate)
        }
    }
}
