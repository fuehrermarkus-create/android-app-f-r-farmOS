package at.hof.farmos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(vm: MainViewModel) {
    val busy by vm.loginBusy.collectAsState()
    val error by vm.loginError.collectAsState()
    var url by rememberSaveable { mutableStateOf(vm.prefs.serverUrl.ifBlank { "http://192.168.68.113" }) }
    var user by rememberSaveable { mutableStateOf(vm.prefs.username) }
    var pass by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var clientId by rememberSaveable { mutableStateOf(vm.prefs.clientId) }
    var scope by rememberSaveable { mutableStateOf(vm.prefs.scope) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Default.Agriculture, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("farmOS Hof", style = MaterialTheme.typography.headlineMedium)
        Text("Mit deinem farmOS-Server verbinden", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Server-Adresse") },
            supportingText = { Text("z. B. http://192.168.68.113 oder Tailscale-Adresse") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Benutzername") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Passwort") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Erweitert ausblenden" else "Erweiterte Einstellungen")
        }
        if (advanced) {
            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it },
                label = { Text("OAuth Client-ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = scope, onValueChange = { scope = it },
                label = { Text("Scope") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                vm.prefs.clientId = clientId.ifBlank { "farm" }
                vm.prefs.scope = scope.ifBlank { "farm_manager" }
                vm.login(url, user.trim(), pass)
            },
            enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("Anmelden")
        }
        Text(
            "Das Passwort wird nicht gespeichert – die App merkt sich nur ein Zugriffstoken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
