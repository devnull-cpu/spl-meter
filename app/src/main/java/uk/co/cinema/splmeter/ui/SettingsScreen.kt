package uk.co.cinema.splmeter.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.cinema.splmeter.audio.Recorder
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.data.SessionStore
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val s by Prefs.state.collectAsState()
    val live by Recorder.state.collectAsState()
    val locked = live.recording

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (locked) {
            Text(
                "Recording in progress — capture settings are locked until it stops.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
        }

        Section("Capture") {
            Text("Microphone source", fontWeight = FontWeight.Medium)
            Text(
                "UNPROCESSED is the raw mic: no AGC, noise suppression or echo cancellation. " +
                    "Auto falls back if it is unavailable; pinning a source reports an error instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.padding(top = 6.dp)) {
                Prefs.Source.entries.forEach { source ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = s.source == source,
                                enabled = !locked,
                                onClick = { Prefs.setSource(source) }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = s.source == source,
                            onClick = { if (!locked) Prefs.setSource(source) },
                            enabled = !locked
                        )
                        Text(source.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (live.audioSource.isNotEmpty()) {
                Text(
                    "Last used: ${live.audioSource}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))
            SwitchRow(
                "Stereo capture",
                "Records stereo and keeps one channel. Mono lets the phone choose or mix " +
                    "microphones. Falls back to mono if stereo is unavailable.",
                s.stereoCapture, enabled = !locked
            ) { Prefs.setStereoCapture(it) }

            if (s.stereoCapture) {
                Spacer(Modifier.height(8.dp))
                Text("Channel to measure", fontWeight = FontWeight.Medium)
                Text(
                    "Which microphone this is depends on the device. Use the one your cal file " +
                        "was made from; the record screen shows which mic is in use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Left", 1 to "Right").forEach { (index, label) ->
                        FilterChip(
                            selected = s.channelIndex == index,
                            onClick = { if (!locked) Prefs.setChannelIndex(index) },
                            enabled = !locked,
                            label = { Text(label) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Band limit to 10 Hz",
                "Ignores content below 10 Hz in A, C and Z, per IEC 61672. Excludes rumble and " +
                    "DC drift, which UNPROCESSED does not filter.",
                s.bandLimit, enabled = !locked
            ) { Prefs.setBandLimit(it) }

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Remove DC offset",
                "Subtracts each window's mean before analysis. Affects peak and clip detection.",
                s.removeDc, enabled = !locked
            ) { Prefs.setRemoveDc(it) }

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Declick",
                "Interpolates across isolated sample spikes, such as handling noise.",
                s.declick, enabled = !locked
            ) { Prefs.setDeclick(it) }

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Declip",
                "Reconstructs clipped peaks. Affects LZpeak; off reports the clipped value.",
                s.declip, enabled = !locked
            ) { Prefs.setDeclip(it) }

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Save raw audio",
                "Also writes a 16-bit WAV. About 330 MB per hour.",
                s.saveRawAudio, enabled = !locked
            ) { Prefs.setSaveRawAudio(it) }

            Spacer(Modifier.height(12.dp))
            Text("Analysis window", fontWeight = FontWeight.Medium)
            Text(
                "FFT length for stored data. 2 s gives 0.5 Hz resolution, 1 s gives 1 Hz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f).forEach { v ->
                    FilterChip(
                        selected = s.windowSeconds == v,
                        onClick = { if (!locked) Prefs.setWindowSeconds(v) },
                        enabled = !locked,
                        label = { Text("${v.toInt()} s") }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Display refresh", fontWeight = FontWeight.Medium)
            Text(
                "How often the on-screen level updates, using Fast (125 ms) weighting. Does not " +
                    "affect stored data. Off follows the analysis window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f to "Off", 0.25f to "0.25 s", 0.5f to "0.5 s", 1f to "1 s").forEach { (v, label) ->
                    FilterChip(
                        selected = s.displaySeconds == v,
                        onClick = { if (!locked) Prefs.setDisplaySeconds(v) },
                        enabled = !locked,
                        label = { Text(label) }
                    )
                }
            }
        }

        Section("Calibration") {
            SwitchRow(
                "Use response curves by default",
                "Correct each band by the mic response in the cal file, as well as the level. " +
                    "Off measures with the Sens Factor only. Each recording can override this " +
                    "on its report.",
                s.applyCalCurve, enabled = true
            ) { Prefs.setApplyCalCurve(it) }

            Spacer(Modifier.height(12.dp))
            Text(
                "Cal files follow the REW convention: the file holds the mic's own response, " +
                    "positive where the mic reads high, and it is subtracted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("Display") {
            Text("Reference level: ${s.referenceDbc.roundToInt()} dBC", fontWeight = FontWeight.Medium)
            Text(
                "Centre of the level bar on the record screen. 85 dBC is Dolby reference.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = s.referenceDbc,
                onValueChange = { Prefs.setReferenceDbc(it.roundToInt().toFloat()) },
                valueRange = 70f..100f,
                steps = 29
            )

            SwitchRow(
                "Keep screen on",
                "Recording continues with the screen off either way.",
                s.keepScreenOn, enabled = true
            ) { Prefs.setKeepScreenOn(it) }
        }

        Section("Storage") {
            Text(
                SessionStore.rootDir().absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "About 1.3 KB per 2 s window; 7 MB for a three-hour film.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) { content() }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
