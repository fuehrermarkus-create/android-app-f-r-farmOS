package at.hof.farmos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.LogKind
import at.hof.farmos.data.db.LogEntity
import at.hof.farmos.data.db.urlList
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

fun formatDate(epochSeconds: Long?): String =
    epochSeconds?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(dateFmt) } ?: "–"

fun formatDateTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dateTimeFmt)

fun formatLocalDate(d: LocalDate?): String = d?.format(dateFmt) ?: "–"

fun sexLabel(sex: String?): String = when (sex) {
    "F" -> "weiblich"
    "M" -> "männlich"
    else -> "–"
}

@Composable
fun EmptyHint(text: String, icon: ImageVector? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.outline)
            Text(
                text,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun LogRow(log: LogEntity) {
    ListItem(
        headlineContent = { Text(log.name.ifBlank { LogKind.labelFor(log.type, log.isMovement) }) },
        supportingContent = {
            Column {
                Text(
                    "${LogKind.labelFor(log.type, log.isMovement)} · ${formatDateTime(log.timestamp)}" +
                        if (log.hasGeo) " · 📍" else ""
                )
                if (!log.notes.isNullOrBlank()) Text(log.notes, maxLines = 2)
                val photos = log.imageUrls.urlList()
                if (photos.isNotEmpty()) {
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
                    PhotoThumbs(photos, size = 56.dp)
                }
            }
        },
        trailingContent = if (log.pending) {
            { Icon(Icons.Default.CloudUpload, contentDescription = "Noch nicht übertragen",
                tint = MaterialTheme.colorScheme.secondary) }
        } else null,
    )
    HorizontalDivider()
}

/** Einfachauswahl aus einer Liste. */
@Composable
fun <T> PickerDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    selected: T?,
    onSelect: (T?) -> Unit,
    onDismiss: () -> Unit,
    allowNone: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    val filtered = items.filter { query.isBlank() || label(it).contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (items.size > 8) SearchField(query) { query = it }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (allowNone) item {
                        Row(Modifier.fillMaxWidth().clickable { onSelect(null) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected == null, onClick = { onSelect(null) })
                            Text("– keine –")
                        }
                    }
                    items(filtered) { item ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(item) },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = item == selected, onClick = { onSelect(item) })
                            Text(label(item))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Mehrfachauswahl, z. B. mehrere Tiere für einen Log. */
@Composable
fun <T> MultiPickerDialog(
    title: String,
    items: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(initial) }
    val filtered = items.filter { query.isBlank() || label(it).contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SearchField(query) { query = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = chosen + filtered.map(key) }) { Text("Alle") }
                    TextButton(onClick = { chosen = emptySet() }) { Text("Keine") }
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(filtered, key = key) { item ->
                        val k = key(item)
                        val toggle = { chosen = if (k in chosen) chosen - k else chosen + k }
                        Row(Modifier.fillMaxWidth().clickable { toggle() },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = k in chosen, onCheckedChange = { toggle() })
                            Text(label(item))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Übernehmen (${chosen.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("Suchen …") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    ) {
        DatePicker(state = state)
    }
}

/** Seitengerüst mit Titelleiste für alle Unterseiten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = actions,
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        floatingActionButton = fab,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(hour: Int, minute: Int, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uhrzeit") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
