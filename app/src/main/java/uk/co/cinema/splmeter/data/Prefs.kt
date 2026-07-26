package uk.co.cinema.splmeter.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** App settings, plus the currently selected calibration file. */
object Prefs {

    private lateinit var sp: SharedPreferences
    private lateinit var calDir: File

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state

    private val _cal = MutableStateFlow(CalFile.NONE)
    val cal: StateFlow<CalFile> = _cal

    /**
     * Which microphone source to capture from.
     *
     * This is a setting rather than an internal detail because a calibration is
     * only valid for the source it was measured on — the processing chain in
     * front of VOICE_RECOGNITION or MIC changes both the level and the response.
     * Pinning a source means a failure is reported instead of quietly producing
     * numbers against the wrong calibration.
     */
    enum class Source(val label: String) {
        AUTO("Auto (prefer UNPROCESSED)"),
        UNPROCESSED("UNPROCESSED"),
        VOICE_RECOGNITION("VOICE_RECOGNITION"),
        MIC("MIC")
    }

    data class Settings(
        val saveRawAudio: Boolean = false,
        val windowSeconds: Float = 2f,
        val source: Source = Source.AUTO,
        /**
         * How often the on-screen level refreshes, in seconds. 0 means follow
         * the analysis window, which is the slow, measurement-grade path.
         */
        val displaySeconds: Float = 0.5f,
        /**
         * Capture stereo and keep the left channel rather than asking for mono.
         * Mono lets the HAL choose or mix microphones; a cal file is only valid
         * for the one physical mic it was measured on.
         */
        val stereoLeft: Boolean = true,
        /** Band-limit the weighted sums to 10 Hz and up, per IEC 61672. */
        val bandLimit: Boolean = true,
        /** Subtract each window's mean before analysis. */
        val removeDc: Boolean = true,
        /** Interpolate across isolated sample-level outliers. */
        val declick: Boolean = true,
        /** Reconstruct flat-topped clipped runs. */
        val declip: Boolean = true,
        val calFileName: String? = null,
        val applyCalCurve: Boolean = true,
        val referenceDbc: Float = 85f,
        val keepScreenOn: Boolean = true
    )

    fun init(context: Context) {
        sp = context.getSharedPreferences("splmeter", Context.MODE_PRIVATE)
        calDir = File(context.filesDir, "cal").apply { mkdirs() }
        _state.value = Settings(
            saveRawAudio = sp.getBoolean("saveRawAudio", false),
            windowSeconds = sp.getFloat("windowSeconds", 2f),
            source = runCatching { Source.valueOf(sp.getString("source", null) ?: "AUTO") }
                .getOrDefault(Source.AUTO),
            displaySeconds = sp.getFloat("displaySeconds", 0.5f),
            stereoLeft = sp.getBoolean("stereoLeft", true),
            bandLimit = sp.getBoolean("bandLimit", true),
            removeDc = sp.getBoolean("removeDc", true),
            declick = sp.getBoolean("declick", true),
            declip = sp.getBoolean("declip", true),
            calFileName = sp.getString("calFileName", null),
            applyCalCurve = sp.getBoolean("applyCalCurve", true),
            referenceDbc = sp.getFloat("referenceDbc", 85f),
            keepScreenOn = sp.getBoolean("keepScreenOn", true)
        )
        reloadCal()
    }

    private fun update(block: Settings.() -> Settings) {
        val s = _state.value.block()
        _state.value = s
        sp.edit()
            .putBoolean("saveRawAudio", s.saveRawAudio)
            .putFloat("windowSeconds", s.windowSeconds)
            .putString("source", s.source.name)
            .putFloat("displaySeconds", s.displaySeconds)
            .putBoolean("stereoLeft", s.stereoLeft)
            .putBoolean("bandLimit", s.bandLimit)
            .putBoolean("removeDc", s.removeDc)
            .putBoolean("declick", s.declick)
            .putBoolean("declip", s.declip)
            .putString("calFileName", s.calFileName)
            .putBoolean("applyCalCurve", s.applyCalCurve)
            .putFloat("referenceDbc", s.referenceDbc)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .apply()
    }

    fun setSaveRawAudio(v: Boolean) = update { copy(saveRawAudio = v) }
    fun setWindowSeconds(v: Float) = update { copy(windowSeconds = v) }
    fun setSource(v: Source) = update { copy(source = v) }
    fun setDisplaySeconds(v: Float) = update { copy(displaySeconds = v) }
    fun setStereoLeft(v: Boolean) = update { copy(stereoLeft = v) }
    fun setBandLimit(v: Boolean) = update { copy(bandLimit = v) }
    fun setRemoveDc(v: Boolean) = update { copy(removeDc = v) }
    fun setDeclick(v: Boolean) = update { copy(declick = v) }
    fun setDeclip(v: Boolean) = update { copy(declip = v) }
    fun setApplyCalCurve(v: Boolean) = update { copy(applyCalCurve = v) }
    fun setReferenceDbc(v: Float) = update { copy(referenceDbc = v) }
    fun setKeepScreenOn(v: Boolean) = update { copy(keepScreenOn = v) }

