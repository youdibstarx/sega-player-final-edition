package com.segaiptv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

data class PlaylistLoad(val entries: List<Channel>, val warnings: List<String> = emptyList())

/** Credentials stay in memory and are sent only to the provider from the assigned playlist. */
internal class XtreamSource private constructor(
    val base: String, private val username: String, private val password: String
) {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun api(action: String, id: String? = null, idKey: String = "series_id"): String =
        "$base/player_api.php?username=${encode(username)}&password=${encode(password)}&action=$action" +
            (id?.let { "&$idKey=${encode(it)}" } ?: "")
    fun xmltv(): String = "$base/xmltv.php?username=${encode(username)}&password=${encode(password)}"
    fun stream(kind: String, id: String, extension: String): String {
        require(id.matches(Regex("[0-9]+")))
        val ext = extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "mp4"
        return "$base/$kind/${encode(username)}/${encode(password)}/$id.$ext"
    }
    fun artwork(value: String): String {
        if (value.isBlank() || value == "null") return ""
        return runCatching { URI("$base/").resolve(value.replace("&amp;", "&")).toString() }
            .getOrDefault("").takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
    }
    companion object {
        fun forEntry(item: Channel): XtreamSource? = from(item.sourceUrl) ?: runCatching {
            val uri = URI(item.url)
            if (uri.scheme !in listOf("http", "https") || uri.host == null) return null
            val match = Regex("""^(.*)/(live|movie|series)/([^/]+)/([^/]+)/[0-9]+(?:\.[^/]+)?$""").matchEntire(uri.rawPath) ?: return null
            fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            XtreamSource("${uri.scheme}://${uri.rawAuthority}${match.groupValues[1]}",
                decode(match.groupValues[3]), decode(match.groupValues[4]))
        }.getOrNull()
        fun from(url: String): XtreamSource? = runCatching {
            val uri = URI(PlaylistSource.normalize(url))
            if (uri.scheme !in listOf("http", "https") || uri.host == null ||
                !uri.path.endsWith("/get.php", true)) return null
            val query = uri.rawQuery.orEmpty().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val user = query["username"]?.takeIf { it.isNotBlank() } ?: return null
            val pass = query["password"]?.takeIf { it.isNotBlank() } ?: return null
            XtreamSource("${uri.scheme}://${uri.rawAuthority}${uri.rawPath.substringBeforeLast('/')}", user, pass)
        }.getOrNull()
    }
}

