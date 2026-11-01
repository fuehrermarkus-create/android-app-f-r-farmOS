package at.hof.farmos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.LogKind
import at.hof.farmos.data.db.urlList

fun kindLabel(kind: String) = when (kind) {
    "structure" -> "Gebäude"
    "plant" -> "Kultur"
    else -> "Fläche"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationListScreen(vm: MainViewModel, onOpen: (String) -> Unit, onPhoto: () -> Unit) {
    val locations by vm.locations.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = locations.filter {
        (kindFilter == null || it.kind == kindFilter) &&
            (query.isBlank() || it.name.contains(query, true) || it.subtype?.contains(query, true) == true)
    }

    ScreenScaffold(
        title = "Flächen & Kulturen",
        actions = {
            IconButton(onClick = onPhoto) { Icon(Icons.Default.CameraAlt, "Foto aufnehmen") }
            IconButton(onClick = { vm.sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Synchronisieren") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SearchField(query) { query = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Alle", "land" to "Flächen", "structure" to "Gebäude", "plant" to "Kulturen")
                        .forEach { (k, label) ->
                            FilterChip(kindFilter == k, { kindFilter = k }, { Text(label) })
                        }
                }
            }
            if (shown.isEmpty()) {
                EmptyHint(
                    if (locations.isEmpty()) "Nichts geladen.\nFlächen und Kulturen legst du in farmOS " +
                        "am PC an (mit Karte), danach synchronisieren." else "Keine Treffer",
                    Icons.Default.Landscape,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { l ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(l.id) },
                            headlineContent = { Text(l.name) },
                            supportingContent = {
                                Text(listOfNotNull(kindLabel(l.kind), l.subtype, l.areaInfo, l.locationNames)
                                    .joinToString(" · "))
                            },
                            leadingContent = {
                                Icon(
                                    when (l.kind) {
                                        "structure" -> Icons.Default.Warehouse
                                        "plant" -> Icons.Default.Grass
                                        else -> Icons.Default.Landscape
                                    }, null,
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Detailseite für Fläche, Gebäude oder Kultur.
 * onQuickLog(Art, mitKamera, istKultur)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationDetailScreen(
    vm: MainViewModel,
    id: String,
    onBack: () -> Unit,
    onQuickLog: (LogKind, Boolean, Boolean) -> Unit,
) {
    val location by remember(id) { vm.location(id) }.collectAsState(initial = null)
    val logs by remember(id) { vm.logsFor(id) }.collectAsState(initial = emptyList())
    val animals by vm.animals.collectAsState()
    val l = location

    ScreenScaffold(title = l?.name ?: "Fläche", onBack = onBack) { pad ->
        if (l == null) {
            EmptyHint("Nicht gefunden"); return@ScreenScaffold
        }
        val isPlant = l.kind == "plant"
        val here = if (isPlant) emptyList() else
            animals.filter { a -> a.locationNames?.split(", ")?.contains(l.name) == true }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Art", kindLabel(l.kind))
                        InfoRow(if (isPlant) "Kulturart" else "Typ", l.subtype)
                        InfoRow("Größe", l.areaInfo)
                        InfoRow("Standort", l.locationNames)
                        InfoRow("Tiere hier", if (here.isEmpty()) null else here.joinToString(", ") { it.name })
                        InfoRow("Notizen", l.notes)
                    }
                }
            }
            item {
                PhotoSection(l.imageUrls.urlList() + logs.flatMap { it.imageUrls.urlList() })
            }
            item {
                Text("Schnell erfassen", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { onQuickLog(LogKind.OBSERVATION, true, isPlant) },
                        label = { Text("Foto") },
                        leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                    )
                    val kinds = if (isPlant) listOf(LogKind.OBSERVATION, LogKind.ACTIVITY, LogKind.INPUT, LogKind.HARVEST)
                    else listOf(LogKind.OBSERVATION, LogKind.ACTIVITY, LogKind.INPUT, LogKind.HARVEST, LogKind.MOVEMENT)
                    kinds.forEach { k ->
                        AssistChip(onClick = { onQuickLog(k, false, isPlant) },
                            label = { Text(if (k == LogKind.MOVEMENT) "Tiere hierher" else k.label) })
                    }
                }
            }
            item {
                Text("Verlauf (${logs.size})", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            if (logs.isEmpty()) item {
                Text("Noch keine Einträge", color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp))
            }
            items(logs, key = { it.id }) { LogRow(it) }
        }
    }
}
