package at.hof.farmos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Compost
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.BLUEH_FLAG
import at.hof.farmos.data.FieldWork
import at.hof.farmos.data.LogKind
import at.hof.farmos.data.db.LocationEntity
import at.hof.farmos.data.db.urlList

fun kindLabel(kind: String) = when (kind) {
    "structure" -> "Gebäude"
    "plant" -> "Kultur"
    else -> "Fläche"
}

fun LocationEntity.isBlueh(): Boolean = BLUEH_FLAG in flagList()

/** Kulturen, die laut farmOS auf dieser Fläche stehen. */
fun cropsOn(field: LocationEntity, all: List<LocationEntity>): List<LocationEntity> =
    all.filter { it.kind == "plant" && field.id in it.locationIds.split(",") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationListScreen(vm: MainViewModel, onOpen: (String) -> Unit, onPhoto: () -> Unit) {
    val locations by vm.locations.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = locations.filter {
        val kindOk = when (kindFilter) {
            null -> true
            "blueh" -> it.isBlueh()
            else -> it.kind == kindFilter
        }
        kindOk && (query.isBlank() || it.name.contains(query, true) || it.subtype?.contains(query, true) == true)
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
                    listOf(
                        null to "Alle", "land" to "Flächen", "structure" to "Gebäude",
                        "plant" to "Kulturen", "blueh" to "🌼 Blühflächen",
                    ).forEach { (k, label) -> FilterChip(kindFilter == k, { kindFilter = k }, { Text(label) }) }
                }
            }
            if (shown.isEmpty()) {
                EmptyHint(
                    if (locations.isEmpty()) "Nichts geladen.\nFlächen legst du in farmOS an (oder importierst sie), " +
                        "danach synchronisieren." else "Keine Treffer",
                    Icons.Default.Landscape,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { l ->
                        val crops = if (l.kind == "land") cropsOn(l, locations).map { it.subtype ?: it.name } else emptyList()
                        ListItem(
                            modifier = Modifier.clickable { onOpen(l.id) },
                            headlineContent = { Text((if (l.isBlueh()) "🌼 " else "") + l.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        kindLabel(l.kind), l.subtype, l.areaInfo, l.locationNames,
                                        crops.distinct().joinToString(", ").ifBlank { null },
                                    ).joinToString(" · ")
                                )
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

private class LocAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Detailseite für Fläche, Gebäude oder Kultur – mit den Arbeiten, die dort passen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationDetailScreen(
    vm: MainViewModel,
    id: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onFieldWork: (FieldWork, String?, List<String>) -> Unit,
    onQuickLog: (LogKind, Boolean, Boolean) -> Unit,
) {
    val location by remember(id) { vm.location(id) }.collectAsState(initial = null)
    val logs by remember(id) { vm.logsFor(id) }.collectAsState(initial = emptyList())
    val animals by vm.animals.collectAsState()
    val all by vm.locations.collectAsState()
    val flags by vm.flags.collectAsState()
    val l = location

    ScreenScaffold(title = l?.name ?: "Fläche", onBack = onBack) { pad ->
        if (l == null) {
            EmptyHint("Nicht gefunden"); return@ScreenScaffold
        }
        val isPlant = l.kind == "plant"
        val isLand = l.kind == "land"
        val crops = if (isLand) cropsOn(l, all) else emptyList()
        val here = if (isPlant) emptyList() else
            animals.filter { a -> a.locationNames?.split(", ")?.contains(l.name) == true }
        // Steht die Kultur auf einem Feld? Dann gehen Feldarbeiten dort hin.
        val plantField = if (isPlant) l.locationIds.split(",").firstOrNull { it.isNotBlank() } else null
        val flagMissing = flags.isNotEmpty() && flags.none { it.id == "flag:$BLUEH_FLAG" }

        val actions = buildList {
            if (isLand) {
                add(LocAction("Aussaat", Icons.Default.Grass) { onFieldWork(FieldWork.SEEDING, l.id, emptyList()) })
                add(LocAction("Pflanzenschutz", Icons.Default.WaterDrop) { onFieldWork(FieldWork.SPRAYING, l.id, emptyList()) })
                add(LocAction("Düngung", Icons.Default.Compost) { onFieldWork(FieldWork.FERTILIZING, l.id, emptyList()) })
                add(LocAction("Ernte", Icons.Default.Agriculture) { onFieldWork(FieldWork.HARVEST, l.id, emptyList()) })
            }
            if (isPlant) {
                add(LocAction("Pflanzenschutz", Icons.Default.WaterDrop) { onFieldWork(FieldWork.SPRAYING, plantField, listOf(l.id)) })
                add(LocAction("Düngung", Icons.Default.Compost) { onFieldWork(FieldWork.FERTILIZING, plantField, listOf(l.id)) })
                add(LocAction("Ernte", Icons.Default.Agriculture) { onFieldWork(FieldWork.HARVEST, plantField, listOf(l.id)) })
            }
            add(LocAction("Foto", Icons.Default.CameraAlt) { onQuickLog(LogKind.OBSERVATION, true, isPlant) })
            add(LocAction("Beobachtung", Icons.Default.Visibility) { onQuickLog(LogKind.OBSERVATION, false, isPlant) })
            if (l.kind == "structure") {
                add(LocAction("Tiere hierher", Icons.Default.Warehouse) { onQuickLog(LogKind.MOVEMENT, false, false) })
            }
        }

        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Art", kindLabel(l.kind))
                        InfoRow(if (isPlant) "Kulturart" else "Typ", l.subtype)
                        InfoRow("Größe", l.areaInfo)
                        InfoRow("Steht auf", l.locationNames)
                        InfoRow("Tiere hier", if (here.isEmpty()) null else here.joinToString(", ") { it.name })
                        InfoRow("Notizen", l.notes)
                    }
                }
            }
            if (isLand) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🌼 Blühfläche", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Markiert die Fläche in farmOS als Blühfläche.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Switch(checked = l.isBlueh(), onCheckedChange = { vm.setBluehflaeche(l, it) })
                        }
                        if (flagMissing) {
                            Text(
                                "Hinweis: In farmOS ist die Markierung „Blühfläche“ noch nicht angelegt – " +
                                    "siehe Anleitung, sonst lehnt der Server die Änderung ab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (isLand) item {
                Text("Kulturen auf dieser Fläche", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                if (crops.isEmpty()) {
                    Text("Keine – über „Aussaat“ erfassen.", color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(crops, key = { "crop-" + it.id }) { c ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(c.id) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { c.subtype?.let { Text(it) } },
                    leadingContent = { Icon(Icons.Default.Grass, null) },
                )
            }
            item {
                Text("Erfassen", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions.forEach { a ->
                        AssistChip(onClick = a.onClick, label = { Text(a.label) },
                            leadingIcon = { Icon(a.icon, null) })
                    }
                }
            }
            item {
                PhotoSection(l.imageUrls.urlList() + logs.flatMap { it.imageUrls.urlList() })
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
