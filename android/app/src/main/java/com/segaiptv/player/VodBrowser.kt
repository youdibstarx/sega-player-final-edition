package com.segaiptv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/** Poster library; playback starts only after an explicit movie/episode action. */
@Composable
fun VodBrowser(modifier: Modifier, entries: List<Channel>, series: Boolean, accent: Color,
    onPlay: (Channel) -> Unit, query: String = "", favorites: Set<String> = emptySet(),
    onToggleFavorite: (Channel) -> Unit = {}, loading: Boolean = false, warnings: String = "", showCategories: Boolean = true) {
    val allTitles = remember(entries, series) { buildVodTitles(entries, series) }
    val groups = remember(allTitles) { allTitles.groupBy { it.entries.first().category } }
    var category by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var season by rememberSaveable(series) { mutableStateOf<Int?>(null) }
    var previousQuery by rememberSaveable(series) { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (previousQuery != query) { detailId = null; previousQuery = query }
    }
    val titles = remember(allTitles, category, query) {
        (if (category == null || query.isNotBlank()) allTitles else groups[category].orEmpty())
            .filter { query.isBlank() || it.title.contains(query, true) || it.entries.any { e -> e.name.contains(query, true) } }
    }
    val detail = remember(allTitles, detailId) { allTitles.firstOrNull { it.id == detailId } }
    var remoteEpisodes by remember(detailId) { mutableStateOf<List<Channel>>(emptyList()) }
    var episodeLoading by remember(detailId) { mutableStateOf(false) }
    var episodeError by remember(detailId) { mutableStateOf(false) }
    var synopsis by remember(detailId) { mutableStateOf("") }
    var synopsisOpen by remember(detailId) { mutableStateOf(false) }
    var retry by remember(detailId) { mutableIntStateOf(0) }
    val remoteShow = detail?.entries?.firstOrNull()?.takeIf { it.seriesId.isNotBlank() && it.mediaType == "series" }
    LaunchedEffect(detailId, retry) {
        val first = detail?.entries?.firstOrNull()
        if (first != null) {
            episodeLoading = true
            episodeError = false
            synopsis = cleanSynopsis(first.description)
            try {
                val info = ProgrammeData.details(first, force = retry > 0)
                synopsis = info.synopsis
                remoteEpisodes = info.episodes
                if (remoteShow != null && season !in remoteEpisodes.map { it.season })
                    season = remoteEpisodes.firstOrNull()?.season
            } catch (ex: Exception) {
                if (ex is kotlinx.coroutines.CancellationException) throw ex
                episodeError = true
            } finally { episodeLoading = false }
        }
    }
    if (synopsisOpen && detail != null) SynopsisDialog(detail.title,
        synopsis.ifBlank { tr(R.string.no_synopsis) }) { synopsisOpen = false }
    BackHandler(detail != null) { detailId = null }
    Column(modifier.fillMaxSize().background(appTheme.background.copy(alpha = .60f))) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold)
        if (warnings.isNotBlank()) Text(statusText(warnings), color = v3Gold, fontSize = 10.sp, maxLines = 2)
        Text(tr(if (series) R.string.series_counts else R.string.movie_counts, groups.size, allTitles.size),
            color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
        if (detail != null) {
            val seasons = remember(detail, remoteEpisodes) { (if (remoteShow != null) remoteEpisodes else detail.entries).groupBy { episodeLabel(it).season } }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).background(v3Panel), contentAlignment = Alignment.Center) {
                    PosterArtwork(detail, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvAction("‹ " + tr(R.string.library)) { detailId = null }
                    Text(detail.title, color = v3Gold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail.entries.first().category, color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                    if (series) {
                        TvAction(tr(R.string.synopsis)) { synopsisOpen = true }
                        if (episodeLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold)
                        if (episodeError) {
                            Text(tr(R.string.episodes_error), color = v3Gold, fontSize = 12.sp)
                            TvAction(tr(R.string.retry)) { retry++ }
                        } else if (!episodeLoading && seasons.isEmpty()) Text(tr(R.string.no_episodes), color = Color.LightGray)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(seasons.keys.toList()) { n ->
                                TvAction(n?.let { tr(R.string.season, it) } ?: tr(R.string.episodes), selected = season == n) { season = n }
                            }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(seasons[season].orEmpty()) { episode ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val label = episodeLabel(episode)
                                    TvAction("▶ " + (label.episode?.let { tr(R.string.episode, it) + " · " } ?: "") + episode.name,
                                        Modifier.weight(1f)) { onPlay(episode) }
                                    TvAction(if (episode.url in favorites) "♥" else "♡") { onToggleFavorite(episode) }
                                }
                            }
                        }
                    } else {
                        val movie = detail.entries.first()
                        if (episodeLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = v3Gold)
                        if (episodeError) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(R.string.details_error), fontSize = 11.sp, color = v3Gold, modifier = Modifier.weight(1f))
                            TvAction(tr(R.string.retry)) { retry++ }
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            item { Text(synopsis.ifBlank { if (episodeLoading) tr(R.string.loading_details) else tr(R.string.no_synopsis) },
                                color = Color.LightGray, fontSize = 13.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvAction(tr(R.string.synopsis)) { synopsisOpen = true }
                            TvAction(tr(R.string.play_movie)) { onPlay(movie) }
                            TvAction((if (movie.url in favorites) "♥ " else "♡ ") + tr(R.string.favorite)) { onToggleFavorite(movie) }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showCategories) LazyColumn(Modifier.fillMaxHeight().weight(.25f).background(v3Panel),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item { TvAction(tr(R.string.all_count, allTitles.size), Modifier.fillMaxWidth(), category == null) { category = null } }
                    items(groups.keys.toList()) { name ->
                        TvAction("$name (${groups[name]?.size ?: 0})", Modifier.fillMaxWidth(), category == name) { category = name }
                    }
                }
                if (titles.isEmpty()) Box(Modifier.weight(.75f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(if (loading) tr(R.string.loading_library) else if (query.isNotBlank()) tr(R.string.no_titles) else tr(R.string.no_vod), color = Color.LightGray, modifier = Modifier.padding(16.dp))
                } else LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.weight(.75f).fillMaxHeight(),
                    contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(titles, key = { it.id }) { title ->
                        var focused by remember { mutableStateOf(false) }
                        Column(Modifier.onFocusChanged { focused = it.isFocused }
                            .border(2.dp, if (focused) v3Gold else Color.Transparent)
                            .background(v3Panel).clickable {
                                detailId = title.id
                                season = episodeLabel(title.entries.first()).season
                            }.padding(4.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                                PosterArtwork(title, Modifier.fillMaxSize())
                            }
                            Text(title.title, color = if (focused) v3Gold else Color.White, fontSize = 12.sp,
                                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            if (series) Text(if (title.entries.first().seriesId.isNotBlank()) tr(R.string.seasons_episodes) else tr(R.string.episode_count, title.entries.size), color = Color.LightGray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterArtwork(title: VodTitle, modifier: Modifier) {
    var imageLoaded by remember(title.cover) { mutableStateOf(false) }
    Box(modifier.background(appTheme.row), contentAlignment = Alignment.Center) {
        if (!imageLoaded) Text(tr(R.string.no_cover), color = Color.LightGray, fontSize = 11.sp,
            modifier = Modifier.padding(10.dp))
        if (title.cover.isNotBlank()) AsyncImage(title.cover, title.title,
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { imageLoaded = true }, onError = { imageLoaded = false })
    }
}

@Composable
fun PlaylistScreen(config: AppConfig, loading: Boolean, warnings: String, onRefresh: () -> Unit,
    onLocalFile: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction(tr(R.string.refresh_playlists), onClick = onRefresh)
            TvAction(tr(R.string.open_m3u), onClick = onLocalFile)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = v3Gold)
        if (warnings.isNotBlank()) Text(statusText(warnings), color = v3Gold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            items(config.playlists) { playlist ->
                Column(Modifier.fillMaxWidth().background(v3Panel).padding(16.dp)) {
                    Text(playlist.name, color = Color.White, fontSize = 19.sp)
                    Text(tr(R.string.managed_playlist), color = Color.LightGray)
                }
            }
            if (config.playlists.isEmpty()) item { Text(tr(R.string.no_playlists), color = Color.LightGray) }
        }
    }
}


