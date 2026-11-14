package com.segaiptv.player

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal object ArtworkUrls {
    fun resolve(base: String, value: String): String {
        val clean = value.trim().replace("\\/", "/").replace("&amp;", "&").trim('"', '\'')
        if (clean.isBlank() || clean.equals("null", true) || clean.startsWith("[") || clean.startsWith("{")) return ""
        val url = clean.toHttpUrlOrNull() ?: (base.trimEnd('/') + "/").toHttpUrlOrNull()?.resolve(clean) ?: return ""
        // TMDB artwork uses HTTPS; avoid an unnecessary redirect from old provider links.
        return if (url.host == "image.tmdb.org") url.newBuilder().scheme("https").build().toString() else url.toString()
    }
    fun fromJson(base: String, row: JSONObject, series: Boolean): String {
        val keys = if (series) listOf("cover", "cover_big", "stream_icon", "movie_image", "poster", "poster_path")
            else listOf("stream_icon", "movie_image", "cover", "cover_big", "poster", "poster_path")
        for (key in keys) {
            val value = row.opt(key) as? String ?: continue
            val url = if (key == "poster_path" && value.startsWith('/') && !value.startsWith("//"))
                resolve("https://image.tmdb.org", "/t/p/w500$value") else resolve(base, value)
            if (url.isNotBlank()) return url
        }
        return ""
    }
    fun details(base: String, raw: String, series: Boolean): String {
        val root = JSONObject(raw)
        for (row in listOfNotNull(root.optJSONObject("info"), root.optJSONObject("movie_data"), root)) {
            fromJson(base, row, series).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }
}

/** Only visible posters ask for missing metadata; bound work and reuse successful URLs. */
internal object PosterRepository {
    private val permits = Semaphore(3)
    private val locks = Array(16) { Mutex() }
    private val memory = object : LinkedHashMap<String, String>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }
    suspend fun resolve(context: Context, entry: Channel, forceDetails: Boolean = false,
        fetch: (String) -> String = XtreamCatalogue::request): String = withContext(Dispatchers.IO) {
        val source = XtreamSource.forEntry(entry)
        val base = source?.base ?: entry.sourceUrl
        val original = ArtworkUrls.resolve(base, entry.logo)
        if (source == null) return@withContext original
        val series = isVodEntry(entry, true)
        val id = if (series) entry.seriesId else streamId(entry)
        if (id.isBlank()) return@withContext original
        val key = mediaKey(entry.sourceUrl + "\n" + entry.url + "\n" + entry.logo)
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (!forceDetails) synchronized(memory) { memory[key] }?.let { return@withLock it }
            val prefs = context.getSharedPreferences("poster_metadata_v1", Context.MODE_PRIVATE)
            if (!forceDetails) prefs.getString(key, null)?.let {
                synchronized(memory) { memory[key] = it }; return@withLock it
            }
            if (!forceDetails && original.isNotBlank()) return@withLock original
            try {
                val raw = permits.withPermit {
                    fetch(source.api(if (series) "get_series_info" else "get_vod_info", id,
                        if (series) "series_id" else "vod_id"))
                }
                val url = ArtworkUrls.details(source.base, raw, series).ifBlank { original }
                // Do not retain an empty result as a permanent cache hit.
                // A missing provider poster can become available on the next visit.
                if (url.isNotBlank()) {
                    synchronized(memory) { memory[key] = url }
                    val edit = prefs.edit()
                    if (prefs.all.size >= 512) prefs.all.keys.take(64).forEach { edit.remove(it) }
                    edit.putString(key, url).apply()
                }
                url
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                // A timeout or TLS failure must not poison the metadata
                // cache. The existing catalogue URL remains a usable fallback.
                original
            }
        }
    }
}
