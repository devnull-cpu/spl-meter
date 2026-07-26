package uk.co.cinema.splmeter.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recording on disk.
 *
 * Everything lives under `Android/data/uk.co.cinema.splmeter/files/sessions/<id>/`
 * so it survives an uninstall-free reinstall, is visible over USB/MTP, and needs
 * no storage permission.
 */
data class SessionMeta(
    val id: String,
    val title: String,
    val startEpochMillis: Long,
    val durationSec: Double,
    val sampleRate: Int,
    val windowSamples: Int,
    val windowCount: Int,
    val calNameAtRecord: String,
    /** The microphone source actually used — a calibration is only valid for one. */
    val audioSource: String,
    /**
     * Which cal file to analyse this recording with. A calibration belongs to
     * the recording, not to the app: a phone file and a UMIK file should not
     * fight over one global setting, and changing cal must not silently
     * reinterpret every past measurement.
     *
     * [EMBEDDED] uses the copy stored beside the recording, null follows the
     * app's active cal, "" is explicitly uncalibrated, anything else names a
     * file in the app's cal library.
     */
    val calOverride: String? = null,
    /**
     * Whether to apply the chosen cal file's response curve, as opposed to just
     * its Sens Factor. Per recording for the same reason the file itself is:
     * a curve measured for one mic and source does not apply to another, and
     * you may want the level from a file without trusting its curve.
     */
    val applyCurve: Boolean = true,
    val hasWav: Boolean,
    val clippedWindows: Int,
    /** Windows lost to analysis overload, and frames lost by the driver. Both should be 0. */
    val droppedWindows: Int = 0,
    val missedFrames: Long = 0,
    /** Analyse only this range, in seconds. Non-destructive — the log keeps everything. */
    val trimStartSec: Double = 0.0,
    /** Exclusive end of the analysed range; 0 means "to the end". */
    val trimEndSec: Double = 0.0,
    // Summary metrics, uncalibrated (dBFS). Calibration is added on display.
    val leqA: Float = -200f,
    val leqC: Float = -200f,
    val leqZ: Float = -200f,
    val lasMax: Float = -200f,
    val lasMin: Float = -200f,
    val lcsMax: Float = -200f,
    val lcsMin: Float = -200f,
    val lzPeak: Float = -200f
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("title", title)
        put("startEpochMillis", startEpochMillis)
        put("durationSec", durationSec)
        put("sampleRate", sampleRate); put("windowSamples", windowSamples)
        put("windowCount", windowCount)
        put("calNameAtRecord", calNameAtRecord)
        put("audioSource", audioSource)
        put("calOverride", calOverride ?: JSONObject.NULL)
        put("applyCurve", applyCurve)
        put("hasWav", hasWav); put("clippedWindows", clippedWindows)
        put("trimStartSec", trimStartSec); put("trimEndSec", trimEndSec)
        put("droppedWindows", droppedWindows); put("missedFrames", missedFrames)
        put("leqA", leqA.toDouble()); put("leqC", leqC.toDouble()); put("leqZ", leqZ.toDouble())
        put("lasMax", lasMax.toDouble()); put("lasMin", lasMin.toDouble())
        put("lcsMax", lcsMax.toDouble()); put("lcsMin", lcsMin.toDouble())
        put("lzPeak", lzPeak.toDouble())
    }.toString(2)

    companion object {
        /** Use the cal file copied into the session folder at record time. */
        const val EMBEDDED = "@embedded"

        fun fromJson(text: String): SessionMeta {
            val o = JSONObject(text)
            return SessionMeta(
                id = o.getString("id"),
                title = o.optString("title", o.getString("id")),
                startEpochMillis = o.optLong("startEpochMillis"),
                durationSec = o.optDouble("durationSec", 0.0),
                sampleRate = o.optInt("sampleRate", 48000),
                windowSamples = o.optInt("windowSamples", 96000),
                windowCount = o.optInt("windowCount", 0),
                calNameAtRecord = o.optString("calNameAtRecord", "None"),
                audioSource = o.optString("audioSource", "unknown"),
                calOverride = if (o.isNull("calOverride")) null else o.optString("calOverride"),
                applyCurve = o.optBoolean("applyCurve", true),
                hasWav = o.optBoolean("hasWav", false),
                clippedWindows = o.optInt("clippedWindows", 0),
                droppedWindows = o.optInt("droppedWindows", 0),
                missedFrames = o.optLong("missedFrames", 0L),
                trimStartSec = o.optDouble("trimStartSec", 0.0),
                trimEndSec = o.optDouble("trimEndSec", 0.0),
                leqA = o.optDouble("leqA", -200.0).toFloat(),
                leqC = o.optDouble("leqC", -200.0).toFloat(),
                leqZ = o.optDouble("leqZ", -200.0).toFloat(),
                lasMax = o.optDouble("lasMax", -200.0).toFloat(),
                lasMin = o.optDouble("lasMin", -200.0).toFloat(),
                lcsMax = o.optDouble("lcsMax", -200.0).toFloat(),
                lcsMin = o.optDouble("lcsMin", -200.0).toFloat(),
                lzPeak = o.optDouble("lzPeak", -200.0).toFloat()
            )
        }
    }
}

object SessionStore {

    private lateinit var root: File

    fun init(context: Context) {
        root = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
        root.mkdirs()
    }

    fun rootDir(): File = root

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun logFile(id: String) = File(dir(id), "spectrum.splog")
    fun wavFile(id: String) = File(dir(id), "audio.wav")
    fun metaFile(id: String) = File(dir(id), "meta.json")
    fun reportFile(id: String) = File(dir(id), "report.html")

    /**
     * A copy of the calibration in force when the recording was made.
     *
     * Written at record time so a session folder is self-describing: copy it to
     * a PC or hand it to someone else and the calibration travels with it,
     * regardless of what the app's cal library looks like later.
     */
    fun embeddedCalFile(id: String) = File(dir(id), "calibration.txt")

    fun newId(at: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.UK).format(at)

    fun save(meta: SessionMeta) {
        metaFile(meta.id).writeText(meta.toJson())
    }

    fun load(id: String): SessionMeta? = runCatching {
        SessionMeta.fromJson(metaFile(id).readText())
    }.getOrNull()

    fun list(): List<SessionMeta> =
        root.listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { load(it.name) }
            ?.sortedByDescending { it.startEpochMillis }
            ?: emptyList()

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun rename(id: String, title: String) {
        load(id)?.let { save(it.copy(title = title)) }
    }
}
