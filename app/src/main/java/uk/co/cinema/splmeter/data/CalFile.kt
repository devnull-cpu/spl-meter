package uk.co.cinema.splmeter.data

/**
 * A UMIK-1 style calibration file — the same format `calibrate_phone.py` writes,
 * so a phone cal generated on the PC drops straight in.
 *
 * ```
 * "Sens Factor =-2.06dB, Phone calibrated against UMIK-1"
 * 20.000    2.6780
 * 20.706    2.3779
 * ...
 * ```
 *
 * Two independent things live in that file:
 *  - **Sens Factor**, which fixes the absolute level (dBFS -> dB SPL)
 *  - the **frequency response curve**, in dB per frequency
 *
 * The convention is standard and not a matter of taste: REW's documentation
 * says the file holds "the actual gain response of the meter or microphone …
 * which will then be subtracted from subsequent measurements". A mic that reads
 * high somewhere carries a positive value there. See [legacyInvertedCurve] for
 * the one exception this app has to cope with.
 */
class CalFile(
    val name: String,
    val sensFactor: Double?,
    val freqs: DoubleArray,
    val corrections: DoubleArray,
    /**
     * True for files written by the old calibrate_phone.py, whose curve is
     * sign-inverted relative to the standard.
     *
     * The format is standard and so is the convention: REW's documentation says
     * a cal file holds "the actual gain response of the meter or microphone …
     * which will then be subtracted from subsequent measurements", so a mic that
     * reads high somewhere carries a positive value there. The old script wrote
     * reference-minus-phone, the negative of that. Measuring a phone against a
     * reference mic showed it reading +20.7 dB high at 7-8 kHz while the file
     * that script produced said -21, which is what gave the game away.
     *
     * Detected from the header so existing files keep working unchanged.
     */
    val legacyInvertedCurve: Boolean = false
) {
    val hasCurve: Boolean get() = freqs.size >= 2

    /** dBFS -> dB SPL offset, using the same convention as the reference Python implementation. */
    val splOffset: Double
        get() = if (sensFactor == null) 0.0 else 100.0 - sensFactor + 24.0

    /** Linear interpolation, clamped to the end values outside the file's range. */
    fun correctionAt(hz: Double): Double {
        if (!hasCurve) return 0.0
        if (hz <= freqs.first()) return corrections.first()
        if (hz >= freqs.last()) return corrections.last()
        var lo = 0
        var hi = freqs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (freqs[mid] <= hz) lo = mid else hi = mid
        }
        val t = (hz - freqs[lo]) / (freqs[hi] - freqs[lo])
        return corrections[lo] + t * (corrections[hi] - corrections[lo])
    }

    /** dB to add to a measured band level at [hz], honouring the convention. */
    fun bandCorrection(hz: Double): Double =
        if (!hasCurve) 0.0 else (if (legacyInvertedCurve) 1.0 else -1.0) * correctionAt(hz)

    fun summary(): String = buildString {
        append(if (sensFactor != null) "Sens %.2f dB · offset %.2f dB".format(sensFactor, splOffset) else "no Sens Factor")
        if (hasCurve) {
            append(" · %d points, %.0f–%.0f Hz".format(freqs.size, freqs.first(), freqs.last()))
            append(" · %+.1f to %+.1f dB".format(corrections.min(), corrections.max()))
            if (legacyInvertedCurve) append(" · legacy inverted sign")
        } else {
            append(" · no response curve")
        }
    }

    companion object {
        val NONE = CalFile("None (uncalibrated)", null, DoubleArray(0), DoubleArray(0))

        /** Header written by calibrate_phone.py before the sign was corrected. */
        private const val LEGACY_MARKER = "Phone calibrated against UMIK-1"

        fun parse(name: String, text: String): CalFile {
            val legacy = text.lineSequence().take(4).any { it.contains(LEGACY_MARKER) }
            var sens: Double? = null
            val f = ArrayList<Double>()
            val c = ArrayList<Double>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().trim('"').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("Sens Factor", ignoreCase = true)) {
                    sens = line.substringAfter('=', "").substringBefore("dB").trim().toDoubleOrNull()
                    continue
                }
                if (line.startsWith("*") || line.startsWith("#") || line.startsWith("Auto-generated")) continue
                val parts = line.split(Regex("[\\s,]+"))
                if (parts.size < 2) continue
                val hz = parts[0].toDoubleOrNull() ?: continue
                val db = parts[1].toDoubleOrNull() ?: continue
                f.add(hz)
                c.add(db)
            }
            // Cal files are written ascending, but don't rely on it.
            val order = f.indices.sortedBy { f[it] }
            return CalFile(
                name = name,
                sensFactor = sens,
                freqs = DoubleArray(order.size) { f[order[it]] },
                corrections = DoubleArray(order.size) { c[order[it]] },
                legacyInvertedCurve = legacy
            )
        }
    }
}
