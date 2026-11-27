package com.segaiptv.player

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val allTitles = rememberCatalogueWork<List<VodTitle>>(emptyList(), entries, series) { buildVodTitles(entries, series) }
    val groups = rememberCatalogueWork<Map<String, List<VodTitle>>>(emptyMap(), allTitles) { allTitles.groupBy { it.entries.first().category } }
    val posterContext = LocalContext.current
    val isTvPosterBrowser = remember(posterContext) {
        val pm = posterContext.packageManager
        AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
            tvUiMode = (posterContext.getSystemService(android.content.Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager)?.currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION,
            hasTelevisionFeature = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION),
            hasTouchscreen = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        )
    }
    var posterRefresh by remember(series) { mutableIntStateOf(0) }
    var category by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var season by rememberSaveable(series) { mutableStateOf<Int?>(null) }
    var previousQuery by rememberSaveable(series) { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (previousQuery != query) { detailId = null; previousQuery = query }
    }
    val titles = rememberCatalogueWork<List<VodTitle>>(emptyList(), allTitles, groups, category, query) {
        (if (category == null || query.isNotBlank()) allTitles else groups[category].orEmpty())
            .filter { query.isBlank() || it.title.contains(query, true) || it.entries.any { e -> e.name.contains(query, true) } }
    }
    val detail = remember(allTitles, detailId) { allTitles.firstOrNull { it.id == detailId } }
    val moviePlayFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }
    var remoteEpisodes by remember(detailId) { mutableStateOf<List<Channel>>(emptyList()) }
    var episodeLoading by remember(detailId) { mutableStateOf(false) }
    LaunchedEffect(detailId, series, episodeLoading) {
        if (detailId != null && !series) moviePlayFocus.requestFocus()
        if (detailId != null && series && !episodeLoading) {
            // The season buttons are rendered only after their catalogue is ready.
            kotlinx.coroutines.yield()
            try { seasonFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }

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
        if (isTvPosterBrowser && detail == null) {
            TvAction(tr(R.string.refresh_posters)) { posterRefresh++ }
        }
        if (detail != null) {
            val seasons = remember(detail, remoteEpisodes) { (if (remoteShow != null) remoteEpisodes else detail.entries).groupBy { episodeLabel(it).season } }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(2f / 3f).background(v3Panel), contentAlignment = Alignment.Center) {
                    PosterArtwork(detail, Modifier.fillMaxSize(), posterRefresh)
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
                                TvAction(n?.let { tr(R.string.season, it) } ?: tr(R.string.episodes), modifier = if (n == seasons.keys.first()) Modifier.focusRequester(seasonFocus) else Modifier, selected = season == n) { season = n }
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
                            TvAction(tr(R.string.play_movie), modifier = Modifier.focusRequester(moviePlayFocus)) { onPlay(movie) }
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
                                PosterArtwork(title, Modifier.fillMaxSize(), posterRefresh)
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
internal fun PosterArtwork(title: VodTitle, modifier: Modifier, refreshPosters: Int = 0) {
    val context = LocalContext.current
    val isTvBox = remember(context) {
        val pm = context.packageManager
        AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
            tvUiMode = (context.getSystemService(android.content.Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager)?.currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION,
            hasTelevisionFeature = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION),
            hasTouchscreen = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        )
    }
    val entry = remember(title.id, title.cover, refreshPosters) { title.entries.firstOrNull()?.copy(logo = title.cover) }
    // Phones keep the existing one-time details fallback.
    var attempt by remember(title.id, title.cover, refreshPosters) { mutableIntStateOf(0) }
    var cover by remember(title.id, title.cover, refreshPosters) { mutableStateOf(title.cover) }
    // On TV boxes, retries are bounded and delayed. An applied retry is a new
    // image request with caches bypassed; this also recovers from stale URLs.
    var tvRetryRequested by remember(title.id, title.cover, refreshPosters) { mutableIntStateOf(0) }
    var tvRetryApplied by remember(title.id, title.cover, refreshPosters) { mutableIntStateOf(0) }
    // A single effect owns metadata resolution. A retry/refresh cancels its
    // predecessor so a slow initial lookup cannot overwrite a recovered URL.
    LaunchedEffect(title.id, title.cover, attempt, tvRetryRequested, refreshPosters) {
        if (entry == null) return@LaunchedEffect
        val retryNumber = if (isTvBox) tvRetryRequested else 0
        if (retryNumber > 0) kotlinx.coroutines.delay(PosterRetryPolicy.delayMillis(retryNumber))
        val resolved = PosterRepository.resolve(context, entry,
            forceDetails = (isTvBox && (retryNumber == 1 || refreshPosters > 0)) ||
                (!isTvBox && attempt > 0))
        cover = resolved
        tvRetryApplied = retryNumber
        if (isTvBox && resolved.isBlank() && XtreamSource.forEntry(entry) != null &&
            retryNumber < PosterRetryPolicy.MAX_RETRIES
        ) tvRetryRequested = retryNumber + 1
    }
    val requestAttempt = tvRetryApplied
    val requestGeneration = "$refreshPosters:$requestAttempt"
    val request = remember(cover, context, tvRetryApplied, refreshPosters) {
        coil.request.ImageRequest.Builder(context).data(cover).size(360, 540).allowHardware(false)
            .apply {
                if (isTvBox) {
                    // Distinguish same-URL requests without multiplying cache
                    // entries. Ignore callbacks from superseded generations.
                    setParameter("sega_poster_generation", requestGeneration, memoryCacheKey = null)
                }
                if (isTvBox && (tvRetryApplied > 0 || refreshPosters > 0)) {
                    memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                    diskCachePolicy(coil.request.CachePolicy.DISABLED)
                }
            }.build()
    }
    var imageLoaded by remember(title.id, request) { mutableStateOf(false) }
    Box(modifier.background(appTheme.row), contentAlignment = Alignment.Center) {
        if (!imageLoaded) Text(tr(R.string.no_cover), color = Color.LightGray, fontSize = 11.sp,
            modifier = Modifier.padding(10.dp))
        // Recreate the painter even when a refresh resolves to the same URL
        // and the same cache policy as the last failed request.
        if (cover.isNotBlank()) key(title.id, refreshPosters, tvRetryApplied) {
            AsyncImage(request, title.title,
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { state ->
                if (!isTvBox || (requestAttempt == tvRetryApplied &&
                    state.result.request.parameters.value<String>("sega_poster_generation") == requestGeneration)
                ) imageLoaded = true
            },
            onError = posterError@ { state ->
                if (isTvBox && (requestAttempt != tvRetryApplied ||
                    state.result.request.parameters.value<String>("sega_poster_generation") != requestGeneration)
                ) return@posterError
                imageLoaded = false
                if (!isTvBox) {
                    if (attempt == 0) attempt = 1
                } else {
                    val reason = state.result.throwable
                    // Never log poster URLs; some Xtream URLs contain credentials.
                    android.util.Log.w("SEGA_POSTER",
                        "TV cover failed: " + PosterRetryPolicy.failureCategory(reason) +
                            ", retry=" + requestAttempt)
                    if (tvRetryRequested == requestAttempt &&
                        PosterRetryPolicy.retryAllowed(requestAttempt, reason)
                    ) tvRetryRequested = requestAttempt + 1
                }
            })
        }
    }
}

@Composable
fun PlaylistScreen(config: AppConfig, identity: DeviceIdentity, loading: Boolean, warnings: String,
    onRefresh: () -> Unit, onLocalFile: () -> Unit, onCopyMac: () -> Unit,
    onCopyKey: () -> Unit, modifier: Modifier = Modifier, playlists: List<Playlist> = config.playlists,
    activePlaylistId: Int? = null, onConnect: (Playlist) -> Unit = {}, onDelete: (Playlist) -> Unit = {}) {
    var selectedForAction by remember { mutableStateOf<Playlist?>(null) }
    var selectedForDelete by remember { mutableStateOf<Playlist?>(null) }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.65f).fillMaxHeight()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvAction(tr(R.string.refresh_playlists), onClick = onRefresh)
                TvAction(tr(R.string.open_m3u), onClick = onLocalFile)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = v3Gold)
            if (warnings.isNotBlank()) Text(statusText(warnings), color = v3Gold, fontSize = 11.sp,
                maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            Text(tr(R.string.subscriptions_title), color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    SubscriptionCard(playlist, Modifier.fillMaxWidth().clickable { selectedForAction = playlist },
                        active = playlist.id == activePlaylistId)
                }
                if (playlists.isEmpty()) item {
                    Row(Modifier.fillMaxWidth().background(v3Panel, RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("▤", color = v3Gold, fontSize = 28.sp)
                        Text(tr(R.string.no_playlists), color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            }
        }
        DeviceActivationCard(identity, onCopyMac, onCopyKey, Modifier.weight(1f).fillMaxHeight())
    }
    selectedForAction?.let { playlist ->
        AlertDialog(onDismissRequest = { selectedForAction = null },
            title = { Text(playlist.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.playlist_choose_action))
                TextButton(onClick = { selectedForDelete = playlist; selectedForAction = null }) {
                    Text(tr(R.string.playlist_delete))
                }
            } },
            confirmButton = { TextButton(onClick = { onConnect(playlist); selectedForAction = null }) {
                Text(tr(R.string.playlist_connect))
            } },
            dismissButton = { TextButton(onClick = { selectedForAction = null }) { Text(tr(R.string.close)) } })
    }
    selectedForDelete?.let { playlist ->
        AlertDialog(onDismissRequest = { selectedForDelete = null },
            title = { Text(tr(R.string.playlist_delete_title)) },
            text = { Text(tr(R.string.playlist_delete_message)) },
            confirmButton = { TextButton(onClick = { onDelete(playlist); selectedForDelete = null }) {
                Text(tr(R.string.playlist_delete))
            } },
            dismissButton = { TextButton(onClick = { selectedForDelete = null }) { Text(tr(R.string.close)) } })
    }
}

