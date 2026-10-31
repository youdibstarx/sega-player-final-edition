package com.segaiptv.player

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal fun mediaKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun Channel.toJson() = JSONObject().apply {
    put("name", name); put("url", url); put("category", category); put("logo", logo); put("playlist", playlist)
    put("mediaType", mediaType); put("seriesTitle", seriesTitle); put("season", season); put("episode", episode)
    put("description", description); put("sourceUrl", sourceUrl); put("seriesId", seriesId)
    put("streamId", streamId); put("epgId", epgId); put("epgUrl", epgUrl)
}
internal fun channelFromJson(o: JSONObject) = Channel(o.getString("name"), o.getString("url"),
    o.optString("category"), o.optString("logo"), o.optString("playlist"), o.optString("mediaType"),
    o.optString("seriesTitle"), if (o.has("season")) o.getInt("season") else null,
    if (o.has("episode")) o.getInt("episode") else null, o.optString("description"), o.optString("sourceUrl"),
    o.optString("seriesId"), o.optString("streamId"), o.optString("epgId"), o.optString("epgUrl"))
internal fun isVod(channel: Channel) = isVodEntry(channel, false) || isVodEntry(channel, true)
internal fun resumablePosition(position: Long, duration: Long): Long =
    if (position < 10_000 || (duration > 0 && duration - position < 30_000)) 0 else position
internal data class WatchEntry(val channel: Channel, val position: Long, val duration: Long)

internal class ViewerStore internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences("sega_viewer_v1", Context.MODE_PRIVATE)
    var revision by mutableIntStateOf(0); private set
    var scale by mutableFloatStateOf(prefs.getFloat("scale", 1f)); private set
    var unlocked by mutableStateOf(false); private set
    private var failures = 0
    private var blockedUntil = 0L
    var lastPlaybackCode by mutableStateOf(""); private set
    val pinEnabled get() = prefs.contains("pin")
    private fun groups() = prefs.getStringSet("groups", emptySet()).orEmpty()
    private fun items() = prefs.getStringSet("items", emptySet()).orEmpty()
    fun groupKey(c: Channel) = mediaKey(c.playlist + "\n" + c.category)
    fun isLocked(c: Channel) = pinEnabled && (groupKey(c) in groups() || mediaKey(c.url) in items())
    fun blocked(c: Channel) = isLocked(c) && !unlocked
    fun lock(c: Channel, category: Boolean) {
        check(pinEnabled && unlocked)
        val field = if (category) "groups" else "items"
        val values = (if (category) groups() else items()).toMutableSet()
        values.add(if (category) groupKey(c) else mediaKey(c.url))
        prefs.edit().putStringSet(field, values).apply(); unlocked = false; revision++
    }
    fun relock() { unlocked = false }
    fun changeScale(value: Float) {
        scale = value.coerceIn(.85f, 1.15f); prefs.edit().putFloat("scale", scale).apply()
    }
    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60000, 256)
        return try { java.util.Base64.getEncoder().encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    fun setPin(pin: String) {
        require(pin.matches(Regex("[0-9]{4,8}")))
        check(!pinEnabled || unlocked)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("salt", java.util.Base64.getEncoder().encodeToString(salt))
            .putString("pin", hashPin(pin, salt)).apply()
        unlocked = false; revision++
    }
    fun verify(pin: String): Boolean {
        if (System.currentTimeMillis() < blockedUntil || !pin.matches(Regex("[0-9]{4,8}"))) return false
        val salt = runCatching { java.util.Base64.getDecoder().decode(prefs.getString("salt", "")) }.getOrNull() ?: return false
        val valid = MessageDigest.isEqual(hashPin(pin, salt).toByteArray(), prefs.getString("pin", "").orEmpty().toByteArray())
        if (valid) { failures = 0; unlocked = true }
        else if (++failures >= 5) { blockedUntil = System.currentTimeMillis() + 30_000; failures = 0 }
        return valid
    }
    fun clearLocks() {
        check(unlocked)
        prefs.edit().remove("pin").remove("salt").remove("groups").remove("items").apply()
        unlocked = false; revision++
    }
    fun playbackError(code: String) { lastPlaybackCode = code.take(80) }
    fun history(vod: Boolean): List<WatchEntry> {
        revision // observable invalidation
        return runCatching {
            val a = JSONArray(prefs.getString(if (vod) "vod_history" else "live_history", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                WatchEntry(channelFromJson(o.getJSONObject("channel")), o.optLong("position"), o.optLong("duration"))
            }
        }.getOrDefault(emptyList())
    }
    fun position(c: Channel) = history(true).firstOrNull { it.channel.url == c.url }?.position ?: 0L
    fun record(c: Channel, position: Long = 0, duration: Long = 0, ended: Boolean = false) {
        val vod = isVod(c)
        val remaining = history(vod).filter { it.channel.url != c.url }
        val p = if (vod) resumablePosition(position, duration) else 0
        val records = if (vod && (ended || p == 0L)) remaining else
            listOf(WatchEntry(c, p, duration)) + remaining
        val data = JSONArray()
        records.take(30).forEach { e -> data.put(JSONObject().put("channel", e.channel.toJson())
            .put("position", e.position).put("duration", e.duration)) }
        prefs.edit().putString(if (vod) "vod_history" else "live_history", data.toString()).apply()
        revision++
    }
    fun clearHistory() { prefs.edit().remove("vod_history").remove("live_history").apply(); revision++ }
    companion object {
        @Volatile private var instance: ViewerStore? = null
        fun get(context: Context): ViewerStore = instance ?: synchronized(this) {
            instance ?: ViewerStore(context.applicationContext).also { instance = it }
        }
    }
}

internal fun nextEpisode(current: Channel, entries: List<Channel>): Channel? {
    val label = episodeLabel(current)
    if (!isVodEntry(current, true) || label.season == null || label.episode == null) return null
    return entries.filter {
        val other = episodeLabel(it)
        isVodEntry(it, true) && it.sourceUrl == current.sourceUrl && it.playlist == current.playlist &&
            it.category == current.category &&
            (if (current.seriesId.isNotBlank()) it.seriesId == current.seriesId else other.title.equals(label.title, true)) &&
            other.season != null && other.episode != null &&
            (other.season > label.season || (other.season == label.season && other.episode > label.episode))
    }.minWithOrNull(compareBy({ episodeLabel(it).season }, { episodeLabel(it).episode }))
}
