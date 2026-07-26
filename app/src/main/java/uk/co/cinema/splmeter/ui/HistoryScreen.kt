package uk.co.cinema.splmeter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import uk.co.cinema.splmeter.audio.Recorder
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.data.SessionMeta
import uk.co.cinema.splmeter.data.SessionStore
import uk.co.cinema.splmeter.report.Metrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val live by Recorder.state.collectAsState()
    val cal by Prefs.cal.collectAsState()
    // Re-read the list whenever a recording finishes.
    var refresh by remember { mutableStateOf(0) }
    val sessions = remember(refresh, live.recording) { SessionStore.list() }
    var confirmDelete by remember { mutableStateOf<SessionMeta?>(null) }

    if (sessions.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recordings are stored in Android/data/uk.co.cinema.splmeter/files/sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(sessions, key = { it.id }) { s ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onOpen(s.id) }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(Date(s.startEpochMillis)) +
                                " · " + Metrics.formatTime(s.durationSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val offset = cal.splOffset
                        val unit = if (cal.sensFactor != null) "dBA" else "dBFS"
                        Text(
                            "Leq %.1f %s · peak %.1f · %s".format(
                                s.leqA + offset, unit, s.lzPeak + offset,
                                if (s.hasWav) "WAV" else "log only"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (s.clippedWindows > 0) {
                            Text(
                                "${s.clippedWindows} clipped window(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    IconButton(onClick = { confirmDelete = s }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("${s.title}\nThis removes the spectral log${if (s.hasWav) ", the WAV" else ""} and any exports.") },
            confirmButton = {
                TextButton(onClick = {
                    SessionStore.delete(s.id)
                    confirmDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}
