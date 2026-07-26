package uk.co.cinema.splmeter.report

import uk.co.cinema.splmeter.data.SpectralLog
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** CSV dumps for further analysis on the PC. */
object CsvExport {

    /** One row per window: the broadband metrics, calibrated. */
    fun metrics(m: Metrics, out: File): File {
        val offset = m.calibration.splOffset
        out.bufferedWriter().use { w ->
            w.write("time_s,LAeq,LCeq,LZeq,Lpeak,LASmax,LASmin,LCSmax,LCSmin,clipped_samples\n")
            for (i in 0 until m.log.size) {
                w.write(
                    String.format(
                        Locale.UK,
                        "%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d\n",
                        m.times[i],
                        m.splA[i], m.splC[i], m.splZ[i],
                        m.log.peak[i] + offset,
                        m.log.lasMax[i] + offset, m.log.lasMin[i] + offset,
                        m.log.lcsMax[i] + offset, m.log.lcsMin[i] + offset,
                        m.log.clippedSamples[i]
                    )
                )
            }
        }
        return out
    }

    /**
     * One row per window with every stored band, calibration applied.
     * Wide (about 300 columns) but that is the point — it is the whole spectral
     * log in a form numpy can read in one line.
     */
    fun spectrum(m: Metrics, out: File): File {
        val log: SpectralLog.Log = m.log
        val offset = m.calibration.splOffset
        out.bufferedWriter().use { w ->
            val headers = ArrayList<String>()
            headers.add("time_s")
            for (i in 0 until log.header.subCount) headers.add("${log.subHz(i).toInt()}Hz")
            for (c in log.header.thirdCentres) headers.add(String.format(Locale.UK, "%.1fHz", c))
            w.write(headers.joinToString(","))
            w.write("\n")

            val subCorr = DoubleArray(log.header.subCount) { m.calibration.bandCorrection(log.subHz(it)) }
            val thirdCorr = DoubleArray(log.header.thirdCentres.size) {
                m.calibration.bandCorrection(log.header.thirdCentres[it])
            }

            val sb = StringBuilder()
            for (i in 0 until log.size) {
                sb.setLength(0)
                sb.append(String.format(Locale.UK, "%.3f", m.times[i]))
                val sub = log.sub[i]
                for (b in sub.indices) sb.append(',').append(String.format(Locale.UK, "%.2f", sub[b] + subCorr[b] + offset))
                val third = log.third[i]
                for (b in third.indices) sb.append(',').append(String.format(Locale.UK, "%.2f", third[b] + thirdCorr[b] + offset))
                sb.append('\n')
                w.write(sb.toString())
            }
        }
        return out
    }

    /** Session-average spectrum, one row per band — the venue fingerprint. */
    fun averageSpectrum(m: Metrics, out: File): File {
        out.bufferedWriter().use { w ->
            w.write("hz,avg_db\n")
            for (i in m.subHz.indices) {
                w.write(String.format(Locale.UK, "%.0f,%.2f\n", m.subHz[i], m.subAvg[i]))
            }
            for (i in m.thirdCentres.indices) {
                if (m.thirdCentres[i] < 315.0) continue
                w.write(String.format(Locale.UK, "%.1f,%.2f\n", m.thirdCentres[i], m.thirdAvg[i]))
            }
        }
        return out
    }

    /** Convenience for re-deriving a level from stored band dB values. */
    fun sumBands(db: FloatArray): Double =
        10.0 * log10(db.sumOf { 10.0.pow(it / 10.0) }.coerceAtLeast(1e-30))
}
