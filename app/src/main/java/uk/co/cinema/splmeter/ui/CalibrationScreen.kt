package uk.co.cinema.splmeter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.co.cinema.splmeter.data.CalFile
import uk.co.cinema.splmeter.data.Prefs

@Composable
fun CalibrationScreen(onCalibrateLevel: () -> Unit = {}) {
    val context = LocalContext.current
    val settings by Prefs.state.collectAsState()
    val cal by Prefs.cal.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    val files = remember(refresh, settings.calFileName) { Prefs.calFiles() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = displayName(context, uri)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val parsed = CalFile.parse(name, text)
            require(parsed.sensFactor != null || parsed.hasCurve) {
                "No Sens Factor and no frequency points found — is this a cal file?"
            }
            Prefs.importCal(name, text)
        }.onFailure { importError = it.message ?: "Could not read that file" }
            .onSuccess { importError = null; refresh++ }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Calibration", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(cal.name, fontWeight = FontWeight.Medium)
                Text(cal.summary(), style = MaterialTheme.typography.bodySmall)
                if (cal.sensFactor == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No Sens Factor, so levels are shown in dBFS. A cal file can be applied " +
                            "to past recordings at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(onClick = { picker.launch(arrayOf("text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import cal file")
        }
        importError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                CalRow(
                    name = "None (uncalibrated)",
                    selected = settings.calFileName == null,
                    onSelect = { Prefs.setActiveCal(null) },
                    onDelete = null
                )
                files.forEach { name ->
                    CalRow(
                        name = name,
                        selected = settings.calFileName == name,
                        onSelect = { Prefs.setActiveCal(name) },
                        onDelete = { Prefs.deleteCal(name); refresh++ }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("No measurement mic?", fontWeight = FontWeight.Medium)
                Text(
                    "Sets the Sens Factor using any sound level meter. The response curve still " +
                        "needs a measurement mic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onCalibrateLevel, modifier = Modifier.fillMaxWidth()) {
                    Text("Calibrate level with a meter")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Making a cal file", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Record pink noise on this phone and a measurement mic at the same time, then on the PC:\n\n" +
                        "python calibrate_phone.py --ref reference.wav --phone phone.wav \\\n" +
                        "    --cal-file reference_mic.txt --output phone_cal.txt\n\n" +
                        "Copy phone_cal.txt to the phone and import it here. The Sens Factor line sets the " +
                        "absolute level; the frequency points correct the mic's response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The file's real name, from the content provider.
 *
 * A document URI's last path segment is an opaque id, not a filename — picking
 * from Downloads gives something like `msf:1234`, which was being saved and
 * shown as "1234".
 */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String {
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.plus(".txt")
        ?: "cal.txt"
}

@Composable
private fun CalRow(name: String, selected: Boolean, onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(0.dp))
        }
        Text(
            name,
            modifier = Modifier.weight(1f).padding(start = if (selected) 8.dp else 32.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}
