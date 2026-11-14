package com.segaiptv.player

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(NetworkClient.mode) }
    val active by CustomerVpn.active.collectAsState()
    var hasProfile by remember { mutableStateOf(CustomerVpn.hasProfile(context)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun operation(task: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { task(); hasProfile = CustomerVpn.hasProfile(context) }
            catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) operation { CustomerVpn.connect(context) }
        else failed = true
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation { CustomerVpn.importProfile(context, uri) }
    }
    TvAction("DNS · VPN", modifier) { open = true }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false },
        title = { Text(tr(R.string.connection_settings)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.dns_hint))
            DnsMode.entries.forEach { mode ->
                val label = when(mode) {
                    DnsMode.AUTOMATIC -> tr(R.string.dns_automatic)
                    DnsMode.GOOGLE -> "Google DNS"
                    DnsMode.CLOUDFLARE -> "Cloudflare DNS"
                }
                TvAction(label, Modifier.fillMaxWidth(), selected == mode) {
                    selected = mode; NetworkClient.select(context, mode)
                }
            }
            HorizontalDivider()
            Text(tr(R.string.vpn_hint))
            Text(tr(if (active) R.string.vpn_connected else R.string.vpn_disconnected))
            if (hasProfile) Text(tr(R.string.vpn_profile_saved))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!busy) {
                if (!active) TvAction(tr(R.string.vpn_import)) { import.launch(arrayOf("*/*")) }
                if (hasProfile || active) TvAction(tr(if (active) R.string.vpn_disconnect else R.string.vpn_connect)) {
                    if (active) operation { CustomerVpn.disconnect() }
                    else {
                        val request = VpnService.prepare(context)
                        if (request == null) operation { CustomerVpn.connect(context) }
                        else permission.launch(request)
                    }
                }
                if (hasProfile && !active) TvAction(tr(R.string.vpn_remove)) { operation { CustomerVpn.remove(context) } }
            }
            if (failed) Text(tr(R.string.vpn_error), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text(tr(R.string.close)) } })
}
