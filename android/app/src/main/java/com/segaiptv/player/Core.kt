package com.segaiptv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

// Protect the UI from very large playlists while reading the source as a stream.
private const val MAX_CHANNELS_PER_PLAYLIST = 50000

data class Playlist(val id: Int, val name: String, val url: String)
data class Channel(val name: String, val url: String, val category: String, val logo: String, val playlist: String,
    val mediaType: String = "", val seriesTitle: String = "",
    val season: Int? = null, val episode: Int? = null, val description: String = "",
    val sourceUrl: String = "", val seriesId: String = "",
    val streamId: String = "", val epgId: String = "", val epgUrl: String = "")
data class AppConfig(
    val active: Boolean = false,
    val appName: String = "SEGA IPTV Player",
    val accent: String = "#E5474C",
    val background: String = "",
    val logo: String = "",
    val expires: String = "",
    val playlists: List<Playlist> = emptyList()
)

class DeviceIdentity(internal val context: Context) {
    private val prefs = context.getSharedPreferences("segaiptv_identity", Context.MODE_PRIVATE)
    val id: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
    val key: String by lazy {
        prefs.getString("device_key", null) ?: ((1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[java.security.SecureRandom().nextInt(32)] }.joinToString("")).also {
            prefs.edit().putString("device_key", it).apply()
        }
    }
    // This is a synthetic MAC-shaped *app identifier*; Android blocks normal apps from
    // accessing actual hardware MAC addresses on modern Android.
    val virtualMac: String by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        bytes.take(6).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
    var localUri: String?
        get() = prefs.getString("local_uri", null)
        set(value) { prefs.edit().putString("local_uri", value).apply() }

    fun readFavorites(): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString("favorites", "[]"))
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun saveFavorites(favorites: Set<String>) {
        prefs.edit().putString("favorites", JSONArray(favorites.toList()).toString()).apply()
    }
}

object Api {
    suspend fun registerAndFetch(identity: DeviceIdentity): AppConfig = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("device_id", identity.id)
            put("mac_alias", identity.virtualMac)
            put("device_key", identity.key)
            val androidId = android.provider.Settings.Secure.getString(
                identity.context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank()) put("trial_token",
                MessageDigest.getInstance("SHA-256").digest(("sega-trial-v1:" + androidId).toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        fun signedRequest(path: String, method: String, body: String? = null, key: String? = null): JSONObject {
            val proof = if (BuildConfig.DEVICE_PROOF) DeviceProof.headers(identity.id, method, path, body) else emptyMap()
            return BackendTransport.request(BackendAddress.value(), path, method, body, key, proof)
        }
        if (BuildConfig.DEVICE_PROOF) {
            val serverTime = BackendTransport.request(BackendAddress.value(), "/api/v1/time", "GET")
            DeviceProof.synchronize(serverTime.getLong("unix_seconds"))
        }
        signedRequest("/api/v1/register", "POST", payload.toString())
        val response = signedRequest("/api/v1/config?device_id=${identity.id}", "GET", key = identity.key)
        val rawPlaylists = response.optJSONArray("playlists") ?: JSONArray()
        val lists = (0 until rawPlaylists.length()).map { n ->
            val p = rawPlaylists.getJSONObject(n)
            Playlist(p.getInt("id"), p.getString("name"), p.getString("url"))
        }
        AppConfig(
            active = response.optBoolean("active"),
            appName = response.optString("app_name", "SEGA IPTV Player"),
            accent = response.optString("accent_color", "#E5474C"),
            background = response.optString("background_url", ""),
            logo = response.optString("logo_url", ""),
            expires = response.optString("expires_at", "").takeUnless { it == "null" } ?: "",
            playlists = lists
        )
    }

    suspend fun downloadPlaylist(playlist: Playlist): PlaylistLoad = withContext(Dispatchers.IO) {
        var m3uFailed = false
        val m3u = try { PlaylistSource.load(playlist.url) { source -> downloadSource(source, playlist.name) } }
            catch (ex: Exception) {
                if (ex is kotlinx.coroutines.CancellationException) throw ex
                m3uFailed = true
                emptyList()
            }
        XtreamCatalogue.load(playlist, m3u, m3uFailed)
    }

    private fun downloadSource(source: String, listName: String): List<Channel> {
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 14000
        connection.readTimeout = 18000
        connection.setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("M3U HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                M3uParser.parseLines(reader.lineSequence(), listName, connection.url.toString())
            }
        } finally { connection.disconnect() }
    }

