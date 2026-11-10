package com.segaiptv.player

import android.view.View
import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Parental and resume checks run before any stream is opened, including preview playback. */
@Composable
fun MediaSurface(channel: Channel?, modifier: Modifier = Modifier, controls: Boolean = false,
    onControlsVisible: (Boolean) -> Unit = {}, onPlayer: (ExoPlayer?) -> Unit = {},
    onEnded: () -> Unit = {}, onOk: () -> Unit = {}) {
    val store = ViewerStore.get(LocalContext.current)
    store.revision
    var unlock by remember(channel?.url) { mutableStateOf(false) }
    val saved = remember(channel?.url) { channel?.takeIf { isVod(it) }?.let { store.position(it) } ?: 0L }
    var position by remember(channel?.url) { mutableStateOf<Long?>(if (saved > 0) null else 0L) }
    if (channel != null && store.blocked(channel)) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            TvAction(fx("unlock")) { unlock = true }
        }
        if (unlock) PinDialog(onClose = { unlock = false }, onSuccess = { unlock = false })
    } else if (channel != null && position == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fx("resume_prompt") + " · ${saved / 60000} min", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvAction(fx("resume")) { position = saved }
                    TvAction(fx("start_over")) { store.record(channel, ended = true); position = 0L }
                }
            }
        }
    } else UnlockedMediaSurface(channel, modifier, controls, position ?: 0L, onControlsVisible, onPlayer, onEnded, onOk)
}

