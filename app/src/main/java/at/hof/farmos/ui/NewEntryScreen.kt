package at.hof.farmos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Compost
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.hof.farmos.data.FieldWork
import at.hof.farmos.data.LogKind

private class Action(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Erster Schritt: Was möchtest du erfassen? Danach kommt ein passendes Formular. */
@Composable
fun NewEntryScreen(
    onBack: () -> Unit,
    onFieldWork: (FieldWork) -> Unit,
    onQuickLog: (LogKind, Boolean) -> Unit,
    onNewAnimal: () -> Unit,
) {
    ScreenScaffold(title = "Was möchtest du erfassen?", onBack = onBack) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Group(
                "Auf dem Feld",
                listOf(
                    Action("Aussaat", Icons.Default.Grass) { onFieldWork(FieldWork.SEEDING) },
                    Action("Pflanzenschutz", Icons.Default.WaterDrop) { onFieldWork(FieldWork.SPRAYING) },
                    Action("Düngung", Icons.Default.Compost) { onFieldWork(FieldWork.FERTILIZING) },
                    Action("Ernte", Icons.Default.Agriculture) { onFieldWork(FieldWork.HARVEST) },
                ),
            )
            Group(
                "Bei den Tieren",
                listOf(
                    Action("Behandlung", Icons.Default.MedicalServices) { onQuickLog(LogKind.MEDICAL, false) },
                    Action("Fütterung", Icons.Default.Restaurant) { onQuickLog(LogKind.INPUT, false) },
                    Action("Umstallen / Weide", Icons.Default.SwapHoriz) { onQuickLog(LogKind.MOVEMENT, false) },
                    Action("Neues Tier", Icons.Default.Pets) { onNewAnimal() },
                ),
            )
            Group(
                "Allgemein",
                listOf(
                    Action("Foto", Icons.Default.CameraAlt) { onQuickLog(LogKind.OBSERVATION, true) },
                    Action("Beobachtung", Icons.Default.Visibility) { onQuickLog(LogKind.OBSERVATION, false) },
                    Action("Sonstige Arbeit", Icons.Default.Build) { onQuickLog(LogKind.ACTIVITY, false) },
                ),
            )
        }
    }
}

@Composable
private fun Group(title: String, actions: List<Action>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    actions.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { a -> ActionTile(a, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionTile(a: Action, modifier: Modifier) {
    Card(modifier.height(96.dp).clickable { a.onClick() }) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(a.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(a.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
