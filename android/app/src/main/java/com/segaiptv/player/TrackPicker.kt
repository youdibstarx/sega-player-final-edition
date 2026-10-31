package com.segaiptv.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer

@Composable
internal fun TrackPicker(player: ExoPlayer, onClose: () -> Unit) {
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onTracksChanged(value: Tracks) { tracks = value } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(fx("tracks")) }, text = {
        LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                item {
                    Text(fx(if (type == C.TRACK_TYPE_AUDIO) "audio" else "subtitles"))
                    TvAction(fx("automatic")) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
                    }
                    if (type == C.TRACK_TYPE_TEXT) TvAction(fx("off")) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
                    }
                }
                val groups = tracks.groups.filter { it.type == type }
                if (groups.isEmpty()) item { Text(fx("no_tracks")) }
                groups.forEach { group ->
                    for (index in 0 until group.length) {
                        if (group.isTrackSupported(index)) item {
                            val format = group.getTrackFormat(index)
                            val label = listOfNotNull(format.label, format.language, format.sampleMimeType).joinToString(" · ")
                            TvAction(label.ifBlank { "#${index + 1}" }, selected = group.isTrackSelected(index)) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(type, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(tr(R.string.close)) } })
}
