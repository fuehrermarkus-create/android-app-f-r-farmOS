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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import at.hof.farmos.data.db.AnimalTypeEntity
import at.hof.farmos.data.db.urlList
import java.time.LocalDate

// ==================================================================== Liste

@Composable
fun AnimalListScreen(vm: MainViewModel, onOpen: (String) -> Unit, onNew: () -> Unit, onPhoto: () -> Unit) {
    val animals by vm.animals.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val typeNames = animals.mapNotNull { it.animalTypeName }.distinct().sorted()
    val shown = animals.filter { a ->
        (typeFilter == null || a.animalTypeName == typeFilter) &&
            (query.isBlank() || a.name.contains(query, true) || (a.idTag?.contains(query, true) == true))
    }

    ScreenScaffold(
        title = "Tiere (${animals.size})",
        actions = {
            IconButton(onClick = onPhoto) { Icon(Icons.Default.CameraAlt, "Foto aufnehmen") }
            IconButton(onClick = { vm.sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Synchronisieren") }
        },
        fab = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Neues Tier") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SearchField(query) { query = it }
                if (typeNames.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = typeFilter == null, onClick = { typeFilter = null },
                                label = { Text("Alle") })
                        }
                        items(typeNames) { t ->
                            FilterChip(selected = typeFilter == t,
                                onClick = { typeFilter = if (typeFilter == t) null else t },
                                label = { Text(t) })
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                EmptyHint(
                    if (animals.isEmpty()) "Noch keine Tiere.\nZum Laden oben auf Synchronisieren tippen " +
                        "oder ein neues Tier anlegen." else "Keine Treffer",
                    Icons.Default.Pets,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { a ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(a.id) },
                            headlineContent = { Text(a.name) },
                            supportingContent = {
                                Text(listOfNotNull(a.animalTypeName, a.idTag, a.locationNames).joinToString(" · "))
                            },
                            leadingContent = {
                                Icon(
                                    when (a.sex) {
                                        "F" -> Icons.Default.Female
                                        "M" -> Icons.Default.Male
                                        else -> Icons.Default.Pets
                                    }, null,
                                )
                            },
                            trailingContent = if (a.pending) {
                                { Icon(Icons.Default.CloudUpload, "Noch nicht übertragen",
                                    tint = MaterialTheme.colorScheme.secondary) }
                            } else null,
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
fun AnimalDetailScreen(
    vm: MainViewModel,
    id: String,
    onBack: () -> Unit,
    onQuickLog: (LogKind, Boolean) -> Unit,
) {
    val animal by remember(id) { vm.animal(id) }.collectAsState(initial = null)
    val logs by remember(id) { vm.logsFor(id) }.collectAsState(initial = emptyList())
    val a = animal

    ScreenScaffold(title = a?.name ?: "Tier", onBack = onBack) { pad ->
        if (a == null) {
            EmptyHint("Tier nicht gefunden"); return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("Tierart", a.animalTypeName)
                        InfoRow("Ohrmarke", a.idTag)
                        InfoRow("Geschlecht", sexLabel(a.sex))
                        InfoRow("Geburtsdatum", a.birthdate?.let { formatDate(it) })
                        InfoRow("Standort", a.locationNames)
                        InfoRow("Notizen", a.notes)
                        if (a.pending) Text("Noch nicht zum Server übertragen",
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                PhotoSection(a.imageUrls.urlList() + logs.flatMap { it.imageUrls.urlList() })
            }
            item {
                Text("Schnell erfassen", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                FlowRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { onQuickLog(LogKind.OBSERVATION, true) },
                        label = { Text("Foto") },
                        leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                    )
                    listOf(LogKind.OBSERVATION, LogKind.MEDICAL, LogKind.INPUT, LogKind.MOVEMENT,
                        LogKind.ACTIVITY).forEach { k ->
                        AssistChip(onClick = { onQuickLog(k, false) }, label = { Text(k.label) })
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

// ==================================================================== Neues Tier

private const val NEW_TYPE = "__new__"

@Composable
fun AnimalNewScreen(vm: MainViewModel, onDone: () -> Unit) {
    val types by vm.animalTypes.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf<String?>(null) }
    var typeId by rememberSaveable { mutableStateOf<String?>(null) }
    var newTypeName by rememberSaveable { mutableStateOf("") }
    var birthEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var showTypes by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    val birth = birthEpochDay?.let { LocalDate.ofEpochDay(it) }
    val selectedType = types.firstOrNull { it.id == typeId }
    val typeOk = selectedType != null || (typeId == NEW_TYPE && newTypeName.isNotBlank())

    ScreenScaffold(title = "Neues Tier", onBack = onDone) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name *") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedButton(onClick = { showTypes = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        selectedType != null -> "Tierart: ${selectedType.name}"
                        typeId == NEW_TYPE -> "Tierart: neu anlegen"
                        else -> "Tierart wählen *"
                    }
                )
            }
            if (typeId == NEW_TYPE) {
                OutlinedTextField(newTypeName, { newTypeName = it },
                    label = { Text("Name der neuen Tierart (z. B. Rind)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(tag, { tag = it }, label = { Text("Ohrmarke") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Geschlecht", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(sex == "F", { sex = if (sex == "F") null else "F" }, { Text("weiblich") })
                FilterChip(sex == "M", { sex = if (sex == "M") null else "M" }, { Text("männlich") })
            }

            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Geburtsdatum: ${formatLocalDate(birth)}")
            }

            OutlinedTextField(notes, { notes = it }, label = { Text("Notizen") },
                minLines = 2, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    val typeName = selectedType?.name ?: newTypeName.trim()
                    vm.createAnimal(
                        name = name,
                        sex = sex,
                        birthdate = birth,
                        idTag = tag.ifBlank { null },
                        typeId = selectedType?.id,
                        newTypeName = if (selectedType == null) newTypeName else null,
                        typeName = typeName,
                        notes = notes.ifBlank { null },
                    )
                    onDone()
                },
                enabled = name.isNotBlank() && typeOk,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Speichern") }
            Text("Wird auch offline gespeichert und später übertragen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    if (showTypes) {
        val options = types + AnimalTypeEntity(NEW_TYPE, "+ Neue Tierart …")
        PickerDialog(
            title = "Tierart",
            items = options,
            label = { it.name },
            selected = options.firstOrNull { it.id == typeId },
            onSelect = { typeId = it?.id; showTypes = false },
            onDismiss = { showTypes = false },
            allowNone = false,
        )
    }
    if (showDate) {
        DateDialog(birth, onPick = { birthEpochDay = it.toEpochDay() }, onDismiss = { showDate = false })
    }
}