@Composable
private fun DeviceActivationCard(identity: DeviceIdentity, onCopyMac: () -> Unit,
    onCopyKey: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val qr = remember(identity.virtualMac, identity.key) {
        val matrix = deviceQrMatrix(identity.virtualMac, identity.key)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Column(modifier.background(v3Panel, RoundedCornerShape(16.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("⌁  SEGA PLAYER", color = v3Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Image(qr, tr(R.string.qr_description), Modifier.size(96.dp), filterQuality = FilterQuality.None)
        Text(tr(R.string.scan_qr), color = Color.LightGray, fontSize = 9.sp)
        DeviceIdentityLine(tr(R.string.virtual_mac), identity.virtualMac, "⌘", onCopyMac)
        DeviceIdentityLine(tr(R.string.device_key), identity.key, "▣", onCopyKey)
        Text("↗  faster4k.xyz", color = v3Gold, fontSize = 12.sp,
            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://faster4k.xyz"))) }
                .padding(vertical = 2.dp))
    }
}

@Composable
private fun DeviceIdentityLine(label: String, value: String, icon: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = .05f), RoundedCornerShape(10.dp))
        .clickable(onClick = onCopy).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(icon, color = v3Gold, fontSize = 15.sp)
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.LightGray, fontSize = 8.sp)
            Text(value, color = Color.White, fontSize = 10.sp, maxLines = 1)
        }
        Text("⧉", color = Color.LightGray, fontSize = 12.sp)
    }
}

