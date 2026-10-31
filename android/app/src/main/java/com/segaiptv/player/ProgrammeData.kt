package com.segaiptv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal data class TitleDetails(val synopsis: String, val episodes: List<Channel> = emptyList())
internal data class EpgProgramme(val title: String, val description: String, val start: Long, val end: Long)
internal fun streamId(item: Channel): String = item.streamId.ifBlank {
    runCatching { URI(item.url).path.substringAfterLast('/').substringBefore('.') }.getOrDefault("")
}.takeIf { it.matches(Regex("[0-9]+")) }.orEmpty()

internal fun cleanSynopsis(value: String): String = value.replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
    .takeUnless { it == "null" }.orEmpty()

internal fun decodeEpgText(value: String): String {
    val decoded = runCatching {
        val bytes = Base64.getDecoder().decode(value)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        text.takeIf { it.isNotBlank() && it.none { c -> c.isISOControl() && c !in "\n\r\t" } }
    }.getOrNull()
    return cleanSynopsis(decoded ?: value)
}

internal object ProgrammeData {
    private data class Cached(val time: Long, val value: Any)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 80
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, ttl: Long): T? = synchronized(cache) {
        cache[key]?.takeIf { System.currentTimeMillis() - it.time < ttl }?.value as? T
    }
    private fun save(key: String, value: Any) = synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), value) }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()

    internal fun parseSynopsis(json: String): String {
        val root = JSONObject(json)
        val info = root.optJSONObject("info") ?: root
        return cleanSynopsis(info.text("plot").ifBlank { info.text("description") }
            .ifBlank { root.optJSONObject("movie_data")?.text("plot").orEmpty() })
    }

    suspend fun details(item: Channel, force: Boolean = false, fetch: (String) -> String = XtreamCatalogue::request): TitleDetails = withContext(Dispatchers.IO) {
        val key = "details:${item.sourceUrl}:${item.url}"
        if (!force) cached<TitleDetails>(key, 10 * 60_000L)?.let { return@withContext it }
        val source = XtreamSource.forEntry(item)
        val isSeries = isVodEntry(item, true)
        val id = if (isSeries) item.seriesId else streamId(item)
        if (source == null || id.isBlank()) return@withContext TitleDetails(cleanSynopsis(item.description))
        // Episode stream IDs are not series IDs. Never send one as series_id.
        val raw = fetch(source.api(if (isSeries) "get_series_info" else "get_vod_info",
            id, if (isSeries) "series_id" else "vod_id"))
        val details = TitleDetails(parseSynopsis(raw).ifBlank { cleanSynopsis(item.description) },
            if (isSeries) XtreamCatalogue.parseEpisodes(source, item, raw) else emptyList())
        save(key, details)
        details
    }

    internal fun parseEpg(json: String, now: Long): List<EpgProgramme> {
        val rows = JSONObject(json).optJSONArray("epg_listings") ?: return emptyList()
        fun epoch(value: String): Long? = value.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
        return (0 until minOf(rows.length(), 500)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val start = epoch(row.text("start_timestamp")) ?: return@mapNotNull null
            val end = epoch(row.text("stop_timestamp").ifBlank { row.text("end_timestamp") }) ?: return@mapNotNull null
            if (end <= now || end <= start) return@mapNotNull null
            EpgProgramme(decodeEpgText(row.text("title")), decodeEpgText(row.text("description")), start, end)
        }.sortedBy { it.start }.distinctBy { it.start to it.title }.take(12)
    }

    suspend fun epg(item: Channel, force: Boolean = false): List<EpgProgramme> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "epg:${item.sourceUrl}:${item.epgUrl}:${item.epgId}:${item.url}"
        if (!force) cached<List<EpgProgramme>>(key, 120_000L)?.let { return@withContext it.filter { p -> p.end > now } }
        val source = XtreamSource.forEntry(item)
        val id = streamId(item)
        var failure = false
        if (source != null && id.isNotBlank()) {
            try {
                val programmes = parseEpg(XtreamCatalogue.request(source.api("get_short_epg", id, "stream_id") + "&limit=12"), now)
                if (programmes.isNotEmpty()) { save(key, programmes); return@withContext programmes }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                failure = true
            }
        }
        val xmlUrl = item.epgUrl.ifBlank { if (item.epgId.isNotBlank()) source?.xmltv().orEmpty() else "" }
        if (xmlUrl.isNotBlank() && item.epgId.isNotBlank()) {
            val programmes = XmltvGuide.download(xmlUrl, item.epgId, now)
            save(key, programmes)
            return@withContext programmes
        }
        if (failure) error("EPG unavailable")
        emptyList()
    }
}
