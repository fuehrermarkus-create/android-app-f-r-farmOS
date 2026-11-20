package at.hof.farmos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.Amount
import at.hof.farmos.data.AssetRef
import at.hof.farmos.data.FieldWork
import at.hof.farmos.data.FieldWorkDraft
import at.hof.farmos.data.eppoFor
import at.hof.farmos.photo.PhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Einheiten, die je Arbeit zur Auswahl stehen (erste = Vorgabe). */
private fun unitsFor(work: FieldWork): List<String> = when (work) {
    FieldWork.SEEDING -> listOf("kg/ha", "Körner/m²", "kg")
    FieldWork.SPRAYING -> listOf("l/ha", "kg/ha", "l", "kg")
    FieldWork.FERTILIZING -> listOf("kg/ha", "m³/ha", "t/ha", "kg", "m³", "t")
    FieldWork.HARVEST -> listOf("t", "dt/ha", "t/ha", "dt", "kg", "Ballen")
}

/** Aussaat, Pflanzenschutz, Düngung und Ernte – jeweils nur mit den passenden Feldern. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldWorkScreen(
    vm: MainViewModel,
    work: FieldWork,
    preField: String?,
    prePlants: List<String>,
    onDone: () -> Unit,
) {
    val allLocations by vm.locations.collectAsState()
    val plantTypes by vm.plantTypes.collectAsState()
    val materials by vm.materials.collectAsState()
    val machines by vm.equipment.collectAsState()
    var machineIds by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    val places = allLocations.filter { it.kind != "plant" }
    val plants = allLocations.filter { it.kind == "plant" }

    // Feld: vorgegeben oder – wenn von einer Kultur aus geöffnet – deren Standort
    val startField = preField ?: plants.firstOrNull { it.id in prePlants }
        ?.locationIds?.split(",")?.firstOrNull { it.isNotBlank() }
    var fieldId by rememberSaveable { mutableStateOf(startField) }
    var plantIds by rememberSaveable { mutableStateOf<List<String>>(ArrayList(prePlants)) }
    var plantsEdited by rememberSaveable { mutableStateOf(prePlants.isNotEmpty()) }
    var crop by rememberSaveable { mutableStateOf("") }
    var variety by rememberSaveable { mutableStateOf("") }
    var material by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(unitsFor(work).first()) }
    var water by rememberSaveable { mutableStateOf("") }
    var treated by rememberSaveable { mutableStateOf("") }
    var treatedEdited by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    // Aufzeichnungspflichten
    var applicator by rememberSaveable { mutableStateOf(vm.prefs.applicator.ifBlank { vm.prefs.username }) }
    var regNo by rememberSaveable { mutableStateOf("") }
    var eppo by rememberSaveable { mutableStateOf("") }
    var eppoEdited by rememberSaveable { mutableStateOf(false) }
    var bbch by rememberSaveable { mutableStateOf("") }
    var fertKind by rememberSaveable { mutableStateOf<String?>(null) }
    var nContent by rememberSaveable { mutableStateOf("") }
    var technique by rememberSaveable { mutableStateOf<String?>(null) }
    var incorporated by rememberSaveable { mutableStateOf<Long?>(null) }
    var showIncDate by remember { mutableStateOf(false) }
    var showIncTime by remember { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var ts by rememberSaveable { mutableStateOf(System.currentTimeMillis() / 1000) }
    var photos by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var showFields by remember { mutableStateOf(false) }
    var showPlants by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val field = places.firstOrNull { it.id == fieldId }
    val onField = plants.filter { p -> fieldId != null && fieldId in p.locationIds.split(",") }
    val chosenPlants = plants.filter { it.id in plantIds }

    // Vorgaben, sobald das Feld feststeht: Kulturen auf dem Feld, behandelte Fläche = Feldgröße
    LaunchedEffect(fieldId, onField.size, field?.areaHa) {
        if (!plantsEdited && work != FieldWork.SEEDING) plantIds = ArrayList(onField.map { it.id })
        if (!treatedEdited) treated = field?.areaHa?.let { String.format(Locale.GERMAN, "%.2f", it) } ?: ""
    }

    // Gemerkte Angaben zum Mittel/Dünger übernehmen (Zulassungsnummer, N-Gehalt, Art)
    LaunchedEffect(material) {
        val info = if (material.isBlank()) emptyMap() else vm.prefs.materialInfo(material)
        if (work == FieldWork.SPRAYING && regNo.isBlank()) info["zulassungsnummer"]?.let { regNo = it }
        if (work == FieldWork.FERTILIZING) {
            if (nContent.isBlank()) info["n_gehalt"]?.let { nContent = it }
            if (fertKind == null) fertKind = info["duengerart"]
            if (technique == null) technique = info["technik"]
        }
    }
    LaunchedEffect(plantIds, plants.size) {
        if (!eppoEdited) eppo = plants.filter { it.id in plantIds }
            .firstNotNullOfOrNull { eppoFor(it.subtype) ?: eppoFor(it.name) } ?: ""
    }

    val photo = rememberPhotoCapture { imported -> photos = ArrayList(photos + imported.path) }

    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochSecond(ts).atZone(zone)
    val valid = photo.importing == 0 && field != null && when (work) {
        FieldWork.SEEDING -> crop.isNotBlank()
        FieldWork.SPRAYING -> material.isNotBlank() && regNo.isNotBlank() && applicator.isNotBlank()
        FieldWork.FERTILIZING -> material.isNotBlank()
        FieldWork.HARVEST -> true
    }

    ScreenScaffold(title = work.label, onBack = onDone) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Wo?
            Section("Feld *")
            OutlinedButton(onClick = { showFields = true }, modifier = Modifier.fillMaxWidth()) {
                Text(field?.let { f -> f.name + (f.areaInfo?.let { " · $it" } ?: "") } ?: "Feld wählen")
            }

            // ---- Was?
            when (work) {
                FieldWork.SEEDING -> {
                    Section("Kultur *")
                    OutlinedTextField(crop, { crop = it }, label = { Text("z. B. Winterweizen, Silomais, Blühmischung") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Suggestions(plantTypes.map { it.name }, crop) { crop = it }
                    OutlinedTextField(variety, { variety = it }, label = { Text("Sorte (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                FieldWork.SPRAYING, FieldWork.FERTILIZING, FieldWork.HARVEST -> {
                    Section(if (work == FieldWork.HARVEST) "Kultur" else "Kulturen auf dem Feld")
                    if (onField.isEmpty() && chosenPlants.isEmpty()) {
                        Text(
                            if (field == null) "Zuerst ein Feld wählen."
                            else "Auf diesem Feld ist keine Kultur erfasst (Aussaat erfassen oder ohne Kultur speichern).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (onField + chosenPlants).distinctBy { it.id }.forEach { p ->
                            FilterChip(
                                selected = p.id in plantIds,
                                onClick = {
                                    plantsEdited = true
                                    plantIds = ArrayList(if (p.id in plantIds) plantIds - p.id else plantIds + p.id)
                                },
                                label = { Text(p.name) },
                            )
                        }
                        if (plants.isNotEmpty()) AssistChip(onClick = { showPlants = true }, label = { Text("Andere …") })
                    }
                    if (work != FieldWork.HARVEST) {
                        Section(if (work == FieldWork.SPRAYING) "Pflanzenschutzmittel *" else "Dünger *")
                        OutlinedTextField(
                            material, { material = it },
                            label = { Text(if (work == FieldWork.SPRAYING) "z. B. Mittelname" else "z. B. Gülle, NAC 27, Mist") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Suggestions(materials.map { it.name }, material) { material = it }
                        if (work == FieldWork.SPRAYING) {
                            OutlinedTextField(
                                regNo, { regNo = it }, label = { Text("Zulassungsnummer *") },
                                supportingText = { Text("Steht auf dem Etikett bzw. im Pflanzenschutzmittelregister, z. B. 3045-0") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Section("Art des Düngers")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FERTILIZER_KINDS.forEach { k ->
                                    FilterChip(fertKind == k, { fertKind = if (fertKind == k) null else k }, { Text(k) })
                                }
                            }
                        }
                    }
                }
            }

            // ---- Wie viel?
            Section(
                when (work) {
                    FieldWork.SEEDING -> "Saatmenge"
                    FieldWork.SPRAYING -> "Aufwandmenge"
                    FieldWork.FERTILIZING -> "Düngermenge"
                    FieldWork.HARVEST -> "Erntemenge"
                }
            )
            OutlinedTextField(
                amount, { amount = it }, label = { Text("Menge") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                unitsFor(work).forEach { u -> FilterChip(unit == u, { unit = u }, { Text(u) }) }
            }
            if (work == FieldWork.SPRAYING) {
                OutlinedTextField(
                    water, { water = it }, label = { Text("Wasseraufwand l/ha (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    reason, { reason = it }, label = { Text("Grund / Schaderreger (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                treated, { treated = it; treatedEdited = true },
                label = { Text(if (work == FieldWork.HARVEST) "Geerntete Fläche in ha" else "Bearbeitete Fläche in ha") },
                supportingText = { if (field?.areaHa != null) Text("Vorgabe: ganze Feldgröße") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            if (work == FieldWork.FERTILIZING) {
                Section("Stickstoff (für die Düngeaufzeichnung)")
                OutlinedTextField(
                    nContent, { nContent = it },
                    label = { Text(nLabel(unit)) },
                    supportingText = { Text("Jahreswirksamer N laut Analyse, Sackaufdruck oder Richtwert") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                nPerHa(amount, unit, nContent, treated)?.let {
                    Text("≈ ${String.format(Locale.GERMAN, "%.1f", it)} kg N/ha",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Section("Ausbringtechnik (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TECHNIQUES.forEach { t ->
                        FilterChip(technique == t, { technique = if (technique == t) null else t }, { Text(t) })
                    }
                }
                Section("Einarbeitung (optional)")
                val inc = incorporated?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inc == null) {
                        OutlinedButton(onClick = { incorporated = ts + 3600 }, modifier = Modifier.weight(1f)) {
                            Text("Einarbeitung erfassen")
                        }
                    } else {
                        OutlinedButton(onClick = { showIncDate = true }, modifier = Modifier.weight(1f)) {
                            Text(formatLocalDate(inc.toLocalDate()))
                        }
                        OutlinedButton(onClick = { showIncTime = true }, modifier = Modifier.weight(1f)) {
                            Text(String.format(Locale.GERMAN, "%02d:%02d Uhr", inc.hour, inc.minute))
                        }
                        TextButton(onClick = { incorporated = null }) { Text("Entfernen") }
                    }
                }
            }
            if (work == FieldWork.SPRAYING) {
                Section("Für die Pflanzenschutz-Aufzeichnung")
                OutlinedTextField(
                    applicator, { applicator = it }, label = { Text("Anwender *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    eppo, { eppo = it.uppercase(); eppoEdited = true },
                    label = { Text("EPPO-Code der Kultur") },
                    supportingText = { Text("Wird bei bekannten Kulturen vorgeschlagen, z. B. ZEAMX = Mais") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    bbch, { bbch = it }, label = { Text("BBCH-Stadium (nur wenn die Zulassung es verlangt)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Womit?
            if (machines.isNotEmpty()) {
                Section("Maschinen (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    machines.forEach { m ->
                        FilterChip(
                            selected = m.id in machineIds,
                            onClick = { machineIds = ArrayList(if (m.id in machineIds) machineIds - m.id else machineIds + m.id) },
                            label = { Text(m.name) },
                        )
                    }
                }
            }

            // ---- Wann?
            Section("Zeit")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(formatLocalDate(zdt.toLocalDate()))
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(String.format(Locale.GERMAN, "%02d:%02d Uhr", zdt.hour, zdt.minute))
                }
            }

            // ---- Fotos & Notizen
            Section("Fotos & Notizen (optional)")
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
            OutlinedTextField(notes, { notes = it }, label = { Text("Notizen") }, minLines = 2,
                modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    val f = field
                    if (f != null) vm.createFieldWork(
                        FieldWorkDraft(
                            work = work,
                            field = AssetRef(f.id, "asset--${f.kind}", f.name),
                            timestamp = ts,
                            notes = notes.ifBlank { null },
                            cropName = crop.ifBlank { null },
                            variety = variety.ifBlank { null },
                            plants = if (work == FieldWork.SEEDING) emptyList()
                            else chosenPlants.map { AssetRef(it.id, "asset--plant", it.name) },
                            material = material.ifBlank { null },
                            amount = amount.ifBlank { null }?.let { Amount(it, unit) },
                            water = water.ifBlank { null }?.let { Amount(it, "l/ha") },
                            treatedHa = treated.ifBlank { null },
                            reason = reason.ifBlank { null },
                            photos = photos,
                            equipment = machines.filter { it.id in machineIds }
                                .map { AssetRef(it.id, "asset--equipment", it.name) },
                            applicator = if (work == FieldWork.SPRAYING) applicator.ifBlank { null } else null,
                            registrationNo = regNo.ifBlank { null },
                            eppo = eppo.ifBlank { null },
                            bbch = bbch.ifBlank { null },
                            fertilizerKind = fertKind,
                            nContent = nContent.ifBlank { null },
                            technique = technique,
                            incorporatedAt = incorporated,
                        )
                    )
                    if (material.isNotBlank()) vm.prefs.rememberMaterial(
                        material,
                        mapOf(
                            "zulassungsnummer" to regNo, "n_gehalt" to nContent,
                            "duengerart" to fertKind, "technik" to technique,
                        ),
                    )
                    if (work == FieldWork.SPRAYING && applicator.isNotBlank()) vm.prefs.applicator = applicator
                    if (f != null) onDone()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("${work.label} speichern") }
            Text(
                when (work) {
                    FieldWork.SEEDING -> "Legt in farmOS die Kultur an und stellt sie auf das Feld (Aussaat-Log)."
                    FieldWork.SPRAYING -> "Wird als Gabe-Log mit Kategorie „Pflanzenschutz“ gespeichert."
                    FieldWork.FERTILIZING -> "Wird als Gabe-Log mit Kategorie „Düngung“ gespeichert."
                    FieldWork.HARVEST -> "Wird als Ernte-Log gespeichert."
                } + " Funktioniert auch offline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (showFields) {
        PickerDialog(
            title = "Feld",
            items = places,
            label = { listOfNotNull(it.name, it.areaInfo).joinToString(" · ") },
            selected = field,
            onSelect = { fieldId = it?.id; plantsEdited = false; treatedEdited = false; showFields = false },
            onDismiss = { showFields = false },
        )
    }
    if (showPlants) {
        MultiPickerDialog(
            title = "Kulturen",
            items = plants,
            key = { it.id },
            label = { listOfNotNull(it.name, it.locationNames).joinToString(" · ") },
            initial = plantIds.toSet(),
            onDone = { plantIds = ArrayList(it); plantsEdited = true; showPlants = false },
            onDismiss = { showPlants = false },
        )
    }
    if (showDate) {
        DateDialog(zdt.toLocalDate(), onPick = { d: LocalDate ->
            ts = d.atTime(zdt.toLocalTime()).atZone(zone).toEpochSecond()
        }, onDismiss = { showDate = false })
    }
    if (showIncDate) {
        val inc = Instant.ofEpochSecond(incorporated ?: ts).atZone(zone)
        DateDialog(inc.toLocalDate(), onPick = { d: LocalDate ->
            incorporated = d.atTime(inc.toLocalTime()).atZone(zone).toEpochSecond()
        }, onDismiss = { showIncDate = false })
    }
    if (showIncTime) {
        val inc = Instant.ofEpochSecond(incorporated ?: ts).atZone(zone)
        TimeDialog(inc.hour, inc.minute, onPick = { h, m ->
            incorporated = inc.withHour(h).withMinute(m).withSecond(0).toEpochSecond()
        }, onDismiss = { showIncTime = false })
    }
    if (showTime) {
        TimeDialog(zdt.hour, zdt.minute, onPick = { h, m ->
            ts = zdt.withHour(h).withMinute(m).withSecond(0).toEpochSecond()
        }, onDismiss = { showTime = false })
    }
}

private val FERTILIZER_KINDS = listOf(
    "Gülle / Jauche", "Mist / Kompost", "Mineraldünger", "Gärrest", "Sonstiger organischer",
)
private val TECHNIQUES = listOf(
    "Schleppschlauch", "Schleppschuh", "Injektion / Schlitz", "Breitverteiler", "Düngerstreuer", "Miststreuer",
)

/** Bezeichnung des N-Gehalts passend zur Mengeneinheit. */
private fun nLabel(unit: String): String = when {
    unit.startsWith("m³") -> "N-Gehalt in kg N je m³"
    unit.startsWith("t") -> "N-Gehalt in kg N je t"
    else -> "N-Gehalt in % N"
}

