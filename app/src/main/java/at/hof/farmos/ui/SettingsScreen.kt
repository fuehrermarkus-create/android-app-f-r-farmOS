package at.hof.farmos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ops by vm.pendingOps.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val diag by vm.diag.collectAsState()
    val diagRunning by vm.diagRunning.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    val open = ops.count { !it.failed }
    val failed = ops.filter { it.failed }

    ScreenScaffold(title = "Sync & Einstellungen") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("Server", vm.prefs.serverUrl)
                        InfoRow("Benutzer", vm.prefs.username)
                        InfoRow("Letzter Abgleich", if (lastSync > 0) formatDateTime(lastSync / 1000) else "noch nie")
                        InfoRow("Wartend", "$open Änderung(en)")
                        if (lastError != null) {
                            Text(
                                "Letzter Fehler: $lastError",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.sync() }, enabled = !syncing) {
                                Text(if (syncing) "Läuft …" else "Jetzt synchronisieren")
                            }
                            OutlinedButton(onClick = { confirmLogout = true }) { Text("Abmelden") }
                        }
                        OutlinedButton(onClick = { vm.runDiagnose() }, enabled = !diagRunning) {
                            Text(if (diagRunning) "Teste …" else "Verbindung testen")
                        }
                    }
                }
            }

            diag?.let { results ->
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Verbindungstest", style = MaterialTheme.typography.titleSmall)
                            results.forEach { r ->
                                Row {
                                    Text(if (r.ok) "✅ " else "❌ ")
                                    Column {
                                        Text(r.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            r.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (r.ok) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (ops.isEmpty()) item {
                ListItem(
                    headlineContent = { Text("Alles übertragen") },
                    leadingContent = { Icon(Icons.Default.CloudDone, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }

            if (failed.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Vom Server abgelehnt (${failed.size})", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.retryFailed() }) { Text("Erneut versuchen") }
                }
            }

            items(ops, key = { it.seq }) { op ->
                ListItem(
                    headlineContent = { Text(op.description) },
                    supportingContent = {
                        Column {
                            Text(formatDateTime(op.createdAt / 1000))
                            if (op.lastError != null) Text(op.lastError, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    leadingContent = {
                        if (op.failed) Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        else Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.secondary)
                    },
                    trailingContent = if (op.failed) {
                        { TextButton(onClick = { vm.discard(op) }) { Text("Verwerfen") } }
                    } else null,
                )
                HorizontalDivider()
            }

            item {
                Text(
                    "Von unterwegs: Tailscale auf Server und Handy installieren und hier die " +
                        "Tailscale-Adresse des Servers verwenden (Abmelden → neue Adresse eingeben). " +
                        "Offline erfasste Einträge werden automatisch übertragen, sobald der Server erreichbar ist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Abmelden?") },
            text = {
                Text(
                    if (open > 0) "Achtung: $open Änderung(en) sind noch nicht übertragen und gehen verloren."
                    else "Lokale Daten werden gelöscht und beim nächsten Anmelden neu geladen."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Abmelden") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Abbrechen") } },
        )
    }
}
