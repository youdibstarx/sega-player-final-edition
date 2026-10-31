package com.segaiptv.player

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Private, bounded, atomic catalogue snapshots; never used to decide activation. */
internal object LibraryCache {
    private fun directory(context: Context) = File(context.noBackupFilesDir, "catalogue_v1").apply { mkdirs() }
    private fun file(context: Context, playlist: Playlist) = File(directory(context), mediaKey(playlist.url) + ".jsonl")
    suspend fun read(context: Context, playlist: Playlist): List<Channel> = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        try {
            val f = file(context, playlist)
            if (!f.exists() || f.length() > 80L * 1024 * 1024 || System.currentTimeMillis() - f.lastModified() > 14L * 86400_000)
                return@withContext emptyList()
            AtomicFile(f).openRead().bufferedReader().use { reader ->
                val categories = mutableMapOf<String, String>()
                val guides = mutableMapOf<String, String>()
                reader.lineSequence().take(150_000).map {
                    coroutine.ensureActive()
                    val item = channelFromJson(JSONObject(it))
                    item.copy(sourceUrl = playlist.url, playlist = playlist.name,
                        category = categories.getOrPut(item.category) { item.category },
                        epgUrl = guides.getOrPut(item.epgUrl) { item.epgUrl })
                }.toList()
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            emptyList()
        }
    }
    suspend fun write(context: Context, playlist: Playlist, entries: List<Channel>) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val atomic = AtomicFile(file(context, playlist))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            var size = 0L
            for (entry in entries.take(150_000)) {
                coroutine.ensureActive()
                val bytes = (entry.toJson().toString() + "\n").toByteArray()
                size += bytes.size
                check(size <= 80L * 1024 * 1024)
                stream.write(bytes)
            }
            atomic.finishWrite(stream); stream = null
            var total = directory(context).listFiles().orEmpty().sumOf { it.length() }
            for (f in directory(context).listFiles().orEmpty().sortedBy { it.lastModified() }) {
                if (total <= 200L * 1024 * 1024) break
                total -= f.length(); f.delete()
            }
        } catch (ex: Exception) {
            stream?.let { atomic.failWrite(it) }
            if (ex is CancellationException) throw ex
        }
    }
    suspend fun prune(context: Context, playlists: List<Playlist>) = withContext(Dispatchers.IO) {
        val keep = playlists.map { mediaKey(it.url) + ".jsonl" }.toSet()
        directory(context).listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
    }
}

/** Restore only failed sections; a successful empty catalogue stays empty. */
internal fun mergeCachedCatalogue(fresh: PlaylistLoad, cached: List<Channel>): PlaylistLoad {
    val failed = fresh.warnings.mapNotNull { warning ->
        Regex("^(live|vod|series) catalogue unavailable$").matchEntire(warning)?.groupValues?.get(1)
    }.toSet()
    val recovered = cached.filter { entry ->
        val kind = if (isVodEntry(entry, true)) "series" else if (isVodEntry(entry, false)) "vod" else "live"
        kind in failed || fresh.warnings.contains("Playlist unavailable")
    }
    if (recovered.isEmpty()) return fresh
    val recoveredKinds = recovered.map { if (isVodEntry(it, true)) "series" else if (isVodEntry(it, false)) "vod" else "live" }.toSet()
    val warnings = fresh.warnings.filterNot { warning ->
        warning == "Playlist unavailable" || recoveredKinds.any { warning == "$it catalogue unavailable" }
    } + "saved catalogue; refresh unavailable"
    return PlaylistLoad((fresh.entries + recovered).distinctBy { it.url }, warnings)
}
