package at.hof.farmos.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wird nach einem Absturz statt der App angezeigt – bis man auf „Weiter“ tippt. */
@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Die App ist abgestürzt", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Bitte tippe auf „Teilen“ und schick den Bericht an Claude (oder kopiere ihn). " +
                "Damit lässt sich der Fehler gezielt beheben.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { share(context, report) }) { Text("Teilen") }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Kopieren") }
            OutlinedButton(onClick = onContinue) { Text("Weiter") }
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "farmOS Hof – Absturzbericht")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Absturzbericht teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
