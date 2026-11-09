package com.segaiptv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only a complete successful refresh may replace a visible catalogue. */
internal suspend fun refreshVisiblePlaylist(
    cached: List<Channel>,
    download: suspend (suspend (List<Channel>) -> Unit) -> PlaylistLoad,
    onPartial: suspend (List<Channel>) -> Unit,
    forceRefresh: Boolean = true
): PlaylistLoad {
    // Activation is checked separately; a saved catalogue never grants access.
    if (!forceRefresh && cached.isNotEmpty()) return PlaylistLoad(cached)
    var partial = emptyList<Channel>()
    val fresh = try {
        download { rows ->
            partial = rows
            // A prefix of the new file must not erase categories later in the cached file.
            val visible = withContext(Dispatchers.Default) { (rows + cached).distinctBy { it.url } }
            onPartial(visible)
        }
    } catch (ex: Exception) {
        if (ex is CancellationException) throw ex
        return withContext(Dispatchers.Default) {
            PlaylistLoad((partial + cached).distinctBy { it.url },
                listOf("Playlist refresh incomplete; keeping available channels"))
        }
    }
    return withContext(Dispatchers.Default) {
        val previous = (partial + cached).distinctBy { it.url }
        if (fresh.entries.isEmpty() && previous.isNotEmpty()) {
            PlaylistLoad(previous, fresh.warnings + "Empty playlist response; keeping available channels")
        } else mergeCachedCatalogue(fresh, previous)
    }
}
