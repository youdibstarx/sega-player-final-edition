package com.segaiptv.player

/** Successful fallback is diagnostic information for Playlists, not a playback failure. */
internal fun catalogueWarnings(raw: String, section: String? = null): String =
    raw.lineSequence().filter { line ->
        if (line.endsWith(" M3U fallback")) false
        else {
            val kind = Regex("(live|vod|series) catalogue unavailable$").find(line)?.groupValues?.get(1)
            kind == null || section == null || kind == section
        }
    }.joinToString("\n")

/** Activation polling must not duplicate a loaded catalogue every five minutes. */
internal fun catalogueNeedsRefresh(loadedToken: Int, requestedToken: Int,
    previous: List<Playlist>?, current: List<Playlist>): Boolean =
    previous == null || loadedToken != requestedToken || previous != current
