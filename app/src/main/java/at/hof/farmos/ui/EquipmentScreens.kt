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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.AssetRef
import at.hof.farmos.data.MachineLogDraft
import at.hof.farmos.data.MachineWork
import at.hof.farmos.data.db.urlList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ==================================================================== Liste

@Composable
fun EquipmentListScreen(vm: MainViewModel, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val machines by vm.equipment.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = machines.filter {
        query.isBlank() || it.name.contains(query, true) || it.manufacturer?.contains(query, true) == true ||
            it.model?.contains(query, true) == true
    }

    ScreenScaffold(
        title = "Maschinen (${machines.size})",
        actions = {
            IconButton(onClick = { vm.sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Synchronisieren") }
        },
        fab = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Neue Maschine") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { SearchField(query) { query = it } }
            if (shown.isEmpty()) {
                EmptyHint(
                    if (machines.isEmpty()) "Noch keine Maschinen.\nTraktor, Mähwerk, Güllefass … mit „Neue Maschine“ anlegen."
                    else "Keine Treffer",
                    Icons.Default.Agriculture,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { m ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(m.id) },
                            headlineContent = { Text(m.name) },
                            supportingContent = {
                                Text(listOfNotNull(m.manufacturer, m.model).joinToString(" · ").ifBlank { "Maschine" })
                            },
                            leadingContent = { Icon(Icons.Default.Agriculture, null) },
                            trailingContent = {
                                if (m.pending) Icon(Icons.Default.CloudUpload, "Noch nicht übertragen",
                                    tint = MaterialTheme.colorScheme.secondary)
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ==================================================================== Detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentDetailScreen(
    vm: MainViewModel,
    id: String,
    onBack: () -> Unit,
    onWork: (MachineWork, Boolean) -> Unit,
) {
    val machine by remember(id) { vm.equipmentItem(id) }.collectAsState(initial = null)
    val logs by remember(id) { vm.logsFor(id) }.collectAsState(initial = emptyList())
    val m = machine

    ScreenScaffold(title = m?.name ?: "Maschine", onBack = onBack) { pad ->
        if (m == null) {
            EmptyHint("Maschine nicht gefunden"); return@ScreenScaffold
        }
        val lastService = logs.firstOrNull { it.type == "maintenance" }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Hersteller", m.manufacturer)
                        InfoRow("Modell", m.model)
                        InfoRow("Seriennummer", m.serial)
                        InfoRow("Letzte Wartung", lastService?.let { formatDate(it.timestamp) } ?: "–")
                        InfoRow("Notizen", m.notes)
                        if (m.pending) Text("Noch nicht zum Server übertragen",
                            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Erfassen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onWork(MachineWork.MAINTENANCE, false) }, label = { Text("Wartung") },
                        leadingIcon = { Icon(Icons.Default.Build, null) })
                    AssistChip(onClick = { onWork(MachineWork.REPAIR, false) }, label = { Text("Reparatur") },
                        leadingIcon = { Icon(Icons.Default.Handyman, null) })
                    AssistChip(onClick = { onWork(MachineWork.DAMAGE, true) }, label = { Text("Foto / Schaden") },
                        leadingIcon = { Icon(Icons.Default.ReportProblem, null) })
                }
            }
            item { PhotoSection(m.imageUrls.urlList() + logs.flatMap { it.imageUrls.urlList() }) }
            item {
                Text("Verlauf (${logs.size})", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            if (logs.isEmpty()) item {
                Text("Noch keine Einträge", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
            }
            items(logs, key = { it.id }) { LogRow(it) }
        }
    }
}

// ==================================================================== Neue Maschine

@Composable
fun EquipmentNewScreen(vm: MainViewModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var manufacturer by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var serial by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    ScreenScaffold(title = "Neue Maschine", onBack = onDone) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name *") },
                placeholder = { Text("z. B. Traktor Steyr, Mähwerk, Güllefass") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(manufacturer, { manufacturer = it }, label = { Text("Hersteller") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Modell") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(serial, { serial = it }, label = { Text("Seriennummer / Kennzeichen") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notizen") }, minLines = 2,
                modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    vm.createEquipment(name, manufacturer.ifBlank { null }, model.ifBlank { null },
                        serial.ifBlank { null }, notes.ifBlank { null })
                    onDone()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Speichern") }
            Text("Wird auch offline gespeichert und später übertragen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ==================================================================== Wartung / Reparatur / Schaden

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MachineLogScreen(
    vm: MainViewModel,
    preMachine: String?,
    preWork: MachineWork,
    startCamera: Boolean,
    onDone: () -> Unit,
) {
    val machines by vm.equipment.collectAsState()
    var machineId by rememberSaveable { mutableStateOf(preMachine) }
    var work by rememberSaveable { mutableStateOf(preWork) }
    var hours by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var ts by rememberSaveable { mutableStateOf(System.currentTimeMillis() / 1000) }
    var photos by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var cameraStarted by rememberSaveable { mutableStateOf(false) }
    var showMachines by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val photo = rememberPhotoCapture { imported -> photos = ArrayList(photos + imported.path) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (startCamera && !cameraStarted) { cameraStarted = true; photo.launchCamera() }
    }

    val machine = machines.firstOrNull { it.id == machineId }
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochSecond(ts).atZone(zone)
    val valid = machine != null && photo.importing == 0 &&
        (notes.isNotBlank() || hours.isNotBlank() || photos.isNotEmpty() || work != MachineWork.DAMAGE)

    ScreenScaffold(title = work.label, onBack = onDone) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Maschine *", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { showMachines = true }, modifier = Modifier.fillMaxWidth()) {
                Text(machine?.name ?: "Maschine wählen")
            }
            Text("Was?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MachineWork.entries.forEach { w -> FilterChip(work == w, { work = w }, { Text(w.label) }) }
            }
            OutlinedTextField(
                hours, { hours = it }, label = { Text("Betriebsstunden (Stand, optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                notes, { notes = it },
                label = {
                    Text(
                        when (work) {
                            MachineWork.MAINTENANCE -> "Was wurde gemacht? (z. B. Ölwechsel, Filter)"
                            MachineWork.REPAIR -> "Was wurde repariert / getauscht?"
                            MachineWork.DAMAGE -> "Was ist aufgefallen?"
                        }
                    )
                },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Text("Zeit", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(formatLocalDate(zdt.toLocalDate()))
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(String.format(Locale.GERMAN, "%02d:%02d Uhr", zdt.hour, zdt.minute))
                }
            }
            Text("Fotos (optional)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = photo.launchCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Text("  Kamera")
                }
                FilledTonalButton(onClick = photo.launchGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null); Text("  Galerie")
                }
            }
            if (photo.importing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Foto wird verarbeitet …", style = MaterialTheme.typography.bodySmall)
                }
            }
            photo.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PhotoThumbs(
                urls = photos.map { "file://$it" }, size = 88.dp,
                onRemove = { i -> photos = ArrayList(photos.filterIndexed { idx, _ -> idx != i }) },
            )
            Button(
                onClick = {
                    val mm = machine
                    if (mm != null) {
                        vm.createMachineLog(
                            MachineLogDraft(
                                work = work,
                                machine = AssetRef(mm.id, "asset--equipment", mm.name),
                                timestamp = ts,
                                notes = notes.ifBlank { null },
                                hours = hours.ifBlank { null },
                                photos = photos,
                            )
                        )
                        onDone()
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("${work.label} speichern") }
        }
    }

    if (showMachines) {
        PickerDialog(
            title = "Maschine",
            items = machines,
            label = { listOfNotNull(it.name, it.model).joinToString(" · ") },
            selected = machine,
            onSelect = { machineId = it?.id; showMachines = false },
            onDismiss = { showMachines = false },
            allowNone = false,
        )
    }
    if (showDate) {
        DateDialog(zdt.toLocalDate(), onPick = { d: LocalDate ->
            ts = d.atTime(zdt.toLocalTime()).atZone(zone).toEpochSecond()
        }, onDismiss = { showDate = false })
    }
    if (showTime) {
        TimeDialog(zdt.hour, zdt.minute, onPick = { h, mi ->
            ts = zdt.withHour(h).withMinute(mi).withSecond(0).toEpochSecond()
        }, onDismiss = { showTime = false })
    }
}
