package uk.co.cinema.splmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.cinema.splmeter.audio.Recorder
import uk.co.cinema.splmeter.audio.RecordingService
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.report.Metrics
import kotlin.math.roundToInt

@Composable
fun RecordScreen(onOpenReport: (String) -> Unit) {
    val context = LocalContext.current
    val state by Recorder.state.collectAsState()
    val settings by Prefs.state.collectAsState()
    val cal by Prefs.cal.collectAsState()
    var title by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (state.error != null) {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Recording failed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    Text(state.error!!, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { Recorder.clearError() }) { Text("Dismiss") }
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            enabled = !state.recording,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        BigLevel(state.splA, if (cal.sensFactor != null) "dBA" else "dBFS (A)")

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Small("C", state.splC)
            Small("Z", state.splZ)
            Small("Leq A", state.leqA)
        }

        Spacer(Modifier.height(16.dp))

        ReferenceBar(state.splC, settings.referenceDbc)

        Spacer(Modifier.height(16.dp))

        Sparkline(state.history)

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Small("LASmax", state.lasMax)
            Small("Peak", state.peakHold)
            Small("Windows", state.windows.toFloat(), decimals = 0)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (state.recording) RecordingService.stop(context)
                else RecordingService.start(context, title)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (state.recording)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors()
        ) {
            Text(
                if (state.recording) "Stop  ·  ${Metrics.formatTime(state.elapsedSec)}" else "Start recording",
                fontSize = 17.sp
            )
        }

        if (!state.recording && state.sessionId != null && state.windows > 0) {
            TextButton(
                onClick = { onOpenReport(state.sessionId!!) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open report for last recording") }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                StatusRow("Calibration", cal.name)
                StatusRow("SPL offset", if (cal.sensFactor != null) "%+.2f dB".format(cal.splOffset) else "none — showing dBFS")
                StatusRow("Response curve", when {
                    !cal.hasCurve -> "none"
                    !settings.applyCalCurve -> "loaded, not applied"
                    cal.legacyInvertedCurve -> "applied (legacy inverted file)"
                    else -> "applied"
                })
                StatusRow("Window", "%.0f s".format(settings.windowSeconds))
                StatusRow("Raw audio", if (settings.saveRawAudio) "saving WAV" else "not saved")
                if (state.audioSource.isNotEmpty()) StatusRow("Source", state.audioSource)
                if (state.micInfo.isNotEmpty()) StatusRow("Microphone", state.micInfo)
                if (state.clippedWindows > 0) {
                    StatusRow("Clipping", "${state.clippedWindows} window(s) repaired")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigLevel(value: Float, unit: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value.isNaN()) "—" else "%.1f".format(value),
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.SansSerif,
            color = levelColour(value)
        )
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Small(label: String, value: Float, decimals: Int = 1) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value.isNaN()) "—" else "%.${decimals}f".format(value),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Level relative to the reference (85 dBC by default), +/-15 dB across the bar. */
@Composable
private fun ReferenceBar(splC: Float, reference: Float) {
    val span = 15f
    val fraction = if (splC.isNaN()) 0f else (((splC - reference) / (2 * span)) + 0.5f).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${(reference - span).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("ref ${reference.roundToInt()} dBC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${(reference + span).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .background(levelColour(splC))
            )
            // Centre tick at the reference level.
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.004f)
                        .height(14.dp)
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }
        }
    }
}

@Composable
private fun Sparkline(history: List<Float>) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surface)
    ) {
        if (history.size < 2) return@Box
        val min = (history.min() - 2f)
        val max = (history.max() + 2f)
        val range = (max - min).coerceAtLeast(1f)
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stepX = size.width / (Recorder.HISTORY_LENGTH - 1).toFloat()
            val offsetIndex = Recorder.HISTORY_LENGTH - history.size
            history.forEachIndexed { i, v ->
                val x = (offsetIndex + i) * stepX
                val y = size.height * (1f - (v - min) / range)
                drawCircle(levelColour(v), radius = 2.2f, center = Offset(x, y))
            }
        }
    }
}