    suspend fun readLocalPlaylist(context: Context, uri: Uri): List<Channel> = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to read the selected file")
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            M3uParser.parseLines(reader.lineSequence(), "ملف محلي", "")
        }
    }


}

object M3uParser {
    private val attribute = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

    // Commas in quoted group names or artwork URLs are not the EXTINF title separator.
    private fun titleSeparator(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { index, char ->
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '\"' || char == '\'') -> quote = char
                quote == null && char == ',' -> return index
            }
        }
        return -1
    }

    fun parse(text: String, listName: String, baseUrl: String): List<Channel> =
        parseLines(text.lineSequence(), listName, baseUrl)

    fun parseLines(lines: Sequence<String>, listName: String, baseUrl: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var title = ""
        var group = "غير مصنف"
        var logo = ""
        var directiveGroup = ""
        var details = emptyMap<String, String>()
        var epgUrl = ""
        for (line in lines) {
            val trimmed = line.trim().trimStart('\uFEFF')
            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attribute.findAll(trimmed).associate {
                        it.groupValues[1].lowercase(java.util.Locale.ROOT) to
                            it.groupValues[2].ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] }
                    }
                    val value = (attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: attrs["tvg-url"]).orEmpty()
                    epgUrl = value.split(',').map { it.trim() }.firstNotNullOfOrNull { candidate ->
                        runCatching { URI(baseUrl).resolve(PlaylistSource.normalize(candidate)).toString() }.getOrNull()
                            ?.takeIf { candidate.isNotBlank() && (it.startsWith("https://") || it.startsWith("http://")) }
                    }.orEmpty()
                }
                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val separator = titleSeparator(trimmed)
                    val metadata = if (separator < 0) trimmed else trimmed.substring(0, separator)
                    val attrs = attribute.findAll(metadata).associate { it.groupValues[1].lowercase(java.util.Locale.ROOT) to (it.groupValues[2].ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] }) }
                    details = attrs
                    title = (if (separator < 0) attrs["tvg-name"].orEmpty() else trimmed.substring(separator + 1)).trim()
                        .ifBlank { attrs["tvg-name"].orEmpty().ifBlank { "قناة" } }
                    group = attrs["group-title"].orEmpty().ifBlank { attrs["group"].orEmpty() }.ifBlank { directiveGroup }.ifBlank { "غير مصنف" }
                    logo = listOf("poster", "cover", "tvg-logo", "logo", "stream-icon")
                        .firstNotNullOfOrNull { attrs[it]?.takeIf { value -> value.isNotBlank() } }.orEmpty()
                    logo = runCatching { URI(baseUrl).resolve(logo.replace("&amp;", "&")).toString() }
                        .getOrDefault(logo).takeIf { logo.isNotBlank() && (it.startsWith("https://") || it.startsWith("http://")) }.orEmpty()
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    directiveGroup = trimmed.substringAfter(':').trim()
                    group = directiveGroup.ifBlank { "غير مصنف" }
                }
                trimmed.isNotBlank() && !trimmed.startsWith('#') -> {
                    val resolved = runCatching {
                        if (baseUrl.isNotBlank()) URI(baseUrl).resolve(trimmed).toString() else trimmed
                    }.getOrDefault(trimmed)
                    if (resolved.startsWith("http://", true) || resolved.startsWith("https://", true)) {
                        channels += Channel(title.ifBlank { "قناة ${channels.size + 1}" }, resolved, group, logo, listName,
                            mediaType = details["media-type"] ?: details["tvg-type"] ?: details["type"].orEmpty(),
                            seriesTitle = details["series-title"] ?: details["show-title"].orEmpty(),
                            season = (details["season"] ?: details["season-number"])?.toIntOrNull(),
                            episode = (details["episode"] ?: details["episode-number"])?.toIntOrNull(),
                            description = details["plot"] ?: details["description"].orEmpty(),
                            sourceUrl = baseUrl, seriesId = details["series-id"].orEmpty(),
                            streamId = details["stream-id"].orEmpty(),
                            epgId = details["tvg-id"].orEmpty(), epgUrl = epgUrl)
                    }
                    title = ""; group = "غير مصنف"; logo = ""; details = emptyMap()
                }
            }
            if (channels.size >= MAX_CHANNELS_PER_PLAYLIST) break
        }
        return channels
    }
}



