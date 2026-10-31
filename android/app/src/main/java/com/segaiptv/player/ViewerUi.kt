package com.segaiptv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ViewerHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { ViewerStore.get(context) }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) store.relock() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density * store.scale, density.fontScale)) { content() }
}

@Composable
internal fun PinDialog(setup: Boolean = false, onClose: () -> Unit, onSuccess: () -> Unit) {
    val store = ViewerStore.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(fx(if (setup) "set_pin" else "enter_pin")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fx("pin_hint"), fontSize = 12.sp)
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (setup) OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8) }, singleLine = true,
                    label = { Text(fx("confirm_pin")) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (failed) Text(fx("pin_error"), color = v3Gold)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                val valid = if (setup) {
                    if (pin.matches(Regex("[0-9]{4,8}")) && pin == confirm) {
                        withContext(Dispatchers.Default) { store.setPin(pin) }; true
                    } else false
                } else withContext(Dispatchers.Default) { store.verify(pin) }
                busy = false
                if (valid) onSuccess() else { failed = true; pin = ""; confirm = "" }
            }
        }) { Text(fx("confirm")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(tr(R.string.close)) } })
}

@Composable
internal fun ViewerSettings(channels: List<Channel>, warnings: String, error: String, loading: Boolean) {
    val context = LocalContext.current
    val store = remember { ViewerStore.get(context) }
    var open by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    TvAction(fx("viewer_settings")) { open = true }
    if (pinMode != null) PinDialog(setup = pinMode == "setup", onClose = { pinMode = null }) {
        val setup = pinMode == "setup"
        pinMode = null
        if (!setup) manage = true
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.85f).fillMaxHeight(.9f).background(v3Panel).padding(14.dp)) {
            Text(fx("viewer_settings"), color = v3Gold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(fx("ui_size"), color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(.85f to "compact", 1f to "normal", 1.15f to "large").forEach { (scale, label) ->
                            TvAction(fx(label), selected = store.scale == scale) { store.changeScale(scale) }
                        }
                    }
                }
                item { TvAction(fx("parental")) { pinMode = if (store.pinEnabled) "verify" else "setup" } }
                item { Text(fx("parental_hint"), color = Color.LightGray, fontSize = 12.sp) }
                item { TvAction(fx("clear_history")) { store.clearHistory() } }
                item { TvAction(fx("diagnostics")) { diagnostics = true } }
            }
            TvAction(tr(R.string.close)) { open = false }
        }
    }
    if (manage && !store.unlocked) PinDialog(onClose = { manage = false }, onSuccess = {})
    if (manage && store.unlocked) Dialog(onDismissRequest = { manage = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.85f).fillMaxHeight(.9f).background(v3Panel).padding(12.dp)) {
            Text(fx("lock_bouquets"), color = v3Gold)
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(tr(R.string.search)) })
            val groups = remember(channels, query) { channels.distinctBy { store.groupKey(it) }
                .filter { it.category.contains(query, true) || it.playlist.contains(query, true) } }
            LazyColumn(Modifier.weight(1f)) {
                items(groups, key = { store.groupKey(it) }) { c ->
                    TvAction((if (store.isLocked(c)) "🔒 " else "") + c.playlist + " · " + c.category, Modifier.fillMaxWidth()) {
                        store.lock(c, true); manage = false
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(fx("remove_locks")) { store.clearLocks(); manage = false }
                TvAction(tr(R.string.close)) { manage = false; store.relock() }
            }
        }
    }
    if (diagnostics) {
        var proofFingerprint by remember { mutableStateOf("—") }
        LaunchedEffect(Unit) { proofFingerprint = withContext(Dispatchers.IO) { DeviceProof.fingerprint() } }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text(fx("diagnostics")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SEGA PLAYER V3 " + BuildConfig.VERSION_NAME)
                    if (BuildConfig.DEVICE_PROOF) androidx.compose.foundation.text.selection.SelectionContainer {
                        Text("Installation SHA-256: " + proofFingerprint, fontSize = 10.sp)
                    }
                    val lastCrash = remember { CrashDiagnostics.last(context) }
                    if (lastCrash.isNotBlank()) Text(lastCrash, fontSize = 11.sp)
                    Text(fx(if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "network_ok" else "network_check"))
                    Text(fx(if (loading) "catalogue_loading" else if (channels.isNotEmpty()) "catalogue_ok" else "catalogue_empty"))
                    Text(fx(if (error.isNotBlank() || warnings.lines().any { it.endsWith("unavailable") }) "source_problem" else "source_ok"))
                    Text(fx("player_status") + ": " + store.lastPlaybackCode.ifBlank { "—" })
                }
            }, confirmButton = { TextButton(onClick = { diagnostics = false }) { Text(tr(R.string.close)) } })
    }
}

@Composable
internal fun WatchHistory(onPlay: (Channel) -> Unit, channels: List<Channel>) {
    val store = ViewerStore.get(LocalContext.current)
    var open by remember { mutableStateOf<Boolean?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvAction(fx("continue")) { open = true }
        TvAction(fx("recent")) { open = false }
    }
    if (open != null) Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Ignore removed providers; never replay a stale subscription from history.
        val sources = remember(channels) { channels.map { it.sourceUrl }.toSet() }
        val records = store.history(open!!).filter { it.channel.sourceUrl in sources }
        Column(Modifier.fillMaxWidth(.9f).fillMaxHeight(.85f).background(v3Panel).padding(14.dp)) {
            Text(fx(if (open == true) "continue" else "recent"), color = v3Gold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (records.isEmpty()) item { Text(fx("empty_history"), color = Color.LightGray) }
                items(records, key = { it.channel.url }) { e ->
                    TvAction(e.channel.name + (if (open == true) " · ${e.position / 60000} min" else ""),
                        Modifier.fillMaxWidth()) { open = null; onPlay(e.channel) }
                }
            }
            TvAction(tr(R.string.close)) { open = null }
        }
    }
}

