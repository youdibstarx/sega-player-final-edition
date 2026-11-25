package com.segaiptv.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ServerRecoveryScreen(
    busy: Boolean,
    result: BackendEndpoints.RecoveryResult?,
    onConnect: (String) -> Unit,
    onRetry: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val error = when (result) {
        BackendEndpoints.RecoveryResult.INVALID_URL -> R.string.recovery_invalid
        BackendEndpoints.RecoveryResult.OFFLINE -> R.string.recovery_offline
        BackendEndpoints.RecoveryResult.KEY_UNAVAILABLE -> R.string.recovery_key_missing
        BackendEndpoints.RecoveryResult.UNREACHABLE -> R.string.recovery_unreachable
        BackendEndpoints.RecoveryResult.UNTRUSTED -> R.string.recovery_untrusted
        BackendEndpoints.RecoveryResult.STORAGE_FAILED -> R.string.recovery_storage
        else -> null
    }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SEGA PLAYER", style = MaterialTheme.typography.titleLarge)
                Text(tr(R.string.recovery_title), style = MaterialTheme.typography.titleMedium)
                Text(tr(R.string.recovery_explanation), textAlign = TextAlign.Center)
                OutlinedTextField(value = input, onValueChange = { if (it.length <= 270) input = it },
                    enabled = !busy, singleLine = true,
                    label = { Text(tr(R.string.recovery_url)) },
                    placeholder = { Text("https://example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                if (error != null) Text(tr(error), color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                Button(onClick = { onConnect(input) }, enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.recovery_connect)) }
                OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(R.string.recovery_retry))
                }
            }
        }
    }
}