    fun setActiveCal(name: String?) {
        update { copy(calFileName = name) }
        reloadCal()
    }

    fun calFiles(): List<String> =
        calDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    /** Writes a cal file into app storage. Does not change which one is active. */
    fun saveCal(name: String, text: String): String {
        calDir.mkdirs()
        val safe = safeName(name)
        File(calDir, safe).writeText(text)
        return safe
    }

    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "cal.txt" }

    fun calExists(name: String): Boolean = File(calDir, safeName(name)).exists()

    /** Copies an imported cal file into app storage and makes it active. */
    fun importCal(name: String, text: String): CalFile {
        setActiveCal(saveCal(name, text))
        return _cal.value
    }

    fun deleteCal(name: String) {
        File(calDir, name).delete()
        if (_state.value.calFileName == name) setActiveCal(null)
    }

    fun calText(name: String): String? = File(calDir, name).takeIf { it.exists() }?.readText()

    /**
     * Writes a cal file with a measured Sens Factor, keeping the response curve
     * from [basedOn] if it has one.
     *
     * Level and response are independent halves of a calibration: someone who
     * already has a curve should be able to re-level against a meter without
     * throwing the curve away, and someone with only a meter should get a
     * perfectly usable level-only file they can add a curve to later.
     */
    fun writeMeasuredCal(name: String, sensFactor: Double, note: String, basedOn: CalFile?): String {
        val text = buildString {
            append("\"Sens Factor =")
            append(String.format(java.util.Locale.UK, "%.2f", sensFactor))
            append("dB, ").append(note).append("\"\n")
            if (basedOn != null && basedOn.hasCurve) {
                for (i in basedOn.freqs.indices) {
                    append(String.format(java.util.Locale.UK, "%.3f\t%.4f%n",
                        basedOn.freqs[i], basedOn.corrections[i]))
                }
            }
        }
        // Deliberately does not activate anything: writing a new file must not
        // silently re-point the calibration in use.
        return saveCal(name, text)
    }

    private fun reloadCal() {
        val name = _state.value.calFileName
        _cal.value = if (name == null) CalFile.NONE
        else File(calDir, name).takeIf { it.exists() }
            ?.let { CalFile.parse(name, it.readText()) }
            ?: CalFile.NONE
    }

    /** The calibration as it should be applied right now, honouring the toggles. */
    fun activeCalibration(): Calibration = calibrationOf(_cal.value)

    /** Loads any imported cal file by name. */
    fun calByName(name: String?): CalFile {
        if (name == null) return CalFile.NONE
        val f = File(calDir, name)
        return if (f.exists()) CalFile.parse(name, f.readText()) else CalFile.NONE
    }

    /**
     * Resolves the calibration for one recording.
     *
     * @param override null follows whichever cal is active in the app; an empty
     *        string means explicitly uncalibrated; anything else names a cal file.
     *
     * Recordings are pinned by *name*, not by content, so re-importing an
     * improved cal file under the same name still re-calibrates everything that
     * refers to it.
     */
    fun calibrationForSession(
        override: String?,
        embedded: File?,
        applyCurve: Boolean = true
    ): Calibration {
        val file = when {
            override == null -> _cal.value
            override.isEmpty() -> return Calibration.NONE
            override == SessionMeta.EMBEDDED ->
                if (embedded != null && embedded.exists())
                    CalFile.parse("Embedded copy", embedded.readText())
                else return Calibration.NONE
            else -> calByName(override)
        }
        // Level and response are independent: dropping the curve keeps the
        // Sens Factor, which is the half most people can measure.
        return Calibration(
            splOffset = file.splOffset,
            cal = if (applyCurve && _state.value.applyCalCurve) file else CalFile.NONE,
            name = file.name + if (!applyCurve) " (level only)" else ""
        )
    }

    private fun calibrationOf(c: CalFile): Calibration = Calibration(
        splOffset = c.splOffset,
        cal = if (_state.value.applyCalCurve) c else CalFile.NONE,
        name = c.name
    )
}

/** Everything needed to turn stored dBFS values into dB SPL. */
class Calibration(
    val splOffset: Double,
    private val cal: CalFile,
    val name: String
) {
    val hasCurve: Boolean get() = cal.hasCurve

    /** dB to add to a band level at [hz]. The file's convention is its own business. */
    fun bandCorrection(hz: Double): Double = cal.bandCorrection(hz)

    companion object {
        val NONE = Calibration(0.0, CalFile.NONE, "None (uncalibrated)")
    }
}
