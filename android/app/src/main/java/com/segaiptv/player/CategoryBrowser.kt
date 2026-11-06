package com.segaiptv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Composable
internal fun CategoryBrowser(modifier: Modifier, playlists: List<Playlist>, page: String,
    channels: List<Channel>, localLoading: Boolean, favorites: Set<String>, accent: Color,
    query: String, refreshToken: Int, onPlay: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit, onLoaded: (List<Channel>) -> Unit, onChoose: () -> Unit,
    loadCategories: suspend (Playlist, String) -> List<ProviderCategory> = { p, k ->
        val categories = CategoryCatalogue.categories(p, k)
        try {
            val ranks = ProviderOrder.load(p, refreshToken, categories.map { it.name }.toSet())
            ProviderOrder.arrange(categories, ranks).map { it.copy(sourceOrderKnown = it.name in ranks) }
        }
        catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            categories.map { it.copy(sourceOrderKnown = false) }
        }
    },
    loadEntries: suspend (ProviderCategory) -> List<Channel> = { CategoryCatalogue.entries(it) },
    memory: CategoryMemory = remember { CategoryMemory() }, m3uOnly: Boolean = false) {
    val kind = when (page) { "movies" -> "vod"; "series" -> "series"; else -> "live" }
    val providers = remember(playlists) { if (m3uOnly) emptyList() else playlists.filter { XtreamSource.from(it.url) != null }.take(30) }
    val snapshot = memory.page(providers, refreshToken, kind)
    var categories by remember(snapshot) { mutableStateOf(snapshot.categories.orEmpty()) }
    var selectedKey by rememberSaveable(page, refreshToken) { mutableStateOf<String?>(null) }
    var loadingCategories by remember(providers, kind, refreshToken) { mutableStateOf(snapshot.categories == null) }
    var categoryError by remember(providers, kind, refreshToken) { mutableStateOf(false) }
    var retryCategories by remember { mutableIntStateOf(0) }
    var retryEntries by remember { mutableIntStateOf(0) }
    var entries by remember(selectedKey, snapshot) { mutableStateOf(if (snapshot.selectedKey == selectedKey) snapshot.entries.orEmpty() else emptyList()) }
    var entriesLoading by remember(selectedKey, providers, refreshToken) { mutableStateOf(false) }
    var entriesError by remember(selectedKey, providers, refreshToken) { mutableStateOf(false) }
    val loadedCallback by rememberUpdatedState(onLoaded)
    // Plain/local M3U must be parsed first; exclude Xtream rows from the on-demand selection.
    val localGroups = remember(channels, kind) {
        channels.filter { (m3uOnly || XtreamSource.from(it.sourceUrl) == null) && when (kind) {
            "vod" -> isVodEntry(it, false)
            "series" -> isVodEntry(it, true)
            else -> !isVodEntry(it, false) && !isVodEntry(it, true)
        } }.groupBy { it.category.ifBlank { "Other" } }
    }
    val selected = categories.firstOrNull { it.key == selectedKey }
    val localName = selectedKey?.takeIf { it.startsWith("local:") }?.removePrefix("local:")
    LaunchedEffect(providers, kind, refreshToken, retryCategories) {
        if (retryCategories == 0 && snapshot.categories != null) return@LaunchedEffect
        loadingCategories = true; categoryError = false
        val result = mutableListOf<ProviderCategory>()
        // Bound parallel requests while merging in configured playlist order, never completion order.
        for (batch in providers.chunked(4)) {
            val loaded = coroutineScope {
                batch.map { playlist -> async {
                    try { loadCategories(playlist, kind) }
                    catch (ex: Exception) { if (ex is CancellationException) throw ex; null }
                } }.awaitAll()
            }
            for (rows in loaded) {
                if (rows == null) categoryError = true else result += rows
            }
            categories = result.toList()
        }
        categories = result
        if (!categoryError) snapshot.categories = result
        loadingCategories = false
        if (selectedKey != null && localName == null && categories.none { it.key == selectedKey }) selectedKey = null
    }
    LaunchedEffect(selected, refreshToken, retryEntries) {
        if (selected != null && retryEntries == 0 && snapshot.selectedKey == selectedKey && snapshot.entries != null) {
            entries = snapshot.entries.orEmpty()
            loadedCallback(entries)
            return@LaunchedEffect
        }
        entries = emptyList(); entriesError = false
        if (selected != null) {
            entriesLoading = true
            try {
                entries = loadEntries(selected)
                snapshot.selectedKey = selectedKey; snapshot.entries = entries
                loadedCallback(entries)
            } catch (ex: Exception) { if (ex is CancellationException) throw ex; entriesError = true }
            finally { entriesLoading = false }
        }
    }
    BackHandler(selectedKey != null) { selectedKey = null }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Keep the category rail visible while only the selected category is fetched.
        Column(Modifier.fillMaxWidth(.22f).fillMaxHeight().background(v3Panel)) {
            Text(tr(R.string.categories_back), color = accent, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
            if (loadingCategories || localLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
            if (categories.any { !it.sourceOrderKnown }) {
                Text(tr(R.string.source_order_unavailable), color = accent, fontSize = 10.sp, modifier = Modifier.padding(6.dp))
                TvAction(tr(R.string.retry_catalogue), Modifier.fillMaxWidth()) { retryCategories++ }
            }
            if (categoryError) {
                Text(tr(R.string.categories_failed), color = accent, fontSize = 10.sp,
                    modifier = Modifier.padding(6.dp))
                TvAction(tr(R.string.retry_catalogue), Modifier.fillMaxWidth()) { retryCategories++ }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(categories.filter { selectedKey != null || query.isBlank() || it.name.contains(query, true) }, key = { it.key }) { category ->
                    TvAction((if (providers.size > 1) "${category.playlist.name} · " else "") + category.name,
                        Modifier.fillMaxWidth(), selected = selectedKey == category.key) {
                        selectedKey = category.key; onChoose()
                    }
                }
                items(localGroups.keys.filter { selectedKey != null || query.isBlank() || it.contains(query, true) }, key = { "local:$it" }) { name ->
                    TvAction("$name (${localGroups[name]?.size ?: 0})", Modifier.fillMaxWidth(), selected = selectedKey == "local:$name") {
                        selectedKey = "local:$name"; onChoose()
                    }
                }
                if (!loadingCategories && !localLoading && categories.isEmpty() && localGroups.isEmpty() && !categoryError)
                    item { Text(tr(R.string.no_categories), color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(6.dp)) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (selectedKey == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr(R.string.choose_category), color = accent, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvAction("‹ " + tr(R.string.categories_back)) { selectedKey = null }
                    Text(selected?.name ?: localName.orEmpty(), color = accent, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(4.dp))
                    if (selected != null) TvAction(tr(R.string.retry_catalogue)) { retryEntries++ }
                }
                if (entriesError) Text(tr(R.string.category_failed), color = accent, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
                val visible = if (localName != null) localGroups[localName].orEmpty() else entries
                // Child browsers keep their content layout without duplicating the category rail.
                key(selectedKey) {
                    if (kind == "live") LiveTvV3(Modifier.weight(1f), visible, entriesLoading, favorites, "", onPlay, onToggleFavorite,
                        search = query, showCategories = false, orderScope = selectedKey)
                    else VodBrowser(Modifier.weight(1f), visible, kind == "series", accent, onPlay,
                        favorites = favorites, onToggleFavorite = onToggleFavorite, loading = entriesLoading, query = query,
                        showCategories = false)
                }
            }
        }
    }
}