internal object XtreamCatalogue {
    private const val LIMIT = 50000
    private fun JSONObject.text(key: String) = optString(key, "").takeUnless { it == "null" }.orEmpty()
    private fun JSONArray.objects() = (0 until minOf(length(), LIMIT)).mapNotNull { optJSONObject(it) }
    internal fun request(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 12000
        con.readTimeout = 18000
        con.setRequestProperty("Accept", "application/json")
        try {
            check(con.responseCode in 200..299) { "Provider catalogue unavailable" }
            // Bound oversized responses; never include response bodies or credential-bearing URLs in errors.
            return con.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    check(result.length + n <= 32 * 1024 * 1024) { "Provider catalogue too large" }
                    result.append(buffer, 0, n)
                }
                result.toString()
            }
        } finally { con.disconnect() }
    }

    internal fun parseSection(source: XtreamSource, playlist: Playlist, kind: String,
        json: String, categoriesJson: String): List<Channel> {
        val categories = JSONArray(categoriesJson).objects().associate { it.text("category_id") to it.text("category_name") }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(playlist.url.toByteArray())
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        val rows = JSONArray(json)
        return (0 until minOf(rows.length(), LIMIT)).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text(if (kind == "series") "series_id" else "stream_id")
            if (!id.matches(Regex("[0-9]+"))) return@mapNotNull null
            val name = item.text("name").ifBlank { return@mapNotNull null }
            val group = categories[item.text("category_id")].orEmpty().ifBlank { "Other" }
            val cover = source.artwork(item.text(if (kind == "series") "cover" else "stream_icon"))
            val url = if (kind == "series") "xtream-series:$fingerprint:$id"
                else source.stream(if (kind == "vod") "movie" else "live", id,
                    if (kind == "live") "ts" else item.text("container_extension").ifBlank { "mp4" })
            Channel(name, url, group, cover, playlist.name,
                mediaType = if (kind == "vod") "movie" else kind,
                seriesTitle = if (kind == "series") name else "",
                description = item.text("plot"), sourceUrl = playlist.url,
                seriesId = if (kind == "series") id else "",
                streamId = if (kind != "series") id else "", epgId = item.text("epg_channel_id"))
        }
    }

    suspend fun load(playlist: Playlist, m3u: List<Channel>, m3uFailed: Boolean,
        fetch: (String) -> String = ::request): PlaylistLoad = coroutineScope {
        val fallback = m3u.map { it.copy(sourceUrl = playlist.url) }
        val source = XtreamSource.from(playlist.url)
        if (source == null) return@coroutineScope PlaylistLoad(fallback,
            if (m3uFailed) listOf("Playlist unavailable") else emptyList())
        // Fetch sections sequentially: three large JSON bodies must not coexist on TV boxes.
        val sections = listOf("live", "vod", "series").map { kind ->
            withContext(Dispatchers.IO) {
                try {
                    val raw = fetch(source.api(if (kind == "series") "get_series" else "get_${kind}_streams"))
                    val categoryRaw = try { fetch(source.api("get_${kind}_categories")) }
                        catch (ex: Exception) { if (ex is CancellationException) throw ex; "[]" }
                    kind to parseSection(source, playlist, kind, raw, categoryRaw)
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    kind to null
                }
            }
        }.toMap()
        val warnings = sections.filterValues { it == null }.keys.map { kind ->
            val hasFallback = fallback.any { entry ->
                when (kind) {
                    "vod" -> isVodEntry(entry, false)
                    "series" -> isVodEntry(entry, true)
                    else -> !isVodEntry(entry, false) && !isVodEntry(entry, true)
                }
            }
            if (hasFallback) "$kind M3U fallback" else "$kind catalogue unavailable"
        }
        val live = sections["live"]
        // Retain original stream URLs where IDs match, preserving favourites and provider output options.
        val oldById = fallback.groupBy { runCatching { URI(it.url).path.substringAfterLast('/').substringBefore('.') }.getOrDefault("") }
        val nativeLive = live?.map { entry ->
            val id = URI(entry.url).path.substringAfterLast('/').substringBefore('.')
            val old = oldById[id]?.firstOrNull { !isVodEntry(it, false) && !isVodEntry(it, true) }
            if (old != null) entry.copy(url = old.url, logo = entry.logo.ifBlank { old.logo }, category = entry.category.takeUnless { it == "Other" } ?: old.category,
                epgId = entry.epgId.ifBlank { old.epgId }, epgUrl = old.epgUrl) else entry
        }
        val result = (nativeLive ?: fallback.filter { !isVodEntry(it, false) && !isVodEntry(it, true) }) +
            (sections["vod"] ?: fallback.filter { isVodEntry(it, false) }) +
            (sections["series"] ?: fallback.filter { isVodEntry(it, true) })
        PlaylistLoad(result, warnings)
    }

    internal fun parseEpisodes(source: XtreamSource, show: Channel, json: String): List<Channel> {
        val root = JSONObject(json)
        val buckets = root.optJSONObject("episodes") ?: return emptyList()
        val result = mutableListOf<Channel>()
        for (seasonKey in buckets.keys()) {
            val episodes = buckets.optJSONArray(seasonKey) ?: continue
            for (episode in episodes.objects()) {
                val id = episode.text("id")
                if (!id.matches(Regex("[0-9]+"))) continue
                val info = episode.optJSONObject("info")
                result += Channel(episode.text("title").ifBlank { show.name },
                    source.stream("series", id, episode.text("container_extension").ifBlank { "mp4" }),
                    show.category, source.artwork(info?.text("movie_image").orEmpty()).ifBlank { show.logo },
                    show.playlist, "episode", show.name,
                    episode.text("season").toIntOrNull() ?: seasonKey.toIntOrNull(),
                    episode.text("episode_num").toIntOrNull(), info?.text("plot").orEmpty(),
                    show.sourceUrl, show.seriesId)
                if (result.size >= LIMIT) break
            }
            if (result.size >= LIMIT) break
        }
        return result.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    suspend fun episodes(show: Channel): List<Channel> = withContext(Dispatchers.IO) {
        val source = XtreamSource.from(show.sourceUrl) ?: error("Series source unavailable")
        parseEpisodes(source, show, request(source.api("get_series_info", show.seriesId)))
    }
}
