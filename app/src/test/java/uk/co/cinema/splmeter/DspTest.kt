package uk.co.cinema.splmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cinema.splmeter.data.CalFile
import uk.co.cinema.splmeter.dsp.Bands
import uk.co.cinema.splmeter.dsp.Repair
import uk.co.cinema.splmeter.dsp.Weighting
import uk.co.cinema.splmeter.dsp.WindowAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class DspTest {

    private val rate = 48000
    private val n = 48000

    private fun sine(freq: Double, amplitude: Double, length: Int = n) = FloatArray(length) {
        (amplitude * sin(2.0 * PI * freq * it / rate)).toFloat()
    }

    @Test
    fun `weighting matches published values`() {
        // IEC 61672 table values, to the tolerance of the analytic approximation.
        assertEquals(0.0, Weighting.aWeightDb(1000.0), 0.05)
        assertEquals(0.0, Weighting.cWeightDb(1000.0), 0.05)
        assertEquals(-50.5, Weighting.aWeightDb(20.0), 0.3)
        assertEquals(-39.4, Weighting.aWeightDb(31.5), 0.3)
        assertEquals(-30.2, Weighting.aWeightDb(50.0), 0.3)
        assertEquals(-19.1, Weighting.aWeightDb(100.0), 0.3)
        assertEquals(-3.2, Weighting.aWeightDb(500.0), 0.2)
        assertEquals(-6.2, Weighting.cWeightDb(20.0), 0.3)
        assertEquals(-1.3, Weighting.cWeightDb(50.0), 0.2)
        assertEquals(-0.8, Weighting.cWeightDb(4000.0), 0.2)
        assertEquals(-3.0, Weighting.cWeightDb(8000.0), 0.3)
    }

    @Test
    fun `rms level is recovered from the spectrum`() {
        val amplitude = 0.5
        val expected = 20.0 * log10(amplitude / Math.sqrt(2.0)) // -9.03 dBFS
        val analyzer = WindowAnalyzer(rate, n)
        val r = analyzer.analyze(sine(1000.0, amplitude), 0f, Repair.Stats(0, 0, 0))

        assertEquals(expected, r.lzeq.toDouble(), 0.05)
        // A and C are both ~0 dB at 1 kHz.
        assertEquals(expected, r.laeq.toDouble(), 0.1)
        assertEquals(expected, r.lceq.toDouble(), 0.1)
        assertEquals(20.0 * log10(amplitude), r.peak.toDouble(), 0.05)
    }

    @Test
    fun `a weighting attenuates a 50 Hz tone`() {
        val analyzer = WindowAnalyzer(rate, n)
        val r = analyzer.analyze(sine(50.0, 0.5), 0f, Repair.Stats(0, 0, 0))
        assertEquals(Weighting.aWeightDb(50.0), (r.laeq - r.lzeq).toDouble(), 0.2)
        assertEquals(Weighting.cWeightDb(50.0), (r.lceq - r.lzeq).toDouble(), 0.2)
    }

    @Test
    fun `energy lands in the right band`() {
        val analyzer = WindowAnalyzer(rate, n)
        val r = analyzer.analyze(sine(58.0, 0.5), 0f, Repair.Stats(0, 0, 0))
        val loudest = r.sub.indices.maxByOrNull { r.sub[it] }!!
        assertEquals(58, Bands.SUB_LOW_HZ + loudest)
    }

    @Test
    fun `declipping recovers a flattened sub bass peak`() {
        val clean = sine(35.0, 1.6) // would peak at 1.6 -> clipped hard
        val clipped = FloatArray(n) { clean[it].coerceIn(-1f, 1f) }

        val before = clipped.max()
        Repair.declip(clipped)
        val after = clipped.max()

        assertTrue("declipping should push the peak above full scale, got $after", after > 1.1f)
        assertTrue("reconstruction should not overshoot wildly, got $after", after < 2.2f)
        assertTrue(after > before)
        // The reconstructed peak should be within a dB or so of the truth.
        assertTrue("peak $after vs true 1.6", abs(after - 1.6f) < 0.25f)
    }

    @Test
    fun `declicking removes an isolated spike without touching the tone`() {
        val x = sine(200.0, 0.4)
        val reference = x.copyOf()
        x[12345] = 0.95f
        x[12346] = -0.9f

        val fixed = Repair.declick(x)
        assertTrue("expected the spike to be found", fixed >= 1)
        assertTrue(abs(x[12345] - reference[12345]) < 0.05f)

        var maxDrift = 0f
        for (i in x.indices) {
            if (i in 12340..12350) continue
            maxDrift = maxOf(maxDrift, abs(x[i] - reference[i]))
        }
        assertEquals(0f, maxDrift, 1e-6f)
    }

    @Test
    fun `factory UMIK file parses, and its curve is subtracted`() {
        // Real miniDSP layout: CRLF, a second header line, SERNO in the Sens
        // Factor line, and 1/56 octave spacing.
        val text = "\"Sens Factor =-11.77dB, SERNO: 0000000\"\r\n" +
            "\"Auto-generated 90-degree calibration file\"\r\n" +
            "10.054\t-6.4540\r\n" +
            "1000.000\t0.0000\r\n" +
            "8000.000\t2.0000\r\n"
        val cal = CalFile.parse("reference_mic_90deg.txt", text)

        assertEquals(-11.77, cal.sensFactor!!, 1e-9)
        assertEquals(135.77, cal.splOffset, 1e-9)
        assertEquals(3, cal.freqs.size)
        // Standard convention: a mic reading +2 dB high gets -2 dB applied.
        assertEquals(-2.0, cal.bandCorrection(8000.0), 1e-9)
    }

    @Test
    fun `a cal curve is the mic's own response, subtracted`() {
        // There is one convention and the header does not change it: whatever
        // wrote the file, a mic reading +21 dB high at 8 kHz gets -21 applied.
        val text = "\"Sens Factor =-2.06dB, Phone response measured against a reference mic\"\n" +
            "8000.000\t21.0000\n1000.000\t0.0000\n"
        val cal = CalFile.parse("phone_cal.txt", text)

        assertEquals(-21.0, cal.bandCorrection(8000.0), 1e-9)
        assertEquals(0.0, cal.bandCorrection(1000.0), 1e-9)
    }

    @Test
    fun `cal file parses sens factor and curve`() {
        val text = """
            "Sens Factor =-2.06dB, Phone calibrated against UMIK-1"
            20.000	2.6780
            1000.000	0.0000
            20000.000	-3.5000
        """.trimIndent()
        val cal = CalFile.parse("phone_cal.txt", text)

        assertEquals(-2.06, cal.sensFactor!!, 1e-9)
        assertEquals(126.06, cal.splOffset, 1e-9) // 100 - sens + 24
        assertEquals(2.678, cal.correctionAt(20.0), 1e-6)
        assertEquals(0.0, cal.correctionAt(1000.0), 1e-6)
        assertEquals(1.339, cal.correctionAt(510.0), 0.01) // linear midpoint
        assertEquals(2.678, cal.correctionAt(5.0), 1e-6)   // clamped below range
        assertEquals(-3.5, cal.correctionAt(30000.0), 1e-6) // clamped above
    }
}
