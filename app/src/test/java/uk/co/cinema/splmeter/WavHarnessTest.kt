package uk.co.cinema.splmeter

import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.cinema.splmeter.data.CalFile
import uk.co.cinema.splmeter.data.Calibration
import uk.co.cinema.splmeter.data.SpectralLog
import uk.co.cinema.splmeter.dsp.Repair
import uk.co.cinema.splmeter.dsp.WindowAnalyzer
import uk.co.cinema.splmeter.dsp.WindowResult
import uk.co.cinema.splmeter.report.Metrics
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Offline validation harness: pushes an existing WAV through exactly the same
 * analysis path the app uses live, so the results can be compared against
 * the reference Python implementation on the same file.
 *
 * Skipped unless a file is given:
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*WavHarness*" \
 *   -Dwav=recording.wav \
 *   -Dsens=-2.06 -Dwindow=5 -Drepair=false
 * ```
 *
 * Options: `-Dwindow=` seconds (default 2), `-Dsens=` Sens Factor,
 * `-Dcal=` cal file path (applies the response curve too),
 * `-Drepair=false` to skip declick/declip, `-Dseconds=` to analyse only the
 * first N seconds, `-Dout=` to write a .splog next to the results.
 */
class WavHarnessTest {

    @Test
    fun `analyse a wav file the way the app would`() {
        val path = System.getProperty("wav")
        assumeTrue("set -Dwav=<path> to run the harness", path != null)
        val file = File(path!!)
        assumeTrue("no such file: $path", file.exists())

        val windowSeconds = System.getProperty("window")?.toDouble() ?: 2.0
        val doRepair = System.getProperty("repair")?.toBooleanStrict() ?: true
        val limitSeconds = System.getProperty("seconds")?.toDouble() ?: Double.MAX_VALUE

        val calPath = System.getProperty("cal")
        val calFile = if (calPath != null) CalFile.parse(File(calPath).name, File(calPath).readText())
        else CalFile.NONE
        val sens = System.getProperty("sens")?.toDouble() ?: calFile.sensFactor
        val offset = if (sens != null) 100.0 - sens + 24.0 else 0.0
        // the reference Python implementation applies only the Sens Factor to its metrics, so by
        // default so do we — pass -Dcal= to bring the response curve in as well.
        val calibration = Calibration(offset, calFile, calFile.name)

        val wav = WavReader(file)
        val windowSamples = (wav.sampleRate * windowSeconds).toInt()
        require(windowSamples % (wav.sampleRate / 20) == 0) {
            "window must be a whole number of 50 ms blocks"
        }

        println("=".repeat(72))
        println("File      : ${file.name}")
        println("Format    : ${wav.sampleRate} Hz, ${wav.channels} ch, ${wav.bitsPerSample}-bit, " +
            "%.1f min".format(Locale.UK, wav.totalFrames / wav.sampleRate.toDouble() / 60.0))
        println("Window    : ${"%.0f".format(windowSeconds)} s ($windowSamples samples)")
        println("Repair    : ${if (doRepair) "declick + declip" else "off"}")
        println("Offset    : ${"%+.2f".format(Locale.UK, offset)} dB" +
            (if (sens != null) " (Sens Factor $sens)" else " (uncalibrated)"))
        println("Cal curve : ${if (calibration.hasCurve) "applied" else "not applied"}")
        println("=".repeat(72))

        val analyzer = WindowAnalyzer(wav.sampleRate, windowSamples)
        val results = ArrayList<WindowResult>()
        val buffer = FloatArray(windowSamples)
        val started = System.currentTimeMillis()
        var totalClipped = 0L
        var totalClicks = 0L
        var peakBeforeRepair = 0f

        while (wav.readWindow(buffer)) {
            val t = (results.size * windowSamples).toDouble() / wav.sampleRate
            if (t >= limitSeconds) break
            for (v in buffer) if (kotlin.math.abs(v) > peakBeforeRepair) peakBeforeRepair = kotlin.math.abs(v)
            val stats = if (doRepair) Repair.repair(buffer) else Repair.Stats(0, 0, 0)
            totalClipped += stats.clippedSamples
            totalClicks += stats.clicksFixed
            results.add(analyzer.analyze(buffer, t.toFloat(), stats))
            if (results.size % 250 == 0) {
                print("\r  ${results.size} windows (${Metrics.formatTime(t)})…")
                System.out.flush()
            }
        }
        wav.close()
        println("\r  ${results.size} windows in ${(System.currentTimeMillis() - started) / 1000}s" + " ".repeat(20))

        assumeTrue("no complete windows in the file", results.isNotEmpty())

        val log = toLog(results, wav.sampleRate, windowSamples)
        val m = Metrics.compute(log, calibration)

        // What the reference Python implementation reports as LASmax/LASmin is simply the max and
        // min of the per-window RMS, not a Fast time-weighted level. Print both
        // so the comparison is like for like.
        val pyMaxA = m.splA.max(); val pyMinA = m.splA.min()
        val pyMaxC = m.splC.max(); val pyMinC = m.splC.min()

        println()
        println("--- app metrics ---------------------------------------------")
        row("Leq (A)", m.leqA)
        row("Leq (C)", m.leqC)
        row("Leq (Z)", m.leqZ)
        row("LZpeak", m.lzPeak, "@ ${Metrics.formatTime(m.lzPeakTime)}")
        row("LASmax (Fast 125 ms)", m.lasMax)
        row("LASmin (Fast 125 ms)", m.lasMin)
        row("LCSmax (Fast 125 ms)", m.lcsMax)
        row("LCSmin (Fast 125 ms)", m.lcsMin)
        println()
        println("--- same definitions the reference Python implementation uses -----------------")
        row("LASmax (per-window RMS)", pyMaxA.toDouble())
        row("LASmin (per-window RMS)", pyMinA.toDouble())
        row("LCSmax (per-window RMS)", pyMaxC.toDouble())
        row("LCSmin (per-window RMS)", pyMinC.toDouble())
        row("LZpeak (before repair)", 20.0 * log10(peakBeforeRepair.toDouble()) + offset)
        println()
        println("--- repair --------------------------------------------------")
        println("  clipped samples : $totalClipped")
        println("  clicks fixed    : $totalClicks")
        println("  windows w/ clip : ${results.count { it.clippedSamples > 0 }} of ${results.size}")
        println()
        println("--- top peaks -----------------------------------------------")
        m.topPeaks.forEachIndexed { i, p ->
            println("  ${i + 1}. %6.1f dB  @ %-9s  %.0f Hz".format(
                Locale.UK, p.db, Metrics.formatTime(p.timeSec), p.dominantHz))
        }
        println()
        println("--- 1/3 octave average --------------------------------------")
        for (i in m.thirdCentres.indices) {
            println("  %7s Hz  avg %6.1f   peak %6.1f".format(
                Locale.UK, uk.co.cinema.splmeter.dsp.Bands.label(m.thirdCentres[i]),
                m.thirdAvg[i], m.thirdPeak[i]))
        }
        println()
        println("--- sub spectrum, 20-100 Hz ---------------------------------")
        for (i in m.subHz.indices) {
            val hz = m.subHz[i].toInt()
            if (hz in 20..100) println("  %3d Hz  %6.1f".format(Locale.UK, hz, m.subAvg[i]))
        }

        System.getProperty("out")?.let { out ->
            val f = File(out)
            SpectralLog.Writer(f, SpectralLog.defaultHeader(wav.sampleRate, windowSamples, 0L)).use { w ->
                results.forEach { w.append(it) }
            }
            println("\nSpectral log written: ${f.absolutePath} (${f.length() / 1024} KB)")
        }
        println("=".repeat(72))
    }

    private fun row(label: String, value: Double, extra: String = "") {
        println("  %-24s %8.1f dB %s".format(Locale.UK, label, value, extra))
    }

    private fun toLog(results: List<WindowResult>, rate: Int, windowSamples: Int): SpectralLog.Log {
        fun col(f: (WindowResult) -> Float) = FloatArray(results.size) { f(results[it]) }
        return SpectralLog.Log(
            header = SpectralLog.defaultHeader(rate, windowSamples, 0L),
            tSec = col { it.tSec },
            laeq = col { it.laeq }, lceq = col { it.lceq }, lzeq = col { it.lzeq },
            peak = col { it.peak },
            lasMax = col { it.lasMax }, lasMin = col { it.lasMin },
            lcsMax = col { it.lcsMax }, lcsMin = col { it.lcsMin },
            clipRuns = IntArray(results.size) { results[it].clipRuns },
            clippedSamples = IntArray(results.size) { results[it].clippedSamples },
            sub = Array(results.size) { results[it].sub },
            third = Array(results.size) { results[it].third }
        )
    }
}

/**
 * Minimal streaming WAV reader — 16/24/32-bit integer and 32-bit float, any
 * channel count, taking the left channel like the Python scripts do.
 */
private class WavReader(file: File) : AutoCloseable {

    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val totalFrames: Long

    private val input = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20))
    private val isFloat: Boolean
    private val bytesPerFrame: Int
    private var framesRead = 0L
    private val frameBuf: ByteArray

    init {
        require(readTag() == "RIFF") { "not a RIFF file" }
        input.skipBytes(4)
        require(readTag() == "WAVE") { "not a WAVE file" }

        var rate = 0; var ch = 0; var bits = 0; var format = 1
        var dataBytes = 0L
        while (true) {
            val tag = readTag()
            val size = readIntLe().toLong() and 0xFFFFFFFFL
            when (tag) {
                "fmt " -> {
                    format = readShortLe()
                    ch = readShortLe()
                    rate = readIntLe()
                    readIntLe()   // byte rate
                    readShortLe() // block align
                    bits = readShortLe()
                    if (size > 16) input.skipBytes((size - 16).toInt())
                }
                "data" -> { dataBytes = size; break }
                else -> skipFully(size + (size and 1L))
            }
        }

        sampleRate = rate
        channels = ch
        bitsPerSample = bits
        isFloat = format == 3
        bytesPerFrame = ch * bits / 8
        totalFrames = dataBytes / bytesPerFrame
        frameBuf = ByteArray(bytesPerFrame)
        require(bits == 16 || bits == 24 || bits == 32) { "unsupported bit depth: $bits" }
    }

    /** Fills [out] with left-channel samples. Returns false at end of file. */
    fun readWindow(out: FloatArray): Boolean {
        for (i in out.indices) {
            var read = 0
            while (read < bytesPerFrame) {
                val n = input.read(frameBuf, read, bytesPerFrame - read)
                if (n < 0) return false
                read += n
            }
            out[i] = decodeLeft()
            framesRead++
        }
        return true
    }

    private fun decodeLeft(): Float {
        val b0 = frameBuf[0].toInt() and 0xFF
        val b1 = frameBuf[1].toInt() and 0xFF
        return when {
            bitsPerSample == 16 -> ((b1 shl 8) or b0).toShort() / 32768f
            bitsPerSample == 24 -> {
                val b2 = frameBuf[2].toInt() and 0xFF
                var v = (b2 shl 16) or (b1 shl 8) or b0
                if (v and 0x800000 != 0) v = v or -0x1000000
                v / 8388608f
            }
            isFloat -> Float.fromBits(
                ((frameBuf[3].toInt() and 0xFF) shl 24) or ((frameBuf[2].toInt() and 0xFF) shl 16) or
                    (b1 shl 8) or b0
            )
            else -> {
                val v = ((frameBuf[3].toInt() and 0xFF) shl 24) or ((frameBuf[2].toInt() and 0xFF) shl 16) or
                    (b1 shl 8) or b0
                v / 2147483648f
            }
        }
    }

    private fun readTag(): String {
        val b = ByteArray(4)
        input.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readIntLe(): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(): Int {
        val b = ByteArray(2)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) break
            left -= s
        }
    }

    override fun close() {
        input.close()
    }
}
