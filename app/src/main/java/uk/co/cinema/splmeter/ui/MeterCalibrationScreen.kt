package uk.co.cinema.splmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.co.cinema.splmeter.audio.LevelProbe
import uk.co.cinema.splmeter.data.Prefs
import java.util.Locale
import kotlin.math.abs

private const val MEASURE_SECONDS = 10

/**
 * Guided level calibration against a separate sound level meter.
 *
 * Level and frequency response are independent halves of a calibration, and only
 * the response half genuinely needs a measurement microphone. This walks through
 * the level half, which anyone with a cheap meter can do, and is the difference
 * between reading dBFS and reading dB SPL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterCalibrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeCal by Prefs.cal.collectAsState()

    var step by remember { mutableStateOf(1) }
    var measuring by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var measured by remember { mutableStateOf<LevelProbe.Result?>(null) }
    var refA by remember { mutableStateOf("") }
    var refC by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var savedAs by remember { mutableStateOf<String?>(null) }
    var includeCurve by remember { mutableStateOf(true) }
    var makeActive by remember { mutableStateOf(false) }

    val a = refA.trim().toDoubleOrNull()
    val c = refC.trim().toDoubleOrNull()
    val m = measured
    val offsetA = if (m != null && a != null) a - m.laeq else null
    val offsetC = if (m != null && c != null) c - m.lceq else null
    // C is the better single reading to trust: it is nearly flat across the
    // range where a phone mic behaves, whereas A leans on 1-5 kHz, which is
    // exactly where phones are worst.
    val offset = offsetC ?: offsetA
    val sens = offset?.let { 124.0 - it }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Calibrate level · step $step of 4") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (step) {

                1 -> {
                    StepHeading("What you need")
                    Body(
                        "A sound level meter, and something steady and broadband to play. Pink " +
                            "noise is ideal. Avoid music: the level moves too much."
                    )
                    Spacer(Modifier.height(12.dp))
                    StepHeading("What this does")
                    Body(
                        "Sets the Sens Factor, which converts dBFS to dB SPL. It does not measure " +
                            "frequency response — that needs a measurement mic."
                    )
                    if (activeCal.hasCurve) {
                        Spacer(Modifier.height(8.dp))
                        Body(
                            "${activeCal.name} has a response curve. You can copy it into the new " +
                                "file at the end. That file is not modified.",
                            colour = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start")
                    }
                }

                2 -> {
                    StepHeading("Measure")
                    Body(
                        "Hold the meter's microphone next to the phone's bottom microphone and " +
                            "keep both still. Note what the meter settles on."
                    )
                    Spacer(Modifier.height(16.dp))

                    if (measuring) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(progress * MEASURE_SECONDS).toInt()} / $MEASURE_SECONDS s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Button(
                            onClick = {
                                failure = null
                                measuring = true
                                progress = 0f
                                scope.launch {
                                    runCatching {
                                        LevelProbe.measure(context, MEASURE_SECONDS) { progress = it }
                                    }.onSuccess { measured = it; step = 3 }
                                        .onFailure { failure = it.message ?: "Measurement failed" }
                                    measuring = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (measured == null) "Measure for $MEASURE_SECONDS s" else "Measure again") }
                    }

                    failure?.let {
                        Spacer(Modifier.height(8.dp))
                        Body(it, colour = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { step = 1 }) { Text("Back") }
                }

                3 -> {
                    StepHeading("What did the meter read?")
                    Body(
                        "Either one will do. C is used when both are given, being less sensitive " +
                            "to a phone's response errors. Entering both also gives an accuracy " +
                            "estimate."
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = refA, onValueChange = { refA = it },
                            label = { Text("dBA") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = refC, onValueChange = { refC = it },
                            label = { Text("dBC") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    m?.let {
                        Spacer(Modifier.height(12.dp))
                        Body(
                            "The phone measured %.1f dBFS(A), %.1f dBFS(C), %.1f dBFS(Z) via %s."
                                .format(Locale.UK, it.laeq, it.lceq, it.lzeq, it.source)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = 2 }) { Text("Measure again") }
                        Button(
                            onClick = {
                                fileName = if (activeCal.hasCurve) "relevelled_${activeCal.name}"
                                else "meter_cal.txt"
                                step = 4
                            },
                            enabled = offset != null
                        ) { Text("Continue") }
                    }
                    if (offset == null) {
                        Spacer(Modifier.height(8.dp))
                        Body("Enter at least one reading to continue.")
                    }
                }

                4 -> {
                    StepHeading("Result")
                    if (offset != null && sens != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "%.2f dB".format(Locale.UK, sens),
                                    fontSize = 30.sp, fontWeight = FontWeight.Light
                                )
                                Text(
                                    "Sens Factor  ·  SPL offset %+.2f dB".format(Locale.UK, offset),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (offsetA != null && offsetC != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Taken from the C reading.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (offsetA != null && offsetC != null) {
                            val gap = abs(offsetA - offsetC)
                            Spacer(Modifier.height(10.dp))
                            StepHeading("How much to trust it")
                            Body(
                                when {
                                    gap < 1.0 -> "A and C agree to %.1f dB, so your phone is effectively flat across this signal. A level-only calibration is sound."
                                        .format(Locale.UK, gap)
                                    gap < 3.0 -> "A and C disagree by %.1f dB. Usable, but expect roughly that much error on A-weighted readings."
                                        .format(Locale.UK, gap)
                                    else -> "A and C disagree by %.1f dB. Your phone's response is far from flat: C and Z will be reasonable, A noticeably off until you add a response curve."
                                        .format(Locale.UK, gap)
                                },
                                colour = if (gap < 3.0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.secondary
                            )
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Body(
                                "Entering both dBA and dBC would give an accuracy estimate."
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        StepHeading("Save as a new cal file")
                        Body(
                            "Writes a new file. Nothing existing is modified."
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("File name") },
                            singleLine = true,
                            isError = Prefs.calExists(fileName),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (Prefs.calExists(fileName)) {
                            Body(
                                "A file called ${Prefs.safeName(fileName)} already exists and would " +
                                    "be overwritten. Choose another name.",
                                colour = MaterialTheme.colorScheme.error
                            )
                        }

                        if (activeCal.hasCurve) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = includeCurve, onCheckedChange = { includeCurve = it })
                                Spacer(Modifier.height(0.dp))
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text("Copy the response curve from ${activeCal.name}",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Body("Off writes a level-only file.")
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = makeActive, onCheckedChange = { makeActive = it })
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("Make it the active calibration",
                                    style = MaterialTheme.typography.bodyMedium)
                                Body("Off saves it without selecting it.")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = 3 }) { Text("Back") }
                            Button(
                                onClick = {
                                    runCatching {
                                        val keep = activeCal.hasCurve && includeCurve
                                        val note = buildString {
                                            append("Level measured against a sound level meter")
                                            if (offsetA != null && offsetC != null) {
                                                append(", A/C agreed to %.1f dB"
                                                    .format(Locale.UK, abs(offsetA - offsetC)))
                                            }
                                            if (keep) append(", response curve from ${activeCal.name}")
                                        }
                                        val written = Prefs.writeMeasuredCal(
                                            fileName.ifBlank { "meter_cal.txt" },
                                            sens, note, activeCal.takeIf { keep }
                                        )
                                        if (makeActive) Prefs.setActiveCal(written)
                                        savedAs = written
                                    }.onFailure { failure = it.message ?: "Could not save" }
                                },
                                enabled = savedAs == null && !Prefs.calExists(fileName)
                            ) { Text(if (savedAs == null) "Save" else "Saved") }
                        }
                        savedAs?.let {
                            Spacer(Modifier.height(10.dp))
                            Body(
                                if (makeActive) "Saved as $it and made the active calibration."
                                else "Saved as $it. Nothing else changed — select it on the Cal screen to use it.",
                                colour = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                        }
                        failure?.let {
                            Spacer(Modifier.height(8.dp))
                            Body(it, colour = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StepHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Body(text: String, colour: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = colour)
}
