package at.hof.farmos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.db.LocationEntity
import at.hof.farmos.photo.GeoHelper
import kotlinx.coroutines.launch
import java.util.Locale

// ==================================================================== Teilen

fun mapsUrl(lat: Double, lon: Double): String =
    String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

/** Text für WhatsApp & Co. – der Kartenlink wird dort als Standort-Vorschau angezeigt. */
fun positionText(l: LocationEntity, lat: Double, lon: Double, crops: List<String> = emptyList()): String =
    buildString {
        append("📍 ").append(kindLabel(l.kind)).append(" „").append(l.name).append("“")
        l.areaInfo?.let { append(" (").append(it).append(")") }
        if (crops.isNotEmpty()) append("\nKultur: ").append(crops.joinToString(", "))
        append("\n").append(mapsUrl(lat, lon))
    }

/** Öffnet WhatsApp (oder WhatsApp Business) direkt, sonst die normale Teilen-Auswahl. */
fun shareWhatsApp(context: Context, text: String) {
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(pkg)
        }
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // nächste Variante probieren
        }
    }
    Toast.makeText(context, "WhatsApp nicht gefunden – bitte App auswählen", Toast.LENGTH_SHORT).show()
    shareAny(context, text)
}

fun shareAny(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Position teilen"))
}

/** Navigation in Google Maps (oder einer anderen Karten-App) starten. */
fun openNavigation(context: Context, lat: Double, lon: Double, label: String) {
    val geo = Uri.parse(
        String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label))
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, geo))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl(lat, lon))))
    }
}

/**
 * Liest Koordinaten aus Text, z. B. „48.12345, 16.12345“, „48,12345 16,12345“
 * oder einem eingefügten Google-Maps-Link.
 */
fun parseCoordinates(input: String): Pair<Double, Double>? {
    val nums = Regex("-?[0-9]+(?:[.,][0-9]+)?").findAll(input)
        .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
    for (i in 0 until nums.size - 1) {
        val la = nums[i]
        val lo = nums[i + 1]
        // Nur Zahlen mit Nachkommastellen ernst nehmen (keine Hausnummern o. Ä.)
        if (la in -90.0..90.0 && lo in -180.0..180.0 && la % 1.0 != 0.0 && lo % 1.0 != 0.0) return la to lo
    }
    return null
}

// ==================================================================== Karte auf der Feldseite

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PositionCard(vm: MainViewModel, l: LocationEntity, crops: List<String>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pos = l.position()
    val canSet = l.kind != "plant"
    var locating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Triple<Double, Double, Float?>?>(null) }
    var showInput by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val locate: () -> Unit = {
        if (!locating) {
            scope.launch {
                locating = true
                info = "Position wird bestimmt … (draußen geht es schneller)"
                val loc = GeoHelper.current(ctx, timeoutMs = 30_000)
                locating = false
                if (loc != null) {
                    info = null
                    found = Triple(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
                } else {
                    info = "Keine Position gefunden – ist GPS eingeschaltet?"
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) {
            locate()
        } else {
            info = "Standort-Berechtigung nicht erteilt"
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📍 Position", style = MaterialTheme.typography.titleSmall)
            if (pos != null) {
                Text(String.format(Locale.US, "%.6f, %.6f", pos.first, pos.second),
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (l.pinLat != null) "In der App gesetzt" else "Mittelpunkt laut farmOS-Karte",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                val text = positionText(l, pos.first, pos.second, crops)
                Button(onClick = { shareWhatsApp(ctx, text) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Per WhatsApp senden")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareAny(ctx, text) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null); Text("  Teilen")
                    }
                    OutlinedButton(onClick = { openNavigation(ctx, pos.first, pos.second, l.name) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Directions, null); Text("  Navigation")
                    }
                }
            } else {
                Text(
                    if (canSet) "Noch keine Position. Stell dich z. B. an die Zufahrt und übernimm den Standort, " +
                        "oder füge Koordinaten aus Google Maps ein."
                    else "Diese Kultur hat noch keine Position (die Fläche, auf der sie steht, braucht eine).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (canSet) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { if (GeoHelper.hasPermission(ctx)) locate() else permission.launch(GeoHelper.permissions) },
                        enabled = !locating,
                    ) {
                        Icon(Icons.Default.MyLocation, null)
                        Text(if (pos == null) "  Aktuellen Standort übernehmen" else "  Neu setzen (Standort)")
                    }
                    TextButton(onClick = { showInput = true }) {
                        Icon(Icons.Default.EditLocation, null); Text("  Koordinaten eingeben")
                    }
                    if (l.pinLat != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Gesetzte Position löschen") }
                    }
                }
            }
            if (locating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Suche GPS …", style = MaterialTheme.typography.bodySmall)
                }
            }
            info?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    found?.let { (la, lo, acc) ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Position übernehmen?") },
            text = {
                Text(
                    String.format(Locale.US, "%.6f, %.6f", la, lo) +
                        (acc?.let { String.format(Locale.GERMAN, "\nGenauigkeit: ±%.0f m", it) } ?: "") +
                        "\n\nAls Position für „${l.name}“ speichern?"
                )
            },
            confirmButton = { TextButton(onClick = { vm.setPosition(l, la, lo); found = null }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Abbrechen") } },
        )
    }
    if (showInput) {
        var text by remember { mutableStateOf("") }
        val parsed = parseCoordinates(text)
        AlertDialog(
            onDismissRequest = { showInput = false },
            title = { Text("Koordinaten eingeben") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "In Google Maps lange auf die Stelle drücken, Koordinaten kopieren und hier einfügen " +
                            "(z. B. 48.12345, 16.12345). Ein Maps-Link geht auch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(text, { text = it }, label = { Text("Breite, Länge") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    if (text.isNotBlank()) {
                        Text(
                            parsed?.let { String.format(Locale.US, "Erkannt: %.6f, %.6f", it.first, it.second) }
                                ?: "Keine gültigen Koordinaten erkannt",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (parsed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { parsed?.let { vm.setPosition(l, it.first, it.second) }; showInput = false },
                    enabled = parsed != null,
                ) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { showInput = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Gesetzte Position löschen?") },
            text = { Text("Danach wird – falls vorhanden – wieder der Mittelpunkt aus der farmOS-Karte verwendet.") },
            confirmButton = { TextButton(onClick = { vm.setPosition(l, null, null); confirmDelete = false }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