/** Owns one player while STARTED and persists position before every release. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun UnlockedMediaSurface(channel: Channel?, modifier: Modifier, controls: Boolean, initialPosition: Long,
    onControlsVisible: (Boolean) -> Unit, onPlayer: (ExoPlayer?) -> Unit, onEnded: () -> Unit, onOk: () -> Unit) {
    val visibilityCallback by rememberUpdatedState(onControlsVisible)
    val playerCallback by rememberUpdatedState(onPlayer)
    val endedCallback by rememberUpdatedState(onEnded)
    val okCallback by rememberUpdatedState(onOk)
    val context = LocalContext.current
    val store = remember { ViewerStore.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var error by remember(channel?.url) { mutableStateOf("") }
    var buffering by remember(channel?.url) { mutableStateOf(false) }
    var retry by remember(channel?.url) { mutableIntStateOf(0) }
    var attempts by remember(channel?.url) { mutableIntStateOf(0) }
    val recovery = remember(channel?.url) { DecoderRecovery() }
    var decoderRetry by remember(channel?.url) { mutableStateOf(false) }
    var externalUnavailable by remember(channel?.url) { mutableStateOf(false) }
    var position by remember(channel?.url) { mutableLongStateOf(initialPosition) }
    LaunchedEffect(error) {
        if (decoderRetry) {
            kotlinx.coroutines.delay(250)
            decoderRetry = false
            retry++
        } else if (error.isNotBlank() && attempts < 3 && DecoderRecovery.network(error)) {
            kotlinx.coroutines.delay(2000L shl attempts)
            attempts++
            retry++
        }
    }
    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15000)
            if (channel != null && isVod(channel) && active.playbackState == Player.STATE_READY)
                store.record(channel, active.currentPosition, active.duration)
        }
    }
    DisposableEffect(channel?.url, lifecycle, retry) {
        var owned: ExoPlayer? = null
        var resume = true
        fun start() {
            if (owned != null || channel == null || store.blocked(channel)) return
            error = ""; buffering = true
            val excludedDecoders = recovery.excluded.toSet()
            val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector { mime, secure, tunneling ->
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mime, secure, tunneling).filter { it.name !in excludedDecoders }
                }
            // Keep adaptation and hardware capability checks; never force unsupported 4K tracks.
            val memory = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val targetBytes = if (memory.isLowRamDevice || memory.memoryClass < 256) 24 * 1024 * 1024 else 64 * 1024 * 1024
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 50000, 2500, 5000)
                .setTargetBufferBytes(targetBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            val created = ExoPlayer.Builder(context.applicationContext, renderers)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context,
                        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(NetworkClient.client))))
                .build()
            owned = created
            created.setAudioAttributes(AudioAttributes.DEFAULT, true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        store.playbackError("")
                        if (!isVod(channel)) store.record(channel)
                    }
                    if (state == Player.STATE_ENDED) {
                        store.record(channel, ended = true)
                        endedCallback()
                    }
                }
                override fun onPlayerError(exception: PlaybackException) {
                    buffering = false
                    error = exception.errorCodeName
                    store.playbackError(exception.errorCodeName)
                    if (DecoderRecovery.decoding(exception.errorCodeName)) {
                        var cause: Throwable? = exception
                        var failed: String? = null
                        repeat(8) {
                            when (val current = cause) {
                                is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> failed = current.codecInfo?.name
                                is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException -> failed = current.codecInfo?.name
                            }
                            if (failed == null) cause = cause?.cause
                        }
                        decoderRetry = recovery.reject(failed)
                    }
                }
            })
            val mime = if (channel.url.substringBefore('?').endsWith(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null
            created.setMediaItem(MediaItem.Builder().setUri(channel.url).setMimeType(mime).build())
            if (isVod(channel) && position > 0) created.seekTo(position)
            created.playWhenReady = resume
            created.prepare()
            player = created; playerCallback(created)
        }
        fun stop() {
            owned?.let {
                position = if (channel != null && isVod(channel)) it.currentPosition else 0L
                if (channel != null && isVod(channel)) store.record(channel, position, it.duration,
                    ended = it.playbackState == Player.STATE_ENDED)
                resume = it.playWhenReady
                player = null; playerCallback(null)
                it.release()
            }
            owned = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            useController = controls
            setOnKeyListener { _, keyCode, event ->
                if (controls && event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    okCallback()
                    true
                } else false
            }
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            controllerShowTimeoutMs = 3000
            controllerAutoShow = false
            setShowSubtitleButton(true)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                val visible = visibility == View.VISIBLE
                visibilityCallback(visible)
                if (!visible && controls) requestFocus()
            })
            isFocusable = controls; isFocusableInTouchMode = controls
            if (controls) requestFocus()
        } }, update = { view -> view.player = player; view.keepScreenOn = player != null },
            onRelease = { it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?); it.player = null; it.keepScreenOn = false },
            modifier = Modifier.fillMaxSize())
        if (channel == null) Text(tr(R.string.select_channel), color = Color.White)
        if (buffering && channel != null) CircularProgressIndicator(color = v3Gold)
        if (error.isNotBlank()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr(R.string.playback_unavailable, error), color = Color.White)
            if (decoderRetry) Text(tr(R.string.trying_decoder), color = v3Gold)
            else if (DecoderRecovery.network(error) && attempts < 3) Text(fx("reconnecting"), color = v3Gold)
            else if (DecoderRecovery.decoding(error)) Text(tr(R.string.decoder_unavailable), color = v3Gold)
            if (channel != null && controls && DecoderRecovery.decoding(error)) {
                val externalTitle = tr(R.string.external_player)
                TvAction(externalTitle) {
                    try {
                        player?.pause()
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(android.net.Uri.parse(channel.url), "video/*")
                        context.startActivity(android.content.Intent.createChooser(intent, externalTitle))
                    } catch (_: android.content.ActivityNotFoundException) { externalUnavailable = true }
                }
                if (externalUnavailable) Text(tr(R.string.no_external_player), color = Color.White)
            }
            TvAction(tr(R.string.retry), onClick = { attempts = 0; retry++ })
        }
    }
}

@Composable
fun PlaybackScreen(channel: Channel, accent: Color, onBack: () -> Unit,
    isFavorite: Boolean, onFavorite: () -> Unit, entries: List<Channel> = emptyList(),
    onNext: (Channel) -> Unit = {}) {
    var controlsVisible by remember(channel.url) { mutableStateOf(false) }
    var channelMenu by remember(channel.url) { mutableStateOf(false) }
    val isLive = !isVod(channel)
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(channelMenu) {
        if (channelMenu) {
            kotlinx.coroutines.yield()
            try { menuFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    var active by remember(channel.url) { mutableStateOf<ExoPlayer?>(null) }
    var tracks by remember(channel.url) { mutableStateOf(false) }
    var ended by remember(channel.url) { mutableStateOf(false) }
    var episodes by remember(channel.url) { mutableStateOf(entries) }
    val store = ViewerStore.get(LocalContext.current)
    var pin by remember { mutableStateOf(false) }
    var lockMenu by remember { mutableStateOf(false) }
    LaunchedEffect(channel.url) {
        if (isVodEntry(channel, true) && channel.seriesId.isNotBlank()) {
            try { episodes = ProgrammeData.details(channel).episodes }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        }
    }
    val next = remember(channel, episodes) { nextEpisode(channel, episodes) }
    BackHandler { if (channelMenu) channelMenu = false else onBack() }
    if (tracks && active != null) TrackPicker(active!!) { tracks = false }
    if (pin) PinDialog(onClose = { pin = false }, onSuccess = { pin = false; lockMenu = true })
    if (lockMenu && !store.unlocked) PinDialog(onClose = { lockMenu = false }, onSuccess = {})
    if (lockMenu && store.unlocked) AlertDialog(onDismissRequest = { lockMenu = false }, title = { Text(fx("parental")) },
        text = { Column {
            TvAction(fx("lock_item")) { store.lock(channel, false); lockMenu = false }
            TvAction(fx("lock_group")) { store.lock(channel, true); lockMenu = false }
        } }, confirmButton = { TextButton(onClick = { lockMenu = false }) { Text(tr(R.string.close)) } })
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MediaSurface(channel, Modifier.fillMaxSize(), controls = true, onControlsVisible = { controlsVisible = it },
            onPlayer = { active = it }, onEnded = { ended = true },
            onOk = { if (isLive) channelMenu = true })
        if (channelMenu && isLive) {
            val liveEntries = remember(entries) { entries.filter { !isVod(it) }.distinctBy { it.url } }
            val currentIndex = liveEntries.indexOfFirst { it.url == channel.url }
            Surface(modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(.43f).fillMaxHeight(),
                color = Color.Black.copy(alpha = .92f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr(R.string.channels), color = accent, style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.weight(1f)) {
                        items(liveEntries.size) { index ->
                            val entry = liveEntries[index]
                            TvAction(entry.name, modifier = Modifier.fillMaxWidth()
                                .then(if (index == (if (currentIndex >= 0) currentIndex else 0))
                                    Modifier.focusRequester(menuFocus) else Modifier),
                                selected = entry.url == channel.url) {
                                channelMenu = false
                                if (entry.url != channel.url) onNext(entry)
                            }
                        }
                    }
                }
            }
        }
        if (controlsVisible) Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .7f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvAction(tr(R.string.back), onClick = onBack)
            Text(channel.name, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, maxLines = 1)
            TvAction(fx("tracks")) { tracks = true }
            if (store.pinEnabled) TvAction("🔒") { if (store.unlocked) lockMenu = true else pin = true }
            TvAction((if (isFavorite) "♥ " else "♡ ") + tr(R.string.favorite), onClick = onFavorite)
        }
        if (ended) Column(Modifier.align(Alignment.Center).background(v3Panel).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (next != null) {
                Text(next.name, color = Color.White)
                TvAction(fx("next_episode")) { onNext(next) }
            }
            TvAction(tr(R.string.back), onClick = onBack)
        }
    }
}


