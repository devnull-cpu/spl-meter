package uk.co.cinema.splmeter.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import uk.co.cinema.splmeter.dsp.WindowResult
import kotlin.math.log10
import kotlin.math.pow

/**
 * Live state of the capture, shared between the recording service and the UI.
 *
 * SPL values here are already calibrated — the offset in force when the
 * recording started is applied so the on-screen number means something. The
 * numbers written to disk stay uncalibrated.
 */
data class LiveState(
    val recording: Boolean = false,
    val sessionId: String? = null,
    val title: String = "",
    val elapsedSec: Double = 0.0,
    val windows: Int = 0,
    val splA: Float = Float.NaN,
    val splC: Float = Float.NaN,
    val splZ: Float = Float.NaN,
    val leqA: Float = Float.NaN,
    val leqC: Float = Float.NaN,
    val peakHold: Float = Float.NaN,
    val lasMax: Float = Float.NaN,
    val calibrated: Boolean = false,
    val calName: String = "None",
    val audioSource: String = "",
    /** Which physical microphone(s) the capture landed on, e.g. "bottom". */
    val micInfo: String = "",
    val clippingNow: Boolean = false,
    val clippedWindows: Int = 0,
    val savingWav: Boolean = false,
    /** Recent A-weighted SPL values for the mini graph, oldest first. */
    val history: List<Float> = emptyList(),
    val error: String? = null
)

object Recorder {

    const val HISTORY_LENGTH = 180

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    // Raw sums feed the stored summary; cal sums feed the live display.
    private var aPowerSum = 0.0
    private var cPowerSum = 0.0
    private var zPowerSum = 0.0
    private var aCalSum = 0.0
    private var cCalSum = 0.0
    private var count = 0
    private var peakHoldDb = Float.NEGATIVE_INFINITY
    private var lasMaxDb = Float.NEGATIVE_INFINITY
    private var clippedWindows = 0
    private val history = ArrayDeque<Float>()
    /** When true the big number is driven by [DisplayMeter], not by the window. */
    private var fastDisplay = false

    fun reset(
        sessionId: String, title: String, calName: String, calibrated: Boolean,
        source: String, savingWav: Boolean, fastDisplay: Boolean
    ) {
        this.fastDisplay = fastDisplay
        aPowerSum = 0.0; cPowerSum = 0.0; zPowerSum = 0.0
        aCalSum = 0.0; cCalSum = 0.0
        count = 0
        peakHoldDb = Float.NEGATIVE_INFINITY
        lasMaxDb = Float.NEGATIVE_INFINITY
        clippedWindows = 0
        history.clear()
        _state.value = LiveState(
            recording = true,
            sessionId = sessionId,
            title = title,
            calName = calName,
            calibrated = calibrated,
            audioSource = source,
            savingWav = savingWav
        )
    }

    /** @param offset dB to add to the stored (dBFS) values to get SPL. */
    fun onWindow(w: WindowResult, offset: Double, elapsedSec: Double) {
        aPowerSum += 10.0.pow(w.laeq / 10.0)
        cPowerSum += 10.0.pow(w.lceq / 10.0)
        zPowerSum += 10.0.pow(w.lzeq / 10.0)
        aCalSum += 10.0.pow(w.laeqCal / 10.0)
        cCalSum += 10.0.pow(w.lceqCal / 10.0)
        count++

        val splA = (w.laeqCal + offset).toFloat()
        val splC = (w.lceqCal + offset).toFloat()
        val splZ = (w.lzeqCal + offset).toFloat()
        val peak = (w.peak + offset).toFloat()
        val lasMax = (w.lasMax + offset).toFloat()

        if (peak > peakHoldDb) peakHoldDb = peak
        if (lasMax > lasMaxDb) lasMaxDb = lasMax
        if (w.clippedSamples > 0) clippedWindows++

        history.addLast(splA)
        while (history.size > HISTORY_LENGTH) history.removeFirst()

        // update{} is a compare-and-set retry, not a read-then-write: the
        // capture thread publishes fast-meter levels between the two, and a
        // plain copy of an earlier snapshot would put the stale level back.
        _state.update { shown ->
            shown.copy(
            elapsedSec = elapsedSec,
            windows = count,
            // With the fast meter running, leave the on-screen level alone —
            // overwriting it with a 2 s average would make it visibly stutter.
            splA = if (fastDisplay) shown.splA else splA,
            splC = if (fastDisplay) shown.splC else splC,
            splZ = if (fastDisplay) shown.splZ else splZ,
            leqA = (10.0 * log10(aCalSum / count) + offset).toFloat(),
            leqC = (10.0 * log10(cCalSum / count) + offset).toFloat(),
            peakHold = peakHoldDb,
            lasMax = lasMaxDb,
            clippingNow = w.clippedSamples > 0,
            clippedWindows = clippedWindows,
            history = history.toList()
            )
        }
    }

    /** Display-only update from the fast meter. Touches nothing that is stored. */
    fun onDisplay(aDb: Float, cDb: Float, zDb: Float, offset: Double) {
        if (!fastDisplay) return
        _state.update { s ->
            if (!s.recording) s else s.copy(
                splA = (aDb + offset).toFloat(),
                splC = (cDb + offset).toFloat(),
                splZ = (zDb + offset).toFloat()
            )
        }
    }

    fun setMicInfo(info: String) {
        _state.update { it.copy(micInfo = info) }
    }

    fun tick(elapsedSec: Double) {
        _state.update { if (it.recording) it.copy(elapsedSec = elapsedSec) else it }
    }

    fun finish() {
        _state.update { it.copy(recording = false) }
    }

    fun fail(message: String) {
        _state.update { it.copy(recording = false, error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Session-wide energy averages, uncalibrated. */
    fun summary(): Triple<Float, Float, Float> {
        if (count == 0) return Triple(-200f, -200f, -200f)
        return Triple(
            (10.0 * log10(aPowerSum / count)).toFloat(),
            (10.0 * log10(cPowerSum / count)).toFloat(),
            (10.0 * log10(zPowerSum / count)).toFloat()
        )
    }
}