/** kg N je ha aus Menge, Einheit, N-Gehalt und Fläche (null, wenn nicht berechenbar). */
private fun nPerHa(amount: String, unit: String, n: String, treatedHa: String): Double? {
    val a = amount.replace(',', '.').toDoubleOrNull() ?: return null
    val g = n.replace(',', '.').toDoubleOrNull() ?: return null
    val perUnit = if (unit.startsWith("kg")) g / 100.0 else g          // % N bei kg
    val total = a * perUnit
    return if (unit.endsWith("/ha")) total
    else treatedHa.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { total / it }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

/** Vorschläge aus farmOS zum schnellen Antippen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(all: List<String>, typed: String, onPick: (String) -> Unit) {
    val list = all.filter { typed.isBlank() || it.contains(typed.trim(), ignoreCase = true) }
        .filter { !it.equals(typed.trim(), ignoreCase = true) }
        .take(12)
    if (list.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { name -> AssistChip(onClick = { onPick(name) }, label = { Text(name) }) }
    }
}

// ==================================================================== Fotos aufnehmen (wiederverwendbar)

class PhotoCaptureState(
    val launchCamera: () -> Unit,
    val launchGallery: () -> Unit,
    val importing: Int,
    val error: String?,
)

@Composable
fun rememberPhotoCapture(onImported: (PhotoStore.Imported) -> Unit): PhotoCaptureState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun importInto(scope: CoroutineScope, context: Context, uri: Uri, deleteAfter: File?) {
        scope.launch {
            importing++
            val r = withContext(Dispatchers.IO) {
                val res = runCatching { PhotoStore.import(context, uri) }.getOrNull()
                deleteAfter?.delete()
                res
            }
            importing--
            if (r == null) error = "Foto konnte nicht gelesen werden" else { error = null; onImported(r) }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCapture
        pendingCapture = null
        if (ok && path != null) {
            val f = File(path)
            importInto(scope, ctx, Uri.fromFile(f), f)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        uris.forEach { importInto(scope, ctx, it, null) }
    }
    return PhotoCaptureState(
        launchCamera = {
            val (uri, file) = PhotoStore.newCaptureTarget(ctx)
            pendingCapture = file.absolutePath
            try {
                takePicture.launch(uri)
            } catch (e: ActivityNotFoundException) {
                pendingCapture = null
                error = "Keine Kamera-App gefunden"
            }
        },
        launchGallery = {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        importing = importing,
        error = error,
    )
}
