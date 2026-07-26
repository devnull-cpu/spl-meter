package uk.co.cinema.splmeter.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.cinema.splmeter.data.Calibration
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.dsp.Repair
import uk.co.cinema.splmeter.dsp.Weighting
import uk.co.cinema.splmeter.dsp.WindowAnalyzer
import kotlin.math.log10
import kotlin.math.pow

/**
 * Measures the equivalent level over a fixed period, for calibrating against a
 * separate sound level meter.
 *
 * Runs the same [WindowAnalyzer] the recorder uses, through the same capture
 * settings, so the number it produces is the number a recording would produce.
 * Energy-averaged over the whole measurement rather than time-weighted: a steady
 * source read over ten seconds is far more repeatable than trying to eyeball two
 * fluctuating displays at the same instant.
 */
object LevelProbe {

    /** Equivalent levels over the measurement, in dB relative to full scale. */
    class Result(val laeq: Double, val lceq: Double, val lzeq: Double, val source: String)

    suspend fun measure(
        context: Context,
        seconds: Int,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val settings = Prefs.state.value
        val opened = AudioCapture.open(context)
        val record = opened.record
        val windowSamples = AudioCapture.SAMPLE_RATE // 1 s windows

        // Apply the response curve if one is loaded: this measurement is meant
        // to fix the level of whatever chain is in force, curve included.
        val cal = Prefs.activeCalibration()
        val correction = curveFor(windowSamples, cal)
        val minHz = if (settings.bandLimit) Weighting.MIN_HZ else Weighting.UNLIMITED_HZ
        val analyzer = WindowAnalyzer(
            AudioCapture.SAMPLE_RATE, windowSamples, correction, minHz, settings.removeDc
        )

        var aSum = 0.0
        var cSum = 0.0
        var zSum = 0.0
        var windows = 0

        try {
            record.startRecording()
            val interleaved = ShortArray(4096 * opened.channels)
            val chunk = ShortArray(4096)
            val window = FloatArray(windowSamples)
            var filled = 0

            // Discard the first moment: some devices ramp their gain briefly
            // even on UNPROCESSED, and a settling transient would bias the mean.
            var warmup = AudioCapture.SAMPLE_RATE / 2

            while (windows < seconds) {
                val read = record.read(interleaved, 0, interleaved.size)
                if (read <= 0) continue
                val frames = read / opened.channels
                AudioCapture.takeChannel(
                    interleaved, chunk, frames, opened.channels, opened.channelIndex
                )

                var i = 0
                while (i < frames) {
                    if (warmup > 0) {
                        val skip = minOf(warmup, frames - i)
                        warmup -= skip
                        i += skip
                        continue
                    }
                    val take = minOf(frames - i, windowSamples - filled)
                    for (j in 0 until take) window[filled + j] = chunk[i + j] / 32768f
                    filled += take
                    i += take
                    if (filled == windowSamples) {
                        val r = analyzer.analyze(window, windows.toFloat(), Repair.Stats(0, 0, 0))
                        aSum += 10.0.pow(r.laeqCal / 10.0)
                        cSum += 10.0.pow(r.lceqCal / 10.0)
                        zSum += 10.0.pow(r.lzeqCal / 10.0)
                        windows++
                        filled = 0
                        onProgress(windows.toFloat() / seconds)
                        if (windows >= seconds) break
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }

        val n = maxOf(windows, 1)
        Result(
            laeq = 10.0 * log10(aSum / n),
            lceq = 10.0 * log10(cSum / n),
            lzeq = 10.0 * log10(zSum / n),
            source = opened.description
        )
    }

    private fun curveFor(nFft: Int, cal: Calibration): DoubleArray? {
        if (!cal.hasCurve) return null
        val bins = nFft / 2 + 1
        val binHz = AudioCapture.SAMPLE_RATE.toDouble() / nFft
        return DoubleArray(bins) { k -> 10.0.pow(cal.bandCorrection(k * binHz) / 10.0) }
    }

    /**
     * Sens Factor implied by a reference reading, using the same convention as
     * the Python scripts: `offset = 100 - sens + 24`.
     */
    fun sensFactorFor(referenceSpl: Double, measuredDbfs: Double): Double =
        124.0 - (referenceSpl - measuredDbfs)
}
