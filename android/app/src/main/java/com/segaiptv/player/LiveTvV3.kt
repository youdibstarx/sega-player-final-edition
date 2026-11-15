package com.segaiptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage

internal val v3Gold: Color
    @Composable get() = appTheme.accent
internal val v3Panel: Color
    @Composable get() = appTheme.panel

/** Clickable provides D-pad/Enter handling. Focus is separate from the selected stream. */
@Composable
fun TvAction(label: String, modifier: Modifier = Modifier, selected: Boolean = false,
    fontSize: TextUnit = 12.sp, verticalPadding: androidx.compose.ui.unit.Dp = 7.dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = if (selected || focused) v3Gold else Color.White, maxLines = 1,
        overflow = TextOverflow.Ellipsis, fontSize = fontSize,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(if (selected || focused) appTheme.selected else v3Panel)
            .border(if (focused) 2.dp else 0.dp, if (focused) v3Gold else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = verticalPadding))
}

@Composable
fun LiveTvV3(modifier: Modifier = Modifier, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, warnings: String, onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit, includeVod: Boolean = false, search: String = "", showCategories: Boolean = true, orderScope: String? = null) {
    val live = rememberCatalogueWork<List<Channel>>(emptyList(), channels, includeVod) {
        if (includeVod) channels else channels.filter { catalogueKind(it) == "live" }
    }
    val groups = rememberCatalogueWork<Map<String, List<Channel>>>(emptyMap(), live) { live.groupBy { it.category.ifBlank { "Other" } } }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groups) { if (category !in groups) category = groups.keys.firstOrNull() }
    val filtered = rememberCatalogueWork<List<Channel>>(emptyList(), live, groups, category, onlyFavorites, favorites, search) {
        (if (onlyFavorites) live.filter { it.url in favorites } else if (search.isNotBlank()) live else groups[category].orEmpty())
            .filter { search.isBlank() || it.name.contains(search, true) }
    }
    val visible = if (orderScope == null) filtered else orderedChannels(orderScope, filtered, live, search.isBlank() && !onlyFavorites)
    val channelListState = rememberLazyListState()
    val selectedFocus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTvDevice = remember(context) {
        val pm = context.packageManager
        val tvMode = (context.getSystemService(android.content.Context.UI_MODE_SERVICE)
            as? android.app.UiModeManager)?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION) || tvMode
    }
    val lastPlayedUrl = remember { ViewerStore.get(context).history(false).firstOrNull()?.channel?.url }
    var restoredSelection by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible.isEmpty()) return@LaunchedEffect
        if (!restoredSelection) {
            lastPlayedUrl?.takeIf { url -> visible.any { it.url == url } }?.let { selectedUrl = it }
            restoredSelection = true
        }
        val index = visible.indexOfFirst { it.url == selectedUrl }
        if (index >= 0) {
            channelListState.scrollToItem(index + (if (showCategories) 0 else 1) + (if (loading) 1 else 0))
            kotlinx.coroutines.yield()
            try { selectedFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    var epgOpen by remember(selectedUrl) { mutableStateOf(false) }
    val selected = remember(live, selectedUrl) { live.firstOrNull { it.url == selectedUrl } }
    if (epgOpen && selected != null) EpgDialog(selected) { epgOpen = false }
    Column(modifier.fillMaxSize().background(appTheme.background.copy(alpha = .60f))) {
        Text(tr(R.string.live_counts, groups.size, live.size), color = Color.LightGray,
            fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
        if (warnings.isNotBlank()) Text(statusText(warnings), color = v3Gold, maxLines = 1, fontSize = 11.sp)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showCategories) LazyColumn(Modifier.weight(.95f).fillMaxHeight().background(v3Panel),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { TvAction(tr(R.string.favorites_count, live.count { it.url in favorites }), Modifier.fillMaxWidth(), onlyFavorites,
                    fontSize = 15.sp, verticalPadding = 10.dp) {
                    onlyFavorites = true
                } }
                itemsIndexed(groups.keys.toList()) { _, name ->
                    TvAction("$name  (${groups[name]?.size ?: 0})", Modifier.fillMaxWidth(), !onlyFavorites && category == name,
                        fontSize = 15.sp, verticalPadding = 10.dp) {
                        category = name; onlyFavorites = false
                    }
                }
            }
            LazyColumn(Modifier.weight(1.12f).fillMaxHeight().background(v3Panel), state = channelListState,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!showCategories) item {
                    TvAction(tr(R.string.favorites_count, live.count { it.url in favorites }), Modifier.fillMaxWidth(), onlyFavorites,
                        fontSize = 15.sp, verticalPadding = 10.dp) {
                        onlyFavorites = !onlyFavorites
                    }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold) }
                if (!loading && visible.isEmpty()) item { Text(tr(R.string.no_channels), color = Color.LightGray, modifier = Modifier.padding(5.dp)) }
                itemsIndexed(visible, key = { i, ch -> "$i-${ch.url}" }) { index, ch ->
                    var focused by remember { mutableStateOf(false) }
                    val active = ch.url == selectedUrl
                    Row(Modifier.fillMaxWidth().height(44.dp)
                        .then(if (active) Modifier.focusRequester(selectedFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .background(if (isTvDevice && (active || focused)) Color.Transparent
                            else if (active || focused) appTheme.selected else appTheme.row)
                        .border(if (focused) 2.dp else 0.dp, if (focused) v3Gold else Color.Transparent)
                        .clickable { if (selectedUrl == ch.url) onPlay(ch) else selectedUrl = ch.url }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                        if (ch.logo.isNotBlank()) AsyncImage(ch.logo, null, Modifier.size(24.dp), contentScale = ContentScale.Fit)
                        else Text("▶", color = v3Gold, modifier = Modifier.width(24.dp))
                        Text(ch.name, color = if (active) v3Gold else Color.White, fontSize = 14.sp,
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



