package com.segaiptv.player

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Category-sized snapshots, keyed by source and section; credentials never appear in filenames. */
internal object CategoryDiskCache {
    private val refreshed = mutableMapOf<String, Int>()
    private fun key(p: Playlist, kind: String) = mediaKey(p.url) + "-" + kind
    private fun file(c: Context, p: Playlist, kind: String) = File(
        File(c.noBackupFilesDir, "category_index_v1").apply { mkdirs() }, key(p, kind) + ".json")
    suspend fun categories(c: Context, p: Playlist, kind: String, token: Int,
        fetch: suspend () -> List<ProviderCategory>): List<ProviderCategory> = withContext(Dispatchers.IO) {
        val k = key(p, kind)
        val saved = runCatching {
            val f = file(c, p, kind)
            require(f.length() <= 4 * 1024 * 1024)
            AtomicFile(f).openRead().bufferedReader().use { CategoryCatalogue.parseCategories(p, kind, it.readText()) }
        }.getOrNull()
        val freshNeeded = token > 0 && synchronized(refreshed) { refreshed[k] != token }
        if (saved != null && !freshNeeded) return@withContext saved
        try {
            val rows = fetch()
            val json = JSONArray()
            rows.forEach { category ->
                json.put(JSONObject().put("category_id", category.id).put("category_name", category.name)
                    .apply { category.channelCount?.let { put("streams_count", it) } })
            }
            val atomic = AtomicFile(file(c, p, kind))
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite(); stream.write(json.toString().toByteArray())
                atomic.finishWrite(stream); stream = null
            } catch (_: Exception) { stream?.let { atomic.failWrite(it) } }
            synchronized(refreshed) { refreshed[k] = token }
            rows
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            saved ?: throw ex
        }
    }
    suspend fun entries(c: Context, category: ProviderCategory, token: Int,
        fetch: suspend () -> List<Channel>): List<Channel> = withContext(Dispatchers.IO) {
        val scope = "${category.kind}:${category.id}"
        val k = key(category.playlist, scope)
        val saved = LibraryCache.read(c, category.playlist, scope)
        val freshNeeded = token > 0 && synchronized(refreshed) { refreshed[k] != token }
        if (saved.isNotEmpty() && !freshNeeded) return@withContext saved
        try {
            val rows = fetch()
            if (rows.isEmpty() && saved.isNotEmpty()) return@withContext saved
            LibraryCache.write(c, category.playlist, rows, scope)
            synchronized(refreshed) { refreshed[k] = token }
            rows
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            saved.takeIf { it.isNotEmpty() } ?: throw ex
        }
    }
}

