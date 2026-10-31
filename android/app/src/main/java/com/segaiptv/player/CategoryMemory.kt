package com.segaiptv.player

/** Activity-owned data survives the browser leaving composition for fullscreen playback. */
class CategoryMemory {
    private var sources: List<Playlist>? = null
    private var generation = -1
    private val pages = mutableMapOf<String, Snapshot>()
    internal class Snapshot {
        var categories: List<ProviderCategory>? = null
        var selectedKey: String? = null
        var entries: List<Channel>? = null
    }
    internal fun page(playlists: List<Playlist>, token: Int, kind: String): Snapshot {
        if (sources != playlists || generation != token) {
            pages.clear(); sources = playlists.toList(); generation = token
        }
        return pages.getOrPut(kind) { Snapshot() }
    }
    fun clear() { pages.clear(); sources = null; generation = -1 }
}
