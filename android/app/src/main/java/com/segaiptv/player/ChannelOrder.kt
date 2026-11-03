package com.segaiptv.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

@Composable
internal fun SettingsGroup(title: String, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(appTheme.surface, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(title, color = appTheme.accent, fontSize = 13.sp)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

private enum class Order(val title: Int) {
    PROVIDER(R.string.order_provider), AZ(R.string.order_az), ZA(R.string.order_za), MANUAL(R.string.order_manual)
}
@Composable
private fun orderPrefs() = LocalContext.current.getSharedPreferences("channel_order", Context.MODE_PRIVATE)

@Composable
internal fun ChannelOrderPicker(openChannels: () -> Unit) {
    val prefs = orderPrefs()
    var mode by remember { mutableStateOf(prefs.getString("mode", Order.PROVIDER.name)) }
    var open by remember { mutableStateOf(false) }
    TvAction(tr(R.string.channel_order) + " · " + tr(Order.entries.firstOrNull { it.name == mode }?.title ?: R.string.order_provider)) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(tr(R.string.channel_order)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Order.entries.forEach { option ->
                TvAction(tr(option.title), Modifier.fillMaxWidth(), option.name == mode) {
                    mode = option.name
                    prefs.edit().putString("mode", option.name).apply()
                    open = false
                    if (option == Order.MANUAL) openChannels()
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(tr(R.string.close)) } })
}

private fun orderId(value: String): String =
    android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()), android.util.Base64.NO_WRAP)

@Composable
internal fun orderedChannels(scope: String, filtered: List<Channel>, channels: List<Channel>, canEdit: Boolean): List<Channel> {
    val prefs = orderPrefs()
    val mode = prefs.getString("mode", Order.PROVIDER.name)
    if (mode != Order.MANUAL.name) return remember(mode, filtered) {
        when (mode) {
            Order.AZ.name -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            Order.ZA.name -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> filtered
        }
    }
    val key = remember(scope) { "order_" + orderId(scope) }
    var saved by remember(key) { mutableStateOf(prefs.getString(key, "").orEmpty()) }
    val ids = remember(channels) { channels.associate { it.url to orderId(it.sourceUrl + "\n" + it.url) } }
    val positions = remember(saved) { saved.lineSequence().withIndex().associate { it.value to it.index } }
    val sorted = remember(filtered, positions, ids) { filtered.sortedBy { positions[ids[it.url]] ?: Int.MAX_VALUE } }
    var editing by remember(key) { mutableStateOf(false) }
    if (canEdit && sorted.size > 1) TvAction(tr(R.string.order_edit)) { editing = true }
    if (editing) {
        val draft = remember(sorted) { mutableStateListOf<Channel>().apply { addAll(sorted) } }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(tr(R.string.order_edit)) },
            text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                itemsIndexed(draft) { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${item.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = index > 0, onClick = { draft.add(index - 1, draft.removeAt(index)) }) { Text("↑") }
                        TextButton(enabled = index < draft.lastIndex, onClick = { draft.add(index + 1, draft.removeAt(index)) }) { Text("↓") }
                    }
                }
            } }, confirmButton = { TextButton(onClick = {
                saved = draft.joinToString("\n") { ids.getValue(it.url) }
                prefs.edit().putString(key, saved).apply()
                editing = false
            }) { Text(tr(R.string.order_save)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(tr(R.string.close)) } })
    }
    return sorted
}
