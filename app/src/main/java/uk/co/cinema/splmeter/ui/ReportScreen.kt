package uk.co.cinema.splmeter.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.data.SessionMeta
import uk.co.cinema.splmeter.data.SessionStore
import uk.co.cinema.splmeter.data.SpectralLog
import uk.co.cinema.splmeter.report.CsvExport
import uk.co.cinema.splmeter.report.Metrics
import uk.co.cinema.splmeter.report.ReportImage
import uk.co.cinema.splmeter.report.ReportGenerator
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReportScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calState by Prefs.cal.collectAsState()
    val prefs by Prefs.state.collectAsState()

    var reportFile by remember { mutableStateOf<File?>(null) }
    var metrics by remember { mutableStateOf<Metrics?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }

    var meta by remember(sessionId) { mutableStateOf(SessionStore.load(sessionId)) }
    val fullDuration = (meta?.durationSec ?: 0.0).toFloat()
    var trimPanelOpen by remember { mutableStateOf(false) }
    var range by remember(sessionId) {
        val end = (meta?.trimEndSec ?: 0.0).takeIf { it > 0.0 } ?: (meta?.durationSec ?: 0.0)
        mutableStateOf((meta?.trimStartSec ?: 0.0).toFloat()..end.toFloat())
    }
    // Applied trim, kept separate from the slider position so that dragging
    // does not rebuild the report on every frame.
    var applied by remember(sessionId) {
        mutableStateOf((meta?.trimStartSec ?: 0.0) to (meta?.trimEndSec ?: 0.0))
    }
    /** Bumped to force a rebuild when nothing else in the key list changed. */
    var rebuild by remember(sessionId) { mutableStateOf(0) }
    var calDialogOpen by remember { mutableStateOf(false) }

    if (calDialogOpen) {
        val options = remember(calState, sessionId) {
            buildList<Pair<String, String?>> {
                if (SessionStore.embeddedCalFile(sessionId).exists()) {
                    add("Embedded copy (recorded with this)" to SessionMeta.EMBEDDED)
                }
                addAll(Prefs.calFiles().map { it to it })
                add("None (uncalibrated)" to "")
                add("Follow app setting" to null)
            }
        }
        AlertDialog(
            onDismissRequest = { calDialogOpen = false },
            title = { Text("Calibration for this recording") },
            text = {
                Column {
                    Text(
                        "Applies to this recording only. The embedded copy was saved when it was " +
                            "recorded; a library file is referenced by name, so replacing that file " +
                            "re-calibrates this recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    options.forEach { (label, value) ->
                        val selected = meta?.calOverride == value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, onClick = {
                                    meta?.let {
                                        val updated = it.copy(calOverride = value)
                                        SessionStore.save(updated)
                                        meta = updated
                                        rebuild++
                                    }
                                })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = meta?.applyCurve ?: true,
                            onCheckedChange = { on ->
                                meta?.let {
                                    val updated = it.copy(applyCurve = on)
                                    SessionStore.save(updated)
                                    meta = updated
                                    rebuild++
                                }
                            }
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Apply its response curve", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Off uses the Sens Factor only: level calibrated, response uncorrected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { calDialogOpen = false }) { Text("Done") } }
        )
    }

    // Regenerate whenever the active calibration or its toggles change — the
    // whole point of storing raw band levels is that this is cheap and lossless.
    LaunchedEffect(sessionId, calState, prefs.applyCalCurve, applied, rebuild) {
        reportFile = null
        failure = null
        runCatching {
            withContext(Dispatchers.IO) {
                val m = meta ?: throw IllegalStateException("Recording metadata missing")
                val full = SpectralLog.read(SessionStore.logFile(sessionId))
                // Scoping only: the log on disk always keeps every window, so a
                // trim can be widened or cleared later with nothing lost.
                val (from, to) = applied
                val log = if (to > from) full.slice(from, to) else full
                val computed = Metrics.compute(
                    log,
                    Prefs.calibrationForSession(
                        m.calOverride, SessionStore.embeddedCalFile(sessionId), m.applyCurve
                    )
                )
                val html = ReportGenerator.generate(context, m, computed)
                val out = SessionStore.reportFile(sessionId)
                out.writeText(html)
                computed to out
            }
        }.onSuccess { (m, f) ->
            metrics = m
            reportFile = f
        }.onFailure { failure = it.message ?: it.javaClass.simpleName }
    }

    Scaffold(
        // MainActivity's Scaffold already consumes the system bar insets; taking
        // them again here inset the report top and bottom, which read as a frame
        // around the page.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // Same reason as contentWindowInsets below: the outer Scaffold
                // has already pushed this screen below the status bar, so the
                // bar's own default inset would sit it lower again.
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(meta?.title ?: sessionId, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { shareOpen = true },
                        enabled = reportFile != null
                    ) { Icon(Icons.Default.Share, contentDescription = "Share report") }
                    DropdownMenu(expanded = shareOpen, onDismissRequest = { shareOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Web page (HTML)") },
                            onClick = {
                                shareOpen = false
                                reportFile?.let { Share.file(context, it, "text/html", "SPL report") }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Image (PNG)") },
                            onClick = {
                                shareOpen = false
                                val m = metrics
                                val md = meta
                                if (m != null && md != null) scope.launch {
                                    val f = withContext(Dispatchers.IO) {
                                        ReportImage.write(
                                            md, m,
                                            File(SessionStore.dir(sessionId), "${sessionId}_report.png")
                                        )
                                    }
                                    Share.file(context, f, "image/png", "SPL report")
                                }
                            }
                        )
                    }

                    IconButton(onClick = { trimPanelOpen = !trimPanelOpen }) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Trim")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Calibration…") },
                            onClick = { menuOpen = false; calDialogOpen = true }
                        )
                        HorizontalDivider()
                        EXPORTS.forEach { (label, build) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                menuOpen = false
                                val m = metrics
                                if (m != null) scope.launch {
                                    val f = withContext(Dispatchers.IO) {
                                        build(m, File(SessionStore.dir(sessionId), fileNameFor(label, sessionId)))
                                    }
                                    Share.file(context, f, Share.mimeFor(f), label)
                                }
                            })
                        }
                        if (meta?.hasWav == true) {
                            DropdownMenuItem(text = { Text("Raw WAV") }, onClick = {
                                menuOpen = false
                                Share.file(context, SessionStore.wavFile(sessionId), "audio/wav", "Raw audio")
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

        if (trimPanelOpen) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {

                    if (fullDuration > 0f) {
                    Text("Analyse a time range", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Analyses only this range. The full log is kept, so this can be changed " +
                            "or cleared at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Metrics.formatTime(range.start.toDouble()) + " – " +
                            Metrics.formatTime(range.endInclusive.toDouble()) + "   (" +
                            Metrics.formatTime((range.endInclusive - range.start).toDouble()) +
                            " of " + Metrics.formatTime(fullDuration.toDouble()) + ")",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        valueRange = 0f..fullDuration
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            applied = range.start.toDouble() to range.endInclusive.toDouble()
                            meta?.let {
                                val updated = it.copy(
                                    trimStartSec = range.start.toDouble(),
                                    trimEndSec = range.endInclusive.toDouble()
                                )
                                SessionStore.save(updated)
                                meta = updated
                            }
                        }) { Text("Apply") }
                        TextButton(onClick = {
                            range = 0f..fullDuration
                            applied = 0.0 to 0.0
                            meta?.let {
                                val updated = it.copy(trimStartSec = 0.0, trimEndSec = 0.0)
                                SessionStore.save(updated)
                                meta = updated
                            }
                        }) { Text("Reset to full") }
                    }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                failure != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Could not build the report", style = MaterialTheme.typography.titleSmall)
                    Text(failure!!, style = MaterialTheme.typography.bodySmall)
                }
                reportFile == null -> CircularProgressIndicator()
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            getSettings().javaScriptEnabled = true
                            getSettings().allowFileAccess = true
                            setBackgroundColor(0xFF0A0A0B.toInt())
                        }
                    },
                    update = { it.loadUrl("file://${reportFile!!.absolutePath}") }
                )
            }
        }
        }
    }
}

private val EXPORTS: List<Pair<String, (Metrics, File) -> File>> = listOf(
    "Metrics CSV" to { m: Metrics, f: File -> CsvExport.metrics(m, f) },
    "Spectrum CSV" to { m: Metrics, f: File -> CsvExport.spectrum(m, f) },
    "Average spectrum CSV" to { m: Metrics, f: File -> CsvExport.averageSpectrum(m, f) }
)

private fun fileNameFor(label: String, id: String) = when (label) {
    "Metrics CSV" -> "${id}_metrics.csv"
    "Spectrum CSV" -> "${id}_spectrum.csv"
    else -> "${id}_avg_spectrum.csv"
}
