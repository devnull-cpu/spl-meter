package uk.co.cinema.splmeter.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * IEC 61672 A and C frequency weightings.
 *
 * Deliberately identical to the analytic forms in the reference Python implementation so that the
 * app and the PC scripts agree to the last decimal on the same audio.
 */
object Weighting {

    const val A = 'A'
    const val C = 'C'
    const val Z = 'Z'

    fun aWeightDb(freq: Double): Double {
        if (freq <= 0.0) return -200.0
        val f2 = freq * freq
        val ra = (12194.0.pow(2) * f2 * f2) /
            ((f2 + 20.6.pow(2)) *
                sqrt((f2 + 107.7.pow(2)) * (f2 + 737.9.pow(2))) *
                (f2 + 12194.0.pow(2)))
        if (ra <= 0.0) return -200.0
        return 20.0 * log10(ra) + 2.0
    }

    fun cWeightDb(freq: Double): Double {
        if (freq <= 0.0) return -200.0
        val f2 = freq * freq
        val rc = (12194.0.pow(2) * f2) / ((f2 + 20.6.pow(2)) * (f2 + 12194.0.pow(2)))
        if (rc <= 0.0) return -200.0
        return 20.0 * log10(rc) + 0.06
    }

    fun weightDb(freq: Double, kind: Char): Double = when (kind) {
        A -> aWeightDb(freq)
        C -> cWeightDb(freq)
        else -> 0.0
    }

    /**
     * Lower edge of the measurement band.
     *
     * IEC 61672 specifies Z weighting over 10 Hz to 20 kHz, not down to DC, and
     * for good reason: UNPROCESSED capture applies no high-pass at all, so a
     * phone picks up table rumble, handling and outright DC drift below the
     * audible band. Left in, that infrasound landed straight in Leq(Z) and
     * Leq(C) — on a quiet measurement it was worth 4 to 5 dB of pure junk.
     */
    const val MIN_HZ = 10.0

    /**
     * Power-domain multipliers (10^(dB/10)) for every one-sided FFT bin.
     * Bins below [MIN_HZ] are zeroed, which for Z weighting is the only thing
     * that band-limits the sum at all.
     */
    fun powerCurve(nFft: Int, sampleRate: Int, kind: Char, minHz: Double = MIN_HZ): DoubleArray {
        val nBins = nFft / 2 + 1
        val binHz = sampleRate.toDouble() / nFft
        return DoubleArray(nBins) { k ->
            val f = k * binHz
            if (f < minHz) 0.0 else 10.0.pow(weightDb(f, kind) / 10.0)
        }
    }

    /** Band edge when band limiting is switched off — still excludes DC. */
    const val UNLIMITED_HZ = 1.0
}
