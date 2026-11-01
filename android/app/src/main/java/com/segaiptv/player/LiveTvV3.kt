package com.segaiptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

internal val v3Gold: Color
    @Composable get() = appTheme.accent
internal val v3Panel: Color
    @Composable get() = appTheme.panel

/** Clickable provides D-pad/Enter handling. Focus is separate from the selected stream. */
@Composable
fun TvAction(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = if (selected || focused) v3Gold else Color.White, maxLines = 1,
        overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(if (selected || focused) appTheme.selected else v3Panel)
            .border(if (focused) 2.dp else 0.dp, if (focused) v3Gold else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp))
}

@Composable
fun LiveTvV3(modifier: Modifier = Modifier, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, warnings: String, onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit, includeVod: Boolean = false, search: String = "", showCategories: Boolean = true) {
    val live = remember(channels, includeVod) {
        if (includeVod) channels else channels.filter { !isVodEntry(it, false) && !isVodEntry(it, true) }
    }
    val groups = remember(live) { live.groupBy { it.category.ifBlank { "Other" } } }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groups) { if (category !in groups) category = groups.keys.firstOrNull() }
    val visible = remember(live, groups, category, onlyFavorites, favorites, search) {
        (if (onlyFavorites) live.filter { it.url in favorites } else if (search.isNotBlank()) live else groups[category].orEmpty())
            .filter { search.isBlank() || it.name.contains(search, true) }
    }
    var epgOpen by remember(selectedUrl) { mutableStateOf(false) }
    val selected = remember(live, selectedUrl) { live.firstOrNull { it.url == selectedUrl } }
    if (epgOpen && selected != null) EpgDialog(selected) { epgOpen = false }
    Column(modifier.fillMaxSize().background(appTheme.background)) {
        Text(tr(R.string.live_counts, groups.size, live.size), color = Color.LightGray,
            fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
        if (warnings.isNotBlank()) Text(statusText(warnings), color = v3Gold, maxLines = 1, fontSize = 11.sp)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showCategories) LazyColumn(Modifier.weight(.95f).fillMaxHeight().background(v3Panel),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { TvAction(tr(R.string.favorites_count, live.count { it.url in favorites }), Modifier.fillMaxWidth(), onlyFavorites) {
                    onlyFavorites = true
                } }
                itemsIndexed(groups.keys.toList()) { _, name ->
                    TvAction("$name  (${groups[name]?.size ?: 0})", Modifier.fillMaxWidth(), !onlyFavorites && category == name) {
                        category = name; onlyFavorites = false
                    }
                }
            }
            LazyColumn(Modifier.weight(1.12f).fillMaxHeight().background(v3Panel),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!showCategories) item {
                    TvAction(tr(R.string.favorites_count, live.count { it.url in favorites }), Modifier.fillMaxWidth(), onlyFavorites) {
                        onlyFavorites = !onlyFavorites
                    }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold) }
                if (!loading && visible.isEmpty()) item { Text(tr(R.string.no_channels), color = Color.LightGray, modifier = Modifier.padding(5.dp)) }
                itemsIndexed(visible, key = { i, ch -> "$i-${ch.url}" }) { index, ch ->
                    var focused by remember { mutableStateOf(false) }
                    val active = ch.url == selectedUrl
                    Row(Modifier.fillMaxWidth().height(34.dp).onFocusChanged { focused = it.isFocused }
                        .background(if (active || focused) appTheme.selected else appTheme.row)
                        .border(if (focused) 2.dp else 0.dp, if (focused) v3Gold else Color.Transparent)
                        .clickable { if (selectedUrl == ch.url) onPlay(ch) else selectedUrl = ch.url }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(22.dp))
                        if (ch.logo.isNotBlank()) AsyncImage(ch.logo, null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                        else Text("▶", color = v3Gold, modifier = Modifier.width(20.dp))
                        Text(ch.name, color = if (active) v3Gold else Color.White, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(2.05f).fillMaxHeight()) {
              val previewHeight = minOf(maxWidth * 9f / 16f, (maxHeight - 105.dp).coerceAtLeast(40.dp))
              Column(Modifier.fillMaxSize()) {
                MediaSurface(selected, Modifier.fillMaxWidth().height(previewHeight))
                Text(selected?.name ?: tr(R.string.live_tv), color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(5.dp))
                EpgPreview(selected)
                Spacer(Modifier.weight(1f))
                if (selected != null) Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isVodEntry(selected, false) && !isVodEntry(selected, true))
                        TvAction("EPG") { epgOpen = true }
                    TvAction(tr(R.string.full_screen), Modifier.weight(1f)) { onPlay(selected) }
                    TvAction((if (selected.url in favorites) "♥ " else "♡ ") + tr(R.string.favorite), Modifier.weight(1f)) { onToggleFavorite(selected) }
                }
              }
            }
        }
    }
}

