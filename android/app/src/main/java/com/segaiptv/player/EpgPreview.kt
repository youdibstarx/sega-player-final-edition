package com.segaiptv.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

@Composable
internal fun EpgPreview(channel: Channel?) {
    var programmes by remember(channel?.url) { mutableStateOf<List<EpgProgramme>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel?.url) {
        if (channel == null || isVod(channel)) return@LaunchedEffect
        delay(1200) // Do not fetch while rapidly browsing.
        while (true) {
            try { programmes = ProgrammeData.epg(channel) }
            catch (e: Exception) { if (e is CancellationException) throw e; programmes = emptyList() }
            delay(300_000)
        }
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val current = programmes.firstOrNull { it.start <= now && it.end > now }
    val next = programmes.firstOrNull { it.start > now }
    if (current != null || next != null) Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
        if (current != null) {
            Text(tr(R.string.epg_now) + ": " + current.title, color = Color.White, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { ((now - current.start).toFloat() / (current.end - current.start)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = v3Gold)
        }
        if (next != null) Text(fx("epg_next") + ": " + next.title, color = Color.LightGray, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
