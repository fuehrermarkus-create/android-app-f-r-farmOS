package at.hof.farmos.ui

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import at.hof.farmos.data.AssetRef
import at.hof.farmos.data.LogDraft
import at.hof.farmos.data.LogKind
import at.hof.farmos.photo.GeoHelper
import at.hof.farmos.photo.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun LogListScreen(vm: MainViewModel, onNew: () -> Unit, onPhoto: () -> Unit) {
    val logs by vm.recentLogs.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyPhotos by rememberSaveable { mutableStateOf(false) }
    val shown = logs.filter {
        (filter == null || LogKind.labelFor(it.type, it.isMovement) == filter) &&
            (!onlyPhotos || it.imageUrls.isNotBlank())
    }

    ScreenScaffold(
        title = "Einträge",
        actions = {
            IconButton(onClick = onPhoto) { Icon(Icons.Default.CameraAlt, "Foto aufnehmen") }
            IconButton(onClick = { vm.sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Synchronisieren") }
        },
        fab = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Schnell-Log") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            KindChips(selected = filter, onSelect = { filter = it }, onlyPhotos = onlyPhotos,
                onTogglePhotos = { onlyPhotos = !onlyPhotos })
            if (shown.isEmpty()) {
                EmptyHint("Noch keine Einträge", Icons.Default.Description)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { LogRow(it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(
    selected: String?,
    onSelect: (String?) -> Unit,
    onlyPhotos: Boolean,
    onTogglePhotos: () -> Unit,
) {
    FlowRow(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(onlyPhotos, onTogglePhotos, { Text("📷 mit Fotos") })
        FilterChip(selected == null, { onSelect(null) }, { Text("Alle") })
        LogKind.entries.forEach { k ->
            FilterChip(selected == k.label, { onSelect(if (selected == k.label) null else k.label) },
                { Text(k.label) })
        }
    }
}

// ==================================================================== Eintrag / Foto erfassen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickLogScreen(
    vm: MainViewModel,
    preAnimals: List<String>,
    prePlants: List<String>,
    preLocation: String?,
    preKind: LogKind?,
    startCamera: Boolean,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val animals by vm.animals.collectAsState()
    val allLocations by vm.locations.collectAsState()
    val categories by vm.categories.collectAsState()
    val places = allLocations.filter { it.kind != "plant" }
    val plants = allLocations.filter { it.kind == "plant" }

    var kind by rememberSaveable { mutableStateOf(preKind ?: LogKind.OBSERVATION) }
    var animalIds by rememberSaveable { mutableStateOf<List<String>>(ArrayList(preAnimals.distinct())) }
    var plantIds by rememberSaveable { mutableStateOf<List<String>>(ArrayList(prePlants.distinct())) }
    var categoryIds by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var locationId by rememberSaveable { mutableStateOf(preLocation) }
    var photos by rememberSaveable { mutableStateOf<List<String>>(ArrayList()) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraStarted by rememberSaveable { mutableStateOf(false) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    var ts by rememberSaveable { mutableStateOf(System.currentTimeMillis() / 1000) }
    var timeFromPhoto by rememberSaveable { mutableStateOf(false) }
    var timeManual by rememberSaveable { mutableStateOf(false) }

    var useGeo by rememberSaveable { mutableStateOf(true) }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
    var accuracy by rememberSaveable { mutableStateOf<Double?>(null) }
    var geoSource by rememberSaveable { mutableStateOf("") }
    var geoInfo by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var attachToAssets by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableIntStateOf(0) }
    var photoError by remember { mutableStateOf<String?>(null) }

    var showAnimals by remember { mutableStateOf(false) }
    var showPlaces by remember { mutableStateOf(false) }
    var showPlants by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // ------------------------------------------------------------ Position
    val locate: () -> Unit = {
        if (!locating) {
          scope.launch {
            locating = true
            geoInfo = "Position wird bestimmt …"
            val l = GeoHelper.current(ctx)
            locating = false
            if (l != null) {
                lat = l.latitude; lon = l.longitude
                accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else null
                geoSource = "GPS"; geoInfo = null
            } else if (lat == null) {
                geoInfo = "Keine Position gefunden – GPS eingeschaltet?"
            } else {
                geoInfo = null
            }
          }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            locate()
        } else {
            geoInfo = "Standort-Berechtigung nicht erteilt"
        }
    }
    val locateOrAsk: () -> Unit = {
        if (GeoHelper.hasPermission(ctx)) {
            locate()
        } else if (!askedPermission) {
            askedPermission = true
            permissionLauncher.launch(GeoHelper.permissions)
        } else {
            geoInfo = "Standort-Berechtigung in den App-Einstellungen erlauben"
        }
    }

    // ------------------------------------------------------------ Fotos
    val handleImported: (PhotoStore.Imported?) -> Unit = { r ->
        if (r == null) {
            photoError = "Foto konnte nicht gelesen werden"
        } else {
            photos = ArrayList(photos + r.path)
            if (!timeManual && r.takenAt != null && photos.size == 1) {
                ts = r.takenAt; timeFromPhoto = true
            }
            if (r.latitude != null && r.longitude != null && geoSource != "Foto") {
                lat = r.latitude; lon = r.longitude; accuracy = null; geoSource = "Foto"
            }
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCapture
        pendingCapture = null
        if (ok && path != null) {
            scope.launch {
                importing++
                val r = withContext(Dispatchers.IO) {
                    val f = File(path)
                    val res = runCatching { PhotoStore.import(ctx, Uri.fromFile(f)) }.getOrNull()
                    f.delete()
                    res
                }
                importing--
                handleImported(r)
            }
        }
        if (useGeo && lat == null) locateOrAsk()
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = PhotoStore.newCaptureTarget(ctx)
        pendingCapture = file.absolutePath
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            photoError = "Keine Kamera-App gefunden"
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing += uris.size
            for (u in uris) {
                val r = withContext(Dispatchers.IO) { runCatching { PhotoStore.import(ctx, u) }.getOrNull() }
                importing--
                handleImported(r)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (startCamera && !cameraStarted) {
            cameraStarted = true
            launchCamera()          // Position erst nach der Aufnahme (App ist dann wieder im Vordergrund)
        } else if (useGeo && lat == null) {
            locateOrAsk()
        }
    }

    // ------------------------------------------------------------ Auswertung
    val chosenAnimals = animals.filter { it.id in animalIds }
    val chosenPlants = plants.filter { it.id in plantIds }
    val chosenPlace = places.firstOrNull { it.id == locationId }
    val chosenCategories = categories.filter { it.id in categoryIds }
    val zone = ZoneId.systemDefault()
    val zdt = Instant.ofEpochSecond(ts).atZone(zone)
    val withQuantity = kind == LogKind.INPUT || kind == LogKind.HARVEST || kind == LogKind.MEDICAL
    val hasSubjects = chosenAnimals.isNotEmpty() || chosenPlants.isNotEmpty() || chosenPlace != null
    val valid = importing == 0 && when (kind) {
        LogKind.MOVEMENT -> chosenPlace != null && chosenAnimals.isNotEmpty()
        else -> hasSubjects || photos.isNotEmpty() || name.isNotBlank() || notes.isNotBlank()
    }

    ScreenScaffold(title = if (startCamera || photos.isNotEmpty()) "Foto-Eintrag" else "Schnell-Log", onBack = onDone) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Fotos
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = launchCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Text("  Kamera")
                }
                FilledTonalButton(
                    onClick = {
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.PhotoLibrary, null); Text("  Galerie") }
            }
            if (importing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Foto wird verarbeitet …", style = MaterialTheme.typography.bodySmall)
                }
            }
            photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PhotoThumbs(
                urls = photos.map { "file://$it" },
                size = 88.dp,
                onRemove = { i -> photos = ArrayList(photos.filterIndexed { idx, _ -> idx != i }) },
            )

            // ---- Art
            Text("Art", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.label) }) }
            }

            // ---- Zuordnung
            Text("Zuordnung", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { showAnimals = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        chosenAnimals.isEmpty() -> "Tiere wählen" + if (kind == LogKind.MOVEMENT) " *" else ""
                        chosenAnimals.size <= 3 -> "Tiere: " + chosenAnimals.joinToString(", ") { it.name }
                        else -> "${chosenAnimals.size} Tiere gewählt"
                    }
                )
            }
            OutlinedButton(onClick = { showPlaces = true }, modifier = Modifier.fillMaxWidth()) {
                Text((if (kind == LogKind.MOVEMENT) "Ziel-Ort *: " else "Feld / Gebäude: ") + (chosenPlace?.name ?: "–"))
            }
            if (plants.isNotEmpty() || chosenPlants.isNotEmpty()) {
                OutlinedButton(onClick = { showPlants = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (chosenPlants.isEmpty()) "Kultur / Frucht wählen"
                        else "Kultur: " + chosenPlants.joinToString(", ") { it.name }
                    )
                }
            }
            if (categories.isNotEmpty()) {
                OutlinedButton(onClick = { showCategories = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (chosenCategories.isEmpty()) "Kategorie wählen"
                        else "Kategorie: " + chosenCategories.joinToString(", ") { it.name }
                    )
                }
            }

            // ---- Zeit
            Text("Zeit", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(formatLocalDate(zdt.toLocalDate()))
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(String.format(Locale.GERMAN, "%02d:%02d Uhr", zdt.hour, zdt.minute))
                }
            }
            if (timeFromPhoto && !timeManual) {
                Text("Aufnahmezeit aus dem Foto übernommen", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }

            // ---- Position
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GPS-Position speichern", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Switch(checked = useGeo, onCheckedChange = {
                            useGeo = it
                            if (it && lat == null) locateOrAsk()
                        })
                    }
                    if (useGeo) {
                        val la = lat
                        val lo = lon
                        if (la != null && lo != null) {
                            Text(
                                String.format(Locale.US, "%.6f, %.6f", la, lo) +
                                    (accuracy?.let { String.format(Locale.GERMAN, "  (±%.0f m)", it) } ?: "") +
                                    if (geoSource == "Foto") "  · aus dem Foto" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        geoInfo?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = locateOrAsk, enabled = !locating) {
                            Icon(Icons.Default.MyLocation, null)
                            Text(if (locating) "  Suche …" else "  Aktuelle Position verwenden")
                        }
                    }
                }
            }

            // ---- Menge
            if (withQuantity) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        qty, { qty = it }, label = { Text("Menge") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        unit, { unit = it }, label = { Text("Einheit (kg, l, Stk …)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OutlinedTextField(notes, { notes = it }, label = { Text("Notizen") },
                minLines = 3, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(name, { name = it }, label = { Text("Titel (optional)") },
                placeholder = { Text("wird automatisch erzeugt") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            if (photos.isNotEmpty() && (chosenAnimals.isNotEmpty() || chosenPlants.isNotEmpty())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = attachToAssets, onCheckedChange = { attachToAssets = it })
                    Text("Fotos zusätzlich direkt beim Tier / bei der Kultur ablegen",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    val subjects = chosenAnimals.map { it.name } + chosenPlants.map { it.name } +
                        listOfNotNull(chosenPlace?.name)
                    val prefix = if (photos.isNotEmpty() && kind == LogKind.OBSERVATION) "Foto" else kind.label
                    val autoName = buildString {
                        append(prefix)
                        if (kind == LogKind.MOVEMENT && chosenPlace != null) {
                            append(" nach ").append(chosenPlace.name)
                            if (chosenAnimals.isNotEmpty()) append(": ").append(shortList(chosenAnimals.map { it.name }))
                        } else if (subjects.isNotEmpty()) {
                            append(": ").append(shortList(subjects))
                        } else {
                            append(" vom ").append(formatLocalDate(zdt.toLocalDate()))
                        }
                    }
                    val title = name.ifBlank { autoName }
                    val exif = buildList {
                        add(title)
                        if (chosenAnimals.isNotEmpty()) add("Tiere: " + chosenAnimals.joinToString(", ") {
                            listOfNotNull(it.name, it.idTag).joinToString(" ")
                        })
                        if (chosenPlace != null) add("Ort: " + chosenPlace.name)
                        if (chosenPlants.isNotEmpty()) add("Kultur: " + chosenPlants.joinToString(", ") { it.name })
                        if (chosenCategories.isNotEmpty()) add("Kategorie: " + chosenCategories.joinToString(", ") { it.name })
                        if (notes.isNotBlank()) add(notes.trim())
                    }.joinToString(" | ")

                    vm.createLog(
                        LogDraft(
                            kind = kind,
                            name = title,
                            timestamp = ts,
                            notes = notes.ifBlank { null },
                            assets = chosenAnimals.map { AssetRef(it.id, "asset--animal", it.name) } +
                                chosenPlants.map { AssetRef(it.id, "asset--plant", it.name) },
                            locations = listOfNotNull(chosenPlace?.let { AssetRef(it.id, "asset--${it.kind}", it.name) }),
                            quantityValue = if (withQuantity) qty.ifBlank { null } else null,
                            quantityUnit = if (withQuantity) unit.ifBlank { null } else null,
                            photos = photos,
                            latitude = if (useGeo) lat else null,
                            longitude = if (useGeo) lon else null,
                            categoryIds = chosenCategories.map { it.id },
                            attachPhotosToAssets = attachToAssets,
                        ),
                        exifDescription = exif,
                    )
                    onDone()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (photos.isNotEmpty()) "Speichern (${photos.size} Foto${if (photos.size > 1) "s" else ""})" else "Speichern") }
            if (kind == LogKind.MOVEMENT) {
                Text("Die Tiere stehen danach in farmOS an diesem Ort.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text("Wird auch offline gespeichert und später übertragen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    // ------------------------------------------------------------ Dialoge
    if (showAnimals) {
        MultiPickerDialog(
            title = "Tiere",
            items = animals,
            key = { it.id },
            label = { listOfNotNull(it.name, it.idTag).joinToString(" · ") },
            initial = animalIds.toSet(),
            onDone = { animalIds = ArrayList(it); showAnimals = false },
            onDismiss = { showAnimals = false },
        )
    }
    if (showPlants) {
        MultiPickerDialog(
            title = "Kulturen",
            items = plants,
            key = { it.id },
            label = { listOfNotNull(it.name, it.subtype).joinToString(" · ") },
            initial = plantIds.toSet(),
            onDone = { plantIds = ArrayList(it); showPlants = false },
            onDismiss = { showPlants = false },
        )
    }
    if (showCategories) {
        MultiPickerDialog(
            title = "Kategorien",
            items = categories,
            key = { it.id },
            label = { it.name },
            initial = categoryIds.toSet(),
            onDone = { categoryIds = ArrayList(it); showCategories = false },
            onDismiss = { showCategories = false },
        )
    }
    if (showPlaces) {
        PickerDialog(
            title = "Feld / Gebäude",
            items = places,
            label = { "${it.name} (${kindLabel(it.kind)})" },
            selected = chosenPlace,
            onSelect = { locationId = it?.id; showPlaces = false },
            onDismiss = { showPlaces = false },
        )
    }
    if (showDate) {
        DateDialog(zdt.toLocalDate(), onPick = { d: LocalDate ->
            ts = d.atTime(zdt.toLocalTime()).atZone(zone).toEpochSecond()
            timeManual = true
        }, onDismiss = { showDate = false })
    }
    if (showTime) {
        TimeDialog(zdt.hour, zdt.minute, onPick = { h, m ->
            ts = zdt.withHour(h).withMinute(m).withSecond(0).toEpochSecond()
            timeManual = true
        }, onDismiss = { showTime = false })
    }
}

private fun shortList(names: List<String>): String =
    if (names.size <= 3) names.joinToString(", ") else names.take(2).joinToString(", ") + " +${names.size - 2}"
