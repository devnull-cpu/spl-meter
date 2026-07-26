package uk.co.cinema.splmeter.dsp

/**
 * The two band sets stored per analysis window.
 *
 *  - [SUB_LOW_HZ]..[SUB_HIGH_HZ] at 1 Hz resolution: enough to see room
 *    cancellation notches after the fact.
 *  - Nominal 1/3 octave bands from 315 Hz up: everything above the sub range,
 *    where 1 Hz resolution buys nothing.
 *
 * Third-octave bands below 315 Hz are not stored separately — they can be
 * integrated back out of the 1 Hz sub bins when a report is generated.
 */
object Bands {

    const val SUB_LOW_HZ = 15
    const val SUB_HIGH_HZ = 300
    const val SUB_COUNT = SUB_HIGH_HZ - SUB_LOW_HZ + 1 // 286

    /** Nominal ISO 1/3 octave centres, 20 Hz .. 20 kHz. */
    val THIRD_OCTAVE_ALL = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0,
        315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0,
        3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    )

    /** The subset actually written to the spectral log (315 Hz and above). */
    val THIRD_OCTAVE_STORED = THIRD_OCTAVE_ALL.filter { it >= 315.0 }.toDoubleArray()

    private val SIXTH_OCTAVE = Math.pow(2.0, 1.0 / 6.0)

    fun lowEdge(centre: Double) = centre / SIXTH_OCTAVE

    fun highEdge(centre: Double) = centre * SIXTH_OCTAVE

    fun label(centre: Double): String = when {
        centre >= 1000.0 -> {
            val k = centre / 1000.0
            if (centre % 1000.0 == 0.0) "${k.toInt()}k" else String.format("%.1fk", k)
        }
        centre == 31.5 -> "31.5"
        else -> centre.toInt().toString()
    }
}
