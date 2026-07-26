package uk.co.cinema.splmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import uk.co.cinema.splmeter.data.CalFile
import uk.co.cinema.splmeter.data.Calibration
import uk.co.cinema.splmeter.data.SpectralLog
import uk.co.cinema.splmeter.data.WavWriter
import uk.co.cinema.splmeter.dsp.Bands
import uk.co.cinema.splmeter.report.Metrics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * The 1 Hz sub bins and the stored 1/3 octave bands describe overlapping
 * frequency ranges, and the RIFF header cannot describe a long recording.
 * Both are quiet, systematic errors rather than crashes, so they get a test.
 */
class BandOverlapTest {

    /** A flat curve — enough to put [Metrics] on its recompute-from-bands path. */
    private fun flatCal() = Calibration(
        splOffset = 0.0,
        cal = CalFile("flat", 0.0, doubleArrayOf(10.0, 24000.0), doubleArrayOf(0.0, 0.0)),
        name = "flat"
    )

    private fun oneWindowLog(sub: FloatArray, third: FloatArray): SpectralLog.Log {
        val header = SpectralLog.defaultHeader(48000, 48000 * 5, 0L)
        return SpectralLog.Log(
            header = header,
            tSec = floatArrayOf(0f),
            laeq = floatArrayOf(-200f),
            lceq = floatArrayOf(-200f),
            lzeq = floatArrayOf(-200f),
            peak = floatArrayOf(-200f),
            lasMax = floatArrayOf(Float.NaN),
            lasMin = floatArrayOf(Float.NaN),
            lcsMax = floatArrayOf(Float.NaN),
            lcsMin = floatArrayOf(Float.NaN),
            clipRuns = intArrayOf(0),
            clippedSamples = intArrayOf(0),
            sub = arrayOf(sub),
            third = arrayOf(third)
        )
    }

    /**
     * The 315 Hz band runs from 280.6 Hz, so it already contains the top of the
     * 1 Hz sub range. Energy there must be counted once, not twice.
     */
    @Test
    fun energyInTheOverlapIsCountedOnce() {
        val silent = -200f
        val sub = FloatArray(Bands.SUB_COUNT) { silent }
        val third = FloatArray(Bands.THIRD_OCTAVE_STORED.size) { silent }

        // 0 dB in each of the twenty 1 Hz bins from 281 to 300 Hz, all of which
        // fall inside the 315 Hz band's 280.6 - 353.6 Hz span, and 0 dB in the
        // band itself. Double counting shows up as 10*log10(21) rather than 0.
        for (hz in 281..Bands.SUB_HIGH_HZ) sub[hz - Bands.SUB_LOW_HZ] = 0f
        third[0] = 0f

        val m = Metrics.compute(oneWindowLog(sub, third), flatCal())
        assertTrue("expected the band recompute path", m.recomputedFromBands)
        assertEquals(0.0, m.leqZ, 0.01)

        // The sub spectrum chart is a different question: it still shows them.
        assertEquals(0.0, m.subAvg[290 - Bands.SUB_LOW_HZ], 0.01)
    }

    /** Below the overlap, sub bin energy must still reach the broadband sum. */
    @Test
    fun energyBelowTheOverlapStillCounts() {
        val sub = FloatArray(Bands.SUB_COUNT) { -200f }
        val third = FloatArray(Bands.THIRD_OCTAVE_STORED.size) { -200f }
        sub[100 - Bands.SUB_LOW_HZ] = 0f

        val m = Metrics.compute(oneWindowLog(sub, third), flatCal())
        assertEquals(0.0, m.leqZ, 0.01)
    }

    /** The RIFF size fields sit at bytes 4 and 40 and describe the right lengths. */
    @Test
    fun riffSizeFieldsAreWritten() {
        val f = File.createTempFile("wavwriter", ".wav")
        f.deleteOnExit()
        val samples = ShortArray(1000) { it.toShort() }
        WavWriter(f, 48000).use { it.write(samples, samples.size) }

        val head = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val dataBytes = samples.size * 2
        assertEquals((36 + dataBytes).toLong(), head.getInt(4).toLong())
        assertEquals(dataBytes.toLong(), head.getInt(40).toLong())
        assertEquals((44 + dataBytes).toLong(), f.length())
    }
}
