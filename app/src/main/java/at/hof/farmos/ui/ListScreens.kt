package at.hof.farmos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.ListWork
import at.hof.farmos.data.db.FieldListEntity
import at.hof.farmos.data.db.FieldListItem
import at.hof.farmos.data.db.LocationEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.Locale

private fun ha(v: Double): String = String.format(Locale.GERMAN, "%.2f ha", v)

fun listWork(name: String?): ListWork? = ListWork.entries.firstOrNull { it.name == name }

private fun landTypeLabel(t: String?): String? = when (t) {
    "field" -> "Acker"
    "paddock" -> "Grünland"
    null -> null
    else -> t
}

// ==================================================================== Übersicht

@Composable
fun ListsScreen(vm: MainViewModel, onOpen: (String) -> Unit, onNew: () -> Unit, onFromLogs: () -> Unit) {
    val lists by vm.fieldLists.collectAsState()
    val items by vm.allListItems.collectAsState()
    val locations by vm.locations.collectAsState()
    val area = locations.associate { it.id to (it.areaHa ?: 0.0) }

    ScreenScaffold(
        title = "Feldlisten",
        actions = {
            IconButton(onClick = onFromLogs) { Icon(Icons.Default.History, "Liste aus Einträgen") }
        },
        fab = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Neue Liste") })
        },
    ) { pad ->
        if (lists.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize()) {
                EmptyHint(
                    "Noch keine Listen.\nZ. B. „Silo 1. Schnitt“ oder „Gülle KW 47“ anlegen, Felder auswählen, " +
                        "abhaken und per WhatsApp verschicken.\n\nOben rechts: Liste aus bereits erfassten Einträgen.",
                    Icons.Default.Checklist,
                )
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(lists, key = { it.id }) { l ->
                    val its = items.filter { it.listId == l.id }
                    val done = its.count { it.done }
                    val total = its.sumOf { area[it.locationId] ?: 0.0 }
                    ListItem(
                        modifier = Modifier.clickable { onOpen(l.id) },
                        headlineContent = { Text(l.name) },
                        supportingContent = {
                            Column {
                                Text(
                                    listOfNotNull(
                                        listWork(l.work)?.label,
                                        "$done/${its.size} erledigt",
                                        if (total > 0) ha(total) else null,
                                    ).joinToString(" · ")
                                )
                                if (its.isNotEmpty()) {
                                    LinearProgressIndicator(
                                        progress = { done.toFloat() / its.size },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Checklist, null) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ==================================================================== Anlegen / Bearbeiten

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListEditScreen(vm: MainViewModel, listId: String?, onDone: (String?) -> Unit) {
    val locations by vm.locations.collectAsState()
    val existing by remember(listId) { listId?.let { vm.fieldList(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)
    val existingItems by remember(listId) {
        listId?.let { vm.listItems(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val week = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    var name by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var loaded by rememberSaveable { mutableStateOf(listId == null) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    // Beim Bearbeiten einmal die vorhandenen Werte übernehmen
    LaunchedEffect(existing, existingItems) {
        val e = existing
        if (!loaded && e != null) {
            name = e.name; work = e.work; notes = e.notes.orEmpty()
            chosen = ArrayList(existingItems.map { it.locationId })
            nameEdited = true; loaded = true
        }
    }

    val fields = locations.filter { it.kind == "land" || it.kind == "structure" }
    val shown = fields.filter { f ->
        val ok = when (filter) {
            null -> true
            "blueh" -> f.isBlueh()
            "chosen" -> f.id in chosen
            else -> f.subtype == filter
        }
        ok && (query.isBlank() || f.name.contains(query, true))
    }
    val total = fields.filter { it.id in chosen }.sumOf { it.areaHa ?: 0.0 }

    ScreenScaffold(title = if (listId == null) "Neue Feldliste" else "Liste bearbeiten", onBack = { onDone(null) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Was wird gemacht?", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListWork.entries.forEach { w ->
                            FilterChip(work == w.name, {
                                work = if (work == w.name) null else w.name
                                if (!nameEdited) name = "${w.label.substringBefore(" /")} KW $week"
                            }, { Text(w.label) })
                        }
                    }
                    OutlinedTextField(
                        name, { name = it; nameEdited = true },
                        label = { Text("Name der Liste *") },
                        placeholder = { Text("z. B. Silo 1. Schnitt, Gülle KW $week") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notiz (optional)") },
                        modifier = Modifier.fillMaxWidth())
                    Text(
                        "Felder auswählen (${chosen.size}" + (if (total > 0) ", ${ha(total)}" else "") + ")",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SearchField(query) { query = it }
                    val types = fields.mapNotNull { it.subtype }.distinct()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(filter == null, { filter = null }, { Text("Alle") })
                        types.forEach { t ->
                            FilterChip(filter == t, { filter = if (filter == t) null else t },
                                { Text(landTypeLabel(t) ?: t) })
                        }
                        FilterChip(filter == "blueh", { filter = if (filter == "blueh") null else "blueh" },
                            { Text("🌼 Blühflächen") })
                        FilterChip(filter == "chosen", { filter = if (filter == "chosen") null else "chosen" },
                            { Text("✓ Ausgewählt") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { chosen = ArrayList((chosen + shown.map { it.id }).distinct()) }) {
                            Text("Alle angezeigten wählen")
                        }
                        TextButton(onClick = { chosen = ArrayList(chosen - shown.map { it.id }.toSet()) }) {
                            Text("Abwählen")
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { f ->
                val sel = f.id in chosen
                ListItem(
                    modifier = Modifier.clickable { chosen = ArrayList(if (sel) chosen - f.id else chosen + f.id) },
                    headlineContent = { Text((if (f.isBlueh()) "🌼 " else "") + f.name) },
                    supportingContent = {
                        Text(listOfNotNull(landTypeLabel(f.subtype), f.areaInfo,
                            cropsOn(f, locations).mapNotNull { it.subtype }.distinct().joinToString(", ").ifBlank { null })
                            .joinToString(" · "))
                    },
                    leadingContent = {
                        Checkbox(checked = sel, onCheckedChange = { chosen = ArrayList(if (sel) chosen - f.id else chosen + f.id) })
                    },
                )
                HorizontalDivider()
            }
            item {
                Button(
                    onClick = {
                        vm.saveFieldList(listId, name, listWork(work), notes, chosen) { id -> onDone(id) }
                    },
                    enabled = name.isNotBlank() && chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                ) { Text("Liste speichern (${chosen.size} Felder)") }
            }
        }
    }
}

// ==================================================================== Detail

@Composable
fun ListDetailScreen(
    vm: MainViewModel,
    listId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenField: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val list by remember(listId) { vm.fieldList(listId) }.collectAsState(initial = null)
    val items by remember(listId) { vm.listItems(listId) }.collectAsState(initial = emptyList())
    val locations by vm.locations.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLog by remember { mutableStateOf(false) }
    val l = list

    val byId = locations.associateBy { it.id }
    // offene zuerst, dann nach Name
    val rows = items.map { it to byId[it.locationId] }
        .sortedWith(compareBy<Pair<FieldListItem, LocationEntity?>>({ it.first.done }, { it.second?.name ?: "" }))
    val doneCount = items.count { it.done }
    val totalHa = rows.sumOf { it.second?.areaHa ?: 0.0 }
    val openHa = rows.filter { !it.first.done }.sumOf { it.second?.areaHa ?: 0.0 }
    val toLog = items.count { it.done && !it.logged }

    ScreenScaffold(
        title = l?.name ?: "Liste",
        onBack = onBack,
        actions = {
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Bearbeiten") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Löschen") }
        },
    ) { pad ->
        if (l == null) {
            EmptyHint("Liste nicht gefunden"); return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoRow("Arbeit", listWork(l.work)?.label)
                        InfoRow("Felder", "$doneCount von ${items.size} erledigt")
                        if (totalHa > 0) InfoRow("Fläche", "${ha(totalHa)} gesamt, ${ha(openHa)} offen")
                        InfoRow("Notiz", l.notes)
                        if (items.isNotEmpty()) {
                            LinearProgressIndicator(
                                progress = { doneCount.toFloat() / items.size },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                        Button(
                            onClick = { shareWhatsApp(ctx, listText(l, rows, onlyOpen = false)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Liste per WhatsApp senden") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { shareWhatsApp(ctx, listText(l, rows, onlyOpen = true)) },
                                enabled = doneCount < items.size,
                                modifier = Modifier.weight(1f),
                            ) { Text("Nur offene") }
                            OutlinedButton(
                                onClick = { shareAny(ctx, listText(l, rows, onlyOpen = false)) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Teilen …") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.setAllDone(l.id) }, enabled = doneCount < items.size) {
                                Text("Alle abhaken")
                            }
                            if (toLog > 0) {
                                TextButton(onClick = { confirmLog = true }) { Text("In farmOS eintragen ($toLog)") }
                            }
                        }
                    }
                }
            }
            items(rows, key = { it.first.locationId }) { (item, loc) ->
                ListItem(
                    modifier = Modifier.clickable { if (loc != null) onOpenField(loc.id) },
                    headlineContent = {
                        Text((if (loc?.isBlueh() == true) "🌼 " else "") + (loc?.name ?: "(Feld nicht mehr vorhanden)"))
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                loc?.areaInfo,
                                loc?.let { cropsOn(it, locations).mapNotNull { c -> c.subtype }.distinct().joinToString(", ").ifBlank { null } },
                                item.doneAt?.takeIf { item.done }?.let { "erledigt " + formatDate(it) },
                                if (item.logged) "in farmOS" else null,
                            ).joinToString(" · ")
                        )
                    },
                    leadingContent = {
                        Checkbox(checked = item.done, onCheckedChange = { vm.setListItemDone(l.id, item.locationId, it) })
                    },
                    trailingContent = {
                        val p = loc?.position()
                        if (p != null && loc != null) {
                            IconButton(onClick = { openNavigation(ctx, p.first, p.second, loc.name) }) {
                                Icon(Icons.Default.Directions, "Navigation")
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete && l != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Liste löschen?") },
            text = { Text("„${l.name}“ wird gelöscht. Bereits in farmOS eingetragene Einträge bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteFieldList(l.id); onBack() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmLog && l != null) {
        val w = listWork(l.work)
        AlertDialog(
            onDismissRequest = { confirmLog = false },
            title = { Text("In farmOS eintragen?") },
            text = {
                Text(
                    "Für $toLog erledigte(s) Feld(er) wird je ein Eintrag „${l.name} – Feldname“ angelegt" +
                        (w?.category?.let { " (Kategorie „$it“" + (w?.material?.let { m -> ", Mittel „$m“" } ?: "") + ")" } ?: "") +
                        ", mit dem Erledigt-Datum und der Feldgröße."
                )
            },
            confirmButton = { TextButton(onClick = { confirmLog = false; vm.logFieldList(l) }) { Text("Eintragen") } },
            dismissButton = { TextButton(onClick = { confirmLog = false }) { Text("Abbrechen") } },
        )
    }
}

/** WhatsApp-Text der Liste mit Kartenlinks. */
private fun listText(l: FieldListEntity, rows: List<Pair<FieldListItem, LocationEntity?>>, onlyOpen: Boolean): String {
    val sel = rows.filter { (!onlyOpen || !it.first.done) && it.second != null }
    val sum = sel.sumOf { it.second?.areaHa ?: 0.0 }
    return buildString {
        append("📋 ").append(l.name)
        if (onlyOpen) append(" – offen")
        append(" (").append(sel.size).append(" Felder")
        if (sum > 0) append(", ").append(ha(sum))
        append(")")
        l.notes?.let { append("\n").append(it) }
        sel.forEachIndexed { i, (item, loc) ->
            loc ?: return@forEachIndexed
            append("\n\n").append(i + 1).append(". ")
            if (!onlyOpen) append(if (item.done) "✅ " else "⬜ ")
            append(loc.name)
            loc.areaInfo?.let { append(" – ").append(it) }
            loc.position()?.let { p -> append("\n").append(mapsUrl(p.first, p.second)) }
        }
    }
}

// ==================================================================== Liste aus Einträgen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListFromLogsScreen(vm: MainViewModel, onDone: (String?) -> Unit) {
    val logs by vm.recentLogs.collectAsState()
    val locations by vm.locations.collectAsState()
    val byId = locations.associateBy { it.id }
    val zone = ZoneId.systemDefault()

    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var fromDay by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(30).toEpochDay()) }
    var toDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var work by rememberSaveable { mutableStateOf<String?>(null) }
    var showFrom by remember { mutableStateOf(false) }
    var showTo by remember { mutableStateOf(false) }

    val from = LocalDate.ofEpochDay(fromDay)
    val to = LocalDate.ofEpochDay(toDay)
    val labels = logs.map { logLabel(it) }.distinct().sorted()
    val startTs = from.atStartOfDay(zone).toEpochSecond()
    val endTs = to.plusDays(1).atStartOfDay(zone).toEpochSecond()

    // Feld -> letztes Datum, an dem dort etwas passend Erfasstes passiert ist
    val doneAt = mutableMapOf<String, Long>()
    logs.filter { label != null && logLabel(it) == label && it.timestamp in startTs until endTs }.forEach { log ->
        val direct = log.locationIds.split(",").filter { it.isNotBlank() }
        val viaPlants = log.assetIds.split(",").mapNotNull { byId[it] }.filter { it.kind == "plant" }
            .flatMap { it.locationIds.split(",") }.filter { it.isNotBlank() }
        (direct + viaPlants).filter { byId[it]?.kind == "land" || byId[it]?.kind == "structure" }.forEach { id ->
            doneAt[id] = maxOf(doneAt[id] ?: 0L, log.timestamp)
        }
    }
    LaunchedEffect(label, fromDay, toDay) {
        if (!nameEdited && label != null) name = "$label ${formatLocalDate(from)}–${formatLocalDate(to)}"
    }

    ScreenScaffold(title = "Liste aus Einträgen", onBack = { onDone(null) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Alle Felder, auf denen im Zeitraum etwas Bestimmtes erfasst wurde, z. B. alle Flächen mit " +
                            "„Düngung“ im Oktober. (Berücksichtigt die zuletzt geladenen Einträge.)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    Text("Was ist passiert?", style = MaterialTheme.typography.titleSmall)
                    if (labels.isEmpty()) Text("Noch keine Einträge geladen – zuerst synchronisieren.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEach { t -> FilterChip(label == t, { label = t }, { Text(t) }) }
                    }
                    Text("Zeitraum", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showFrom = true }, modifier = Modifier.weight(1f)) { Text("von ${formatLocalDate(from)}") }
                        OutlinedButton(onClick = { showTo = true }, modifier = Modifier.weight(1f)) { Text("bis ${formatLocalDate(to)}") }
                    }
                    Text("Arbeit für die Liste (optional)", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListWork.entries.forEach { w ->
                            FilterChip(work == w.name, { work = if (work == w.name) null else w.name }, { Text(w.label) })
                        }
                    }
                    OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text("Name der Liste *") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Gefundene Felder: ${doneAt.size}", style = MaterialTheme.typography.titleSmall)
                }
            }
            items(doneAt.keys.toList().sortedBy { byId[it]?.name }, key = { it }) { id ->
                val loc = byId[id]
                ListItem(
                    headlineContent = { Text(loc?.name ?: id) },
                    supportingContent = { Text(listOfNotNull(loc?.areaInfo, doneAt[id]?.let { formatDate(it) }).joinToString(" · ")) },
                )
                HorizontalDivider()
            }
            item {
                Button(
                    onClick = { vm.createListFromLogs(name, listWork(work), doneAt.toMap()) { id -> onDone(id) } },
                    enabled = name.isNotBlank() && doneAt.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                ) { Text("Liste erstellen (${doneAt.size} Felder)") }
            }
        }
    }
    if (showFrom) DateDialog(from, onPick = { fromDay = it.toEpochDay() }, onDismiss = { showFrom = false })
    if (showTo) DateDialog(to, onPick = { toDay = it.toEpochDay() }, onDismiss = { showTo = false })
}
