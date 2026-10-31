package com.segaiptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SynopsisDialog(title: String, synopsis: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth(.85f).fillMaxHeight(.85f).background(v3Panel).padding(16.dp)) {
            Text(title, color = v3Gold, fontSize = 18.sp, maxLines = 2)
            Text(synopsis, color = Color.White, fontSize = 15.sp,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp)
                    .verticalScroll(rememberScrollState()).focusRequester(focus).focusable())
            TvAction(tr(R.string.close), onClick = onClose)
        }
    }
}

@Composable
internal fun EpgDialog(channel: Channel, onClose: () -> Unit) {
    var programmes by remember(channel.url) { mutableStateOf<List<EpgProgramme>>(emptyList()) }
    var loading by remember(channel.url) { mutableStateOf(true) }
    var failed by remember(channel.url) { mutableStateOf(false) }
    var retry by remember(channel.url) { mutableIntStateOf(0) }
    LaunchedEffect(channel.url, retry) {
        loading = true; failed = false
        try { programmes = ProgrammeData.epg(channel, retry > 0) }
        catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            failed = true
        } finally { loading = false }
    }
    val language = LocalAppLanguage.current.code
    val formatter = remember(language) {
        DateTimeFormatter.ofPattern("EEE HH:mm", Locale.forLanguageTag(language))
    }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxWidth(.9f).fillMaxHeight(.9f).background(v3Panel).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EPG · ${channel.name}", color = v3Gold, fontSize = 18.sp, maxLines = 1)
            Text(tr(R.string.epg_local_time), color = Color.LightGray, fontSize = 10.sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!loading && programmes.isEmpty()) Text(tr(if (failed) R.string.epg_error else R.string.no_epg), color = Color.LightGray)
                programmes.forEach { entry ->
                    val zone = ZoneId.systemDefault()
                    val times = formatter.format(Instant.ofEpochMilli(entry.start).atZone(zone)) + " — " +
                        formatter.format(Instant.ofEpochMilli(entry.end).atZone(zone))
                    val now = System.currentTimeMillis()
                    Text((if (entry.start <= now && entry.end > now) tr(R.string.epg_now) + " · " else "") + times,
                        color = v3Gold, fontSize = 12.sp)
                    Text(entry.title, color = Color.White, fontSize = 16.sp)
                    if (entry.description.isNotBlank()) Text(entry.description, color = Color.LightGray, fontSize = 13.sp)
                    HorizontalDivider(color = Color.DarkGray)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction(tr(R.string.close), onClick = onClose)
                if (!loading) TvAction(tr(R.string.refresh)) { retry++ }
            }
        }
    }
}
