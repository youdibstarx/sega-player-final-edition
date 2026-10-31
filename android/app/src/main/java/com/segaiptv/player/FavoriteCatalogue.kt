package com.segaiptv.player

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small private metadata snapshot so favourites do not require downloading every category. */
internal object FavoriteCatalogue {
    private val writeLock = Mutex()
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "favorite_catalogue.json"))
    suspend fun read(context: Context, favorites: Set<String>, playlists: List<Playlist>): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (f.baseFile.length() > 8 * 1024 * 1024) return@withContext emptyList()
            val array = f.openRead().bufferedReader().use { JSONArray(it.readText()) }
            val allowed = playlists.map { it.url }.toSet()
            (0 until minOf(array.length(), 5000)).mapNotNull { i ->
                runCatching { channelFromJson(array.getJSONObject(i)) }.getOrNull()
            }.filter { it.url in favorites && it.sourceUrl in allowed }
        } catch (_: Exception) { emptyList() }
    }
    suspend fun write(context: Context, entries: List<Channel>) = withContext(Dispatchers.IO) {
        writeLock.withLock { writeSnapshot(context, entries) }
    }
    private fun writeSnapshot(context: Context, entries: List<Channel>) {
        val array = JSONArray()
        entries.distinctBy { it.url }.take(5000).forEach { array.put(it.toJson()) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 8 * 1024 * 1024) return
        val f = file(context)
        var stream: java.io.FileOutputStream? = null
        try { stream = f.startWrite(); stream.write(bytes); f.finishWrite(stream) }
        catch (_: Exception) { stream?.let { f.failWrite(it) } }
    }
}
