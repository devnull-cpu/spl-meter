package uk.co.cinema.splmeter.report

import uk.co.cinema.splmeter.data.Calibration
import uk.co.cinema.splmeter.data.SpectralLog
import uk.co.cinema.splmeter.dsp.Bands
import uk.co.cinema.splmeter.dsp.Weighting
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Turns a stored spectral log plus a calibration into everything a report needs.
 *
 * When the calibration has no response curve the broadband values recorded at
 * full FFT resolution are used directly. When it does have one, the A/C/Z levels
 * are recomputed from the stored band powers with the correction applied — which
 * is exactly what makes an old recording re-analysable with a better cal file.
 */
class Metrics private constructor(
    val log: SpectralLog.Log,
    val calibration: Calibration,
    val recomputedFromBands: Boolean,
    val times: FloatArray,
    val splA: FloatArray,
    val splC: FloatArray,
    val splZ: FloatArray,
    val leqA: Double,
    val leqC: Double,
    val leqZ: Double,
    val lasMax: Double,
    val lasMin: Double,
    val lcsMax: Double,
    val lcsMin: Double,
    val lzPeak: Double,
    val lzPeakTime: Double,
    val thirdCentres: DoubleArray,
    val thirdAvg: DoubleArray,
    val thirdPeak: DoubleArray,
    val subHz: DoubleArray,
    val subAvg: DoubleArray,
    val topPeaks: List<PeakMoment>,
    val clippedWindows: Int
) {
    data class PeakMoment(val db: Double, val timeSec: Double, val dominantHz: Double)

    val durationSec: Double get() = log.durationSec
    val windowSeconds: Double get() = log.header.windowSeconds

    companion object {

        fun compute(log: SpectralLog.Log, calibration: Calibration): Metrics {
            val n = log.size
            val offset = calibration.splOffset
            val useBands = calibration.hasCurve

            // Correction per stored band, applied in the power domain.
            val subCorr = DoubleArray(log.header.subCount) {
                10.0.pow(calibration.bandCorrection(log.subHz(it)) / 10.0)
            }
            val thirdCorr = DoubleArray(log.header.thirdCentres.size) {
                10.0.pow(calibration.bandCorrection(log.header.thirdCentres[it]) / 10.0)
            }

            val splA = FloatArray(n)
            val splC = FloatArray(n)
            val splZ = FloatArray(n)
            var aSum = 0.0; var cSum = 0.0; var zSum = 0.0
            var aSumRaw = 0.0

            // Full third-octave set, 20 Hz .. 20 kHz: low bands integrated out
            // of the 1 Hz sub bins, high bands taken from the stored bands.
            val centres = Bands.THIRD_OCTAVE_ALL
            val thirdPowerSum = DoubleArray(centres.size)
            val thirdPeakDb = DoubleArray(centres.size) { -200.0 }
            val subPowerSum = DoubleArray(log.header.subCount)

            // Map each display band either to a stored 1/3 octave band, or to a
            // range of 1 Hz sub bins to integrate.
            val storedIndex = IntArray(centres.size) { b ->
                log.header.thirdCentres.indexOfFirst { abs(it - centres[b]) < 0.01 }
            }
            // The 1 Hz sub bins run to 300 Hz but the lowest stored 1/3 octave
            // band is 315 Hz, whose lower edge is 280.6 Hz — so the two sets
            // overlap. Summing both wholesale counts that slice twice; for pink
            // content it is about 0.9% of the total, or 0.04 dB on Leq(Z). The
            // stored band covers the slice already, so the sub bins stop at its
            // lower edge. Only the broadband sums are trimmed: the sub spectrum
            // chart still wants every bin.
            val bandsFrom = log.header.thirdCentres.minOrNull()
                ?.let { Bands.lowEdge(it) } ?: Double.MAX_VALUE

            val subFrom = IntArray(centres.size)
            val subTo = IntArray(centres.size)
            for (b in centres.indices) {
                if (storedIndex[b] >= 0) continue
                val lo = Bands.lowEdge(centres[b])
                val hi = Bands.highEdge(centres[b])
                var from = -1
                var to = -2
                for (i in 0 until log.header.subCount) {
                    val hz = log.subHz(i)
                    if (hz >= lo && hz < hi) {
                        if (from < 0) from = i
                        to = i
                    }
                }
                subFrom[b] = from
                subTo[b] = to
            }

            for (w in 0 until n) {
                val sub = log.sub[w]
                val third = log.third[w]

                var aP = 0.0; var cP = 0.0; var zP = 0.0
                var aPRaw = 0.0
                for (i in sub.indices) {
                    val hz = log.subHz(i)
                    val p = 10.0.pow(sub[i] / 10.0)
                    val pc = p * subCorr[i]
                    subPowerSum[i] += pc
                    if (hz >= bandsFrom) continue // already inside a stored band
                    zP += pc
                    aP += pc * 10.0.pow(Weighting.aWeightDb(hz) / 10.0)
                    cP += pc * 10.0.pow(Weighting.cWeightDb(hz) / 10.0)
                    aPRaw += p * 10.0.pow(Weighting.aWeightDb(hz) / 10.0)
                }
                for (i in third.indices) {
                    val hz = log.header.thirdCentres[i]
                    val p = 10.0.pow(third[i] / 10.0)
                    val pc = p * thirdCorr[i]
                    zP += pc
                    aP += pc * 10.0.pow(Weighting.aWeightDb(hz) / 10.0)
                    cP += pc * 10.0.pow(Weighting.cWeightDb(hz) / 10.0)
                    aPRaw += p * 10.0.pow(Weighting.aWeightDb(hz) / 10.0)
                }

                // Band-resolved third-octave spectrum for the charts.
                for (b in centres.indices) {
                    val idx = storedIndex[b]
                    var power = 0.0
                    if (idx >= 0) {
                        power = 10.0.pow(third[idx] / 10.0) * thirdCorr[idx]
                    } else if (subFrom[b] >= 0) {
                        for (i in subFrom[b]..subTo[b]) power += 10.0.pow(sub[i] / 10.0) * subCorr[i]
                    }
                    if (power > 0.0) {
                        thirdPowerSum[b] += power
                        val db = 10.0 * log10(power) + offset
                        if (db > thirdPeakDb[b]) thirdPeakDb[b] = db
                    }
                }

                if (useBands) {
                    splA[w] = (10.0 * log10(aP.coerceAtLeast(1e-30)) + offset).toFloat()
                    splC[w] = (10.0 * log10(cP.coerceAtLeast(1e-30)) + offset).toFloat()
                    splZ[w] = (10.0 * log10(zP.coerceAtLeast(1e-30)) + offset).toFloat()
                    aSum += aP; cSum += cP; zSum += zP
                } else {
                    splA[w] = (log.laeq[w] + offset).toFloat()
                    splC[w] = (log.lceq[w] + offset).toFloat()
                    splZ[w] = (log.lzeq[w] + offset).toFloat()
                    aSum += 10.0.pow(log.laeq[w] / 10.0)
                    cSum += 10.0.pow(log.lceq[w] / 10.0)
                    zSum += 10.0.pow(log.lzeq[w] / 10.0)
                }
                aSumRaw += aPRaw
            }

            // Fast time-weighted extremes were computed live without the response
            // curve. Shift them by the session-average effect of the curve so the
            // LAS/LCS cards stay consistent with everything else.
            val fastShift = if (useBands && n > 0 && aSumRaw > 0.0)
                10.0 * log10(aSum / aSumRaw) else 0.0

            val safeN = maxOf(n, 1)
            val thirdAvg = DoubleArray(centres.size) {
                if (thirdPowerSum[it] > 0) 10.0 * log10(thirdPowerSum[it] / safeN) + offset else -200.0
            }
            val subAvg = DoubleArray(log.header.subCount) {
                if (subPowerSum[it] > 0) 10.0 * log10(subPowerSum[it] / safeN) + offset else -200.0
            }
            val subHz = DoubleArray(log.header.subCount) { log.subHz(it) }

            val peaks = topPeaks(log, offset, subCorr, thirdCorr)

            return Metrics(
                log = log,
                calibration = calibration,
                recomputedFromBands = useBands,
                times = log.tSec,
                splA = splA, splC = splC, splZ = splZ,
                leqA = if (n == 0) -200.0 else 10.0 * log10(aSum / safeN) + offset,
                leqC = if (n == 0) -200.0 else 10.0 * log10(cSum / safeN) + offset,
                leqZ = if (n == 0) -200.0 else 10.0 * log10(zSum / safeN) + offset,
                // NaN marks a window with no usable level; ignore those rather
                // than letting one pin an extreme for the whole session.
                lasMax = extreme(log.lasMax, true) + offset + fastShift,
                lasMin = extreme(log.lasMin, false) + offset + fastShift,
                lcsMax = extreme(log.lcsMax, true) + offset + fastShift,
                lcsMin = extreme(log.lcsMin, false) + offset + fastShift,
                lzPeak = peaks.firstOrNull()?.db ?: -200.0,
                lzPeakTime = peaks.firstOrNull()?.timeSec ?: 0.0,
                thirdCentres = centres,
                thirdAvg = thirdAvg,
                thirdPeak = thirdPeakDb,
                subHz = subHz,
                subAvg = subAvg,
                topPeaks = peaks,
                clippedWindows = log.clippedSamples.count { it > 0 }
            )
        }

        /** Loudest windows, at least 10 s apart, with the dominant band of each. */
        private fun topPeaks(
            log: SpectralLog.Log,
            offset: Double,
            subCorr: DoubleArray,
            thirdCorr: DoubleArray
        ): List<Metrics.PeakMoment> {
            if (log.size == 0) return emptyList()
            val used = BooleanArray(log.size)
            val gapWindows = maxOf(1, (10.0 / log.header.windowSeconds).toInt())
            val out = ArrayList<Metrics.PeakMoment>()
            repeat(5) {
                var best = -1
                var bestDb = -1e9f
                for (i in 0 until log.size) {
                    if (used[i]) continue
                    if (log.peak[i] > bestDb) { bestDb = log.peak[i]; best = i }
                }
                if (best < 0 || bestDb <= -199f) return@repeat

                var domHz = 0.0
                var domDb = -1e9
                val sub = log.sub[best]
                for (i in sub.indices) {
                    val v = sub[i] + 10.0 * log10(subCorr[i])
                    if (v > domDb) { domDb = v; domHz = log.subHz(i) }
                }
                val third = log.third[best]
                for (i in third.indices) {
                    val v = third[i] + 10.0 * log10(thirdCorr[i])
                    if (v > domDb) { domDb = v; domHz = log.header.thirdCentres[i] }
                }

                out.add(Metrics.PeakMoment(bestDb + offset, log.tSec[best].toDouble(), domHz))
                for (j in maxOf(0, best - gapWindows)..minOf(log.size - 1, best + gapWindows)) used[j] = true
            }
            return out
        }

        /** Max or min over the values that are real measurements. */
        private fun extreme(values: FloatArray, wantMax: Boolean): Double {
            var best = Double.NaN
            for (v in values) {
                if (v.isNaN() || v <= -199f) continue
                if (best.isNaN() || (if (wantMax) v > best else v < best)) best = v.toDouble()
            }
            return if (best.isNaN()) -200.0 else best
        }

        fun formatTime(seconds: Double): String {
            val s = seconds.toInt()
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
