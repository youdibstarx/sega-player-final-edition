package com.segaiptv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

/** TV-only composables: 10-foot typography and explicit focus affordances for remote D-pad. */
private val tvSurface: Color
    @Composable get() = appTheme.surface
private val tvMuted = Color(0xFFBAB1BA)

private val tvPages = listOf(
    Triple("home", "الرئيسية", "⌂"),
    Triple("channels", "البث المباشر", "▦"),
    Triple("movies", "الأفلام", "▤"),
    Triple("series", "المسلسلات", "▥"),
    Triple("favorites", "المفضلة", "♥"),
    Triple("settings", "الإعدادات", "⚙")
)

@Composable
fun TvShell(
    config: AppConfig, identity: DeviceIdentity, page: String,
    onPageChange: (String) -> Unit, channels: List<Channel>, loading: Boolean,
    favorites: Set<String>, accent: Color, warnings: String, error: String,
    onRefresh: () -> Unit, onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    onLocalFile: () -> Unit, onCopyMac: () -> Unit, onCopyKey: () -> Unit,
    refreshToken: Int = 0, onCategoryLoaded: (List<Channel>) -> Unit = {},
    categoryMemory: CategoryMemory = remember { CategoryMemory() }
) {
    val updates = rememberUpdatePrompt()
    if (page == "home") {
        TvLanding(config, identity, channels.size, loading, accent, onPageChange, onRefresh, onPlay, channels)
        return
    }
    var query by rememberSaveable(page) { mutableStateOf("") }
    var searchOpen by remember(page) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val searchable = page in listOf("channels", "movies", "series", "favorites")
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(page) { initialFocus.requestFocus() }
    Column(Modifier.fillMaxSize().background(appTheme.background.copy(alpha = .60f)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("home" to tr(R.string.home), "channels" to tr(R.string.live_tv), "movies" to tr(R.string.movies), "series" to tr(R.string.series)).forEachIndexed { index, (id, label) ->
                TvFocusItem(onClick = { onPageChange(id) }, accent = accent, selected = page == id,
                    modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier) {
                    Text(label, color = if (page == id) accent else Color.White,
                        fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            if (searchable) TvFocusItem(onClick = { searchOpen = true }, accent = accent, selected = query.isNotBlank()) {
                Text("⌕ " + tr(R.string.search), color = if (query.isNotBlank()) accent else Color.White,
                    fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (query.isNotBlank()) TvAction("×") { query = "" }
            Spacer(Modifier.weight(1f))
            Text("SEGA PLAYER V3", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        when (page) {
            "channels", "movies", "series" -> CategoryBrowser(
                Modifier.weight(1f), config.playlists, page, channels, loading,
                favorites, accent, query, refreshToken, onPlay, onToggleFavorite, onCategoryLoaded, onChoose = { query = "" }, memory = categoryMemory, m3uOnly = true)
            "favorites" -> LiveTvV3(Modifier.weight(1f), channels.filter { it.url in favorites }, loading,
                favorites, catalogueWarnings(warnings), onPlay, onToggleFavorite, includeVod = true, search = query)
            "playlists" -> PlaylistScreen(config, identity, loading, warnings, onRefresh, onLocalFile,
                onCopyMac, onCopyKey, Modifier.weight(1f))
            else -> TvSettings(config, identity, channels.size, accent, error,
                onCopyMac, onCopyKey, Modifier.weight(1f), channels, warnings, loading, updates, { onPageChange("channels") })
        }
    }
    if (searchOpen) {
        AlertDialog(onDismissRequest = { searchOpen = false },
            title = { Text(when (page) { "movies" -> tr(R.string.search_movies); "series" -> tr(R.string.search_series); else -> tr(R.string.search_channels) }) },
            text = {
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                OutlinedTextField(query, { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    placeholder = { Text(tr(R.string.type_name)) })
            },
            confirmButton = { TextButton(onClick = { searchOpen = false }) { Text(tr(R.string.show_results)) } },
            dismissButton = { TextButton(onClick = { query = ""; searchOpen = false }) { Text(tr(R.string.clear)) } })
    }
}

/** Large SEGA dashboard inspired by the TV tile navigation pattern; independent look and assets. */
@Composable
private fun TvLanding(config: AppConfig, identity: DeviceIdentity, channelCount: Int,
    loading: Boolean, accent: Color, onPageChange: (String) -> Unit, onRefresh: () -> Unit,
    onPlay: (Channel) -> Unit, channels: List<Channel>) {
    val onExit = LocalExitApp.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("▶", color = accent, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
            Text("SEGA PLAYER V3", fontSize = 22.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            HomeClockWeather()
        }
        Spacer(Modifier.height(18.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cardHeight = minOf(maxHeight * .84f, ((maxWidth - 50.dp) / 6f) * (464f / 400f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val tiles = listOf(
                    Triple(R.drawable.tile_live, tr(R.string.live_tv), "channels"),
                    Triple(R.drawable.tile_movies, tr(R.string.movies), "movies"),
                    Triple(R.drawable.tile_series, tr(R.string.series), "series"),
                    Triple(R.drawable.tile_favorites, tr(R.string.favorites), "favorites"),
                    Triple(R.drawable.tile_playlists, tr(R.string.subscriptions_title), "playlists"),
                    Triple(R.drawable.tile_settings, tr(R.string.settings), "settings")
                )
                tiles.forEachIndexed { index, tile ->
                    if (tile.third == "playlists") SubscriptionTile(config, Modifier.weight(1f).height(cardHeight)) {
                        onPageChange("playlists")
                    } else ThemeArtworkTile(tile.first, tile.second, accent,
                        Modifier.weight(1f).height(cardHeight)
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)) {
                        onPageChange(tile.third)
                    }
                }
            }
        }
        SubscriptionStrip(config)
        WatchHistory(onPlay, channels)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvAction("↻ " + tr(R.string.refresh), onClick = onRefresh)
                TvAction("⏻ " + tr(R.string.exit_app), onClick = onExit)
            }
        }
    }
}

@Composable
private fun TvUpcomingTile(icon: String, label: String, note: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(19.dp)).background(Color(0xFF29212A))
        .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(19.dp))
        .padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(icon, fontSize = 48.sp, color = Color(0xFFD5A7AE))
        Column {
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(note, fontSize = 12.sp, color = tvMuted)
        }
    }
}

@Composable
private fun TvLandingShortcut(icon: String, label: String, accent: Color, modifier: Modifier,
    onClick: () -> Unit) {
    TvFocusItem(onClick = onClick, accent = accent, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(icon, fontSize = 40.sp, color = accent)
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Keep supplied artwork intact; localized content description labels the clickable card. */
@Composable
private fun ThemeArtworkTile(artwork: Int, label: String, accent: Color,
    modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Reserve vertical room in TvLanding so the taller focused card stays inside the row area.
    val heightScale by animateFloatAsState(
        targetValue = if (focused) 1.16f else 1f,
        animationSpec = tween(durationMillis = 180), label = "artwork-focus-height")
    Box(modifier.scale(scaleX = 1f, scaleY = heightScale).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent,
            RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(4.dp), contentAlignment = Alignment.Center) {
        AsyncImage(model = artwork, contentDescription = label,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** A single clickable target: Compose clickable already provides D-pad focus. */
@Composable
private fun TvFocusItem(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "tv-focus-scale")
    val background = if (focused) accent.copy(alpha = 0.44f)
        else if (selected) accent.copy(alpha = 0.22f) else tvSurface
    Box(
        modifier.scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .border(BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) accent else if (selected) accent.copy(alpha = 0.5f) else Color.Transparent), shape)
            .clickable(onClick = onClick),
        content = content
    )
}

@Composable
private fun TvHome(config: AppConfig, channels: List<Channel>, loading: Boolean, accent: Color,
    onPageChange: (String) -> Unit, onPlay: (Channel) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TvFocusItem(onClick = { onPageChange("channels") }, accent = accent,
            modifier = Modifier.fillMaxWidth().height(183.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(accent.copy(alpha = .86f), Color(0xFF23284C), Color(0xFF101727))))) {
                Column(Modifier.align(Alignment.CenterStart).padding(30.dp)) {
                    Text("استمتع بمشاهدة قنواتك", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("تصفح القنوات المصرح بها من لوحة التحكم", color = Color.White.copy(alpha = .86f), fontSize = 17.sp)
                    Spacer(Modifier.height(18.dp))
                    Text("تصفح الآن ←", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("القنوات المضافة حديثاً", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${config.playlists.size} قائمة  •  ${channels.size} قناة", fontSize = 14.sp, color = tvMuted)
        }
        if (loading) {
            CircularProgressIndicator(color = accent)
        } else if (channels.isEmpty()) {
            Text("لا توجد قنوات حالياً. أضف قائمة M3U من لوحة التحكم.", fontSize = 18.sp, color = tvMuted)
        } else {
            TvChannelGrid(channels.take(18), accent, emptySet(), onPlay, {},
                showFavorite = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TvChannels(
    channels: List<Channel>, loading: Boolean, favorites: Set<String>, accent: Color,
    warning: String, onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    modifier: Modifier, favoritesOnly: Boolean = false
) {
    var search by remember(favoritesOnly) { mutableStateOf("") }
    var category by remember(favoritesOnly) { mutableStateOf("الكل") }
    val categories = remember(channels) { listOf("الكل") + channels.map { it.category }.distinct().take(80) }
    val filtered = remember(channels, search, category) {
        channels.filter { (category == "الكل" || it.category == category) &&
            (search.isBlank() || it.name.contains(search, ignoreCase = true) ||
                it.category.contains(search, ignoreCase = true)) }
    }

    Column(modifier) {
        OutlinedTextField(search, onValueChange = { search = it }, singleLine = true,
            label = { Text("بحث بالقنوات باستعمال الريموت", fontSize = 15.sp) },
            leadingIcon = { Text("⌕", fontSize = 23.sp) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(categories) { group ->
                TvFocusItem(onClick = { category = group }, accent = accent,
                    selected = category == group) {
                    Text(group, fontSize = 15.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 19.dp, vertical = 13.dp))
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        if (warning.isNotBlank()) Text(warning, color = Color(0xFFFFD08A), fontSize = 15.sp)
        Text("${filtered.size} قناة", fontSize = 15.sp, color = tvMuted)
        Spacer(Modifier.height(11.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favoritesOnly) "لا توجد قنوات مفضّلة" else "لم يتم العثور على قنوات",
                    fontSize = 20.sp, color = tvMuted)
            }
        } else {
            TvChannelGrid(filtered, accent, favorites, onPlay, onToggleFavorite,
                showFavorite = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TvChannelGrid(channels: List<Channel>, accent: Color, favorites: Set<String>,
    onPlay: (Channel) -> Unit, onToggleFavorite: (Channel) -> Unit,
    showFavorite: Boolean, modifier: Modifier
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 202.dp), modifier = modifier,
        contentPadding = PaddingValues(start = 5.dp, end = 8.dp, top = 5.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(channels) { channel ->
            TvFocusItem(onClick = { onPlay(channel) }, accent = accent,
                modifier = Modifier.fillMaxWidth().height(160.dp)) {
                Column(Modifier.fillMaxSize().padding(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (channel.logo.isNotBlank()) {
                            AsyncImage(channel.logo, channel.name, Modifier.size(66.dp)
                                .clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.size(66.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF303853)), contentAlignment = Alignment.Center) {
                                Text("▶", color = accent, fontSize = 28.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (showFavorite) {
                            val favorited = channel.url in favorites
                            // Remote-friendly secondary action: separate focus target for favorite.
                            TvFocusItem(onClick = { onToggleFavorite(channel) },
                                accent = accent, modifier = Modifier.size(42.dp)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (favorited) "♥" else "♡", fontSize = 23.sp,
                                        color = if (favorited) accent else tvMuted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel.category, fontSize = 13.sp, color = tvMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TvSettings(config: AppConfig, identity: DeviceIdentity, count: Int,
    accent: Color, error: String,
    onCopyMac: () -> Unit, onCopyKey: () -> Unit, modifier: Modifier,
    channels: List<Channel>, warnings: String, loading: Boolean, checkUpdates: () -> Unit, openChannels: () -> Unit
) {
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(tr(R.string.device_settings), color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactIdentity(tr(R.string.virtual_mac), identity.virtualMac, Modifier.weight(1.15f), onCopyMac)
                CompactIdentity(tr(R.string.device_key), identity.key, Modifier.weight(1f), onCopyKey)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TvInformation(tr(R.string.subscription), if (config.active) tr(R.string.active) else tr(R.string.inactive), Modifier.weight(1f))
                TvInformation(tr(R.string.expires), config.expires.substringBefore('T').substringBefore(' ').ifBlank { tr(R.string.unspecified) }, Modifier.weight(1.3f))
                TvInformation(tr(R.string.channels), "$count", Modifier.weight(.85f))
            }
            Text(tr(R.string.copy_hint), color = tvMuted, fontSize = 10.sp)
            if (error.isNotBlank()) Text(statusText(error), color = Color(0xFFFFC5AD), fontSize = 11.sp, maxLines = 1)
        }
        Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsGroup(tr(R.string.preferences_group)) {
                LanguagePicker(Modifier.weight(1f))
                ThemePicker(Modifier.weight(1f))
            }
            SettingsGroup(tr(R.string.channels)) {
                ChannelOrderPicker(openChannels, Modifier.weight(1.25f))
                ViewerSettings(channels, warnings, error, loading, Modifier.weight(1f))
            }
            SettingsGroup(tr(R.string.connection_and_updates)) {
                ConnectionSettings(Modifier.weight(1f))
                TvAction(tr(R.string.update_check), Modifier.weight(1.4f), onClick = checkUpdates)
            }
        }
    }
}

@Composable
private fun CompactIdentity(title: String, value: String, modifier: Modifier, onCopy: () -> Unit) {
    TvFocusItem(onClick = onCopy, accent = v3Gold, modifier = modifier, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("$title  ⧉", color = tvMuted, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun TvInformation(title: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(tvSurface).padding(9.dp)) {
        Text(title, fontSize = 11.sp, color = tvMuted)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvIdentityAction(title: String, value: String, accent: Color, onClick: () -> Unit) {
    TvFocusItem(onClick = onClick, accent = accent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = tvMuted)
                Spacer(Modifier.height(5.dp))
                Text(value, fontSize = 21.sp, maxLines = 2, fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis)
            }
            Text("نسخ ◧", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
fun TvActivationScreen(config: AppConfig, identity: DeviceIdentity, accent: Color,
    error: String, onRefresh: () -> Unit, copyMac: () -> Unit, copyKey: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 100.dp, vertical = 38.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(config.appName, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Text("تفعيل جهاز Android TV", fontSize = 25.sp, color = tvMuted)
        Text("أرسل بيانات الجهاز للمشرف، ثم اختر التحقق من التفعيل باستعمال الريموت.",
            fontSize = 17.sp, color = tvMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvIdentityAction("Virtual MAC — ليس MAC الحقيقي", identity.virtualMac, accent, copyMac)
            TvIdentityAction("Device Key", identity.key, accent, copyKey)
            TvFocusItem(onClick = onRefresh, accent = accent,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
                Text("↻  التحقق من التفعيل", modifier = Modifier.fillMaxWidth().padding(21.dp),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFCAAC), fontSize = 16.sp)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvVideoScreen(channel: Channel, accent: Color, onBack: () -> Unit,
    isFavorite: Boolean, onFavorite: () -> Unit
) {
    PlaybackScreen(channel, accent, onBack, isFavorite, onFavorite)
}



