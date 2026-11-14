package com.segaiptv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class SubscriptionInfo(val expiry: Long? = null, val unlimited: Boolean = false,
    val status: String = "", val available: Boolean = false)

internal object SubscriptionRepository {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, SubscriptionInfo>>()
    fun parse(raw: String): SubscriptionInfo {
        val user = JSONObject(raw).optJSONObject("user_info") ?: return SubscriptionInfo()
        if (user.optInt("auth", 0) != 1) return SubscriptionInfo()
        val value = user.optString("exp_date", "")
        val expiry = value.toLongOrNull()?.takeIf { it > 0 && it <= 253402300799L }
        val unlimited = user.has("exp_date") && (user.isNull("exp_date") || value.isBlank() || value == "0")
        return SubscriptionInfo(expiry, unlimited, user.optString("status"), true)
    }
    suspend fun fetch(playlist: Playlist): SubscriptionInfo = withContext(Dispatchers.IO) {
        val source = XtreamSource.from(playlist.url) ?: return@withContext SubscriptionInfo()
        mutex.withLock {
            val key = mediaKey(playlist.url)
            val now = android.os.SystemClock.elapsedRealtime()
            cache[key]?.takeIf { now - it.first in 0 until 15 * 60_000L }?.let { return@withLock it.second }
            val info = try { parse(XtreamCatalogue.request(source.api("").substringBefore("&action="))) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; SubscriptionInfo() }
            if (cache.size >= 30) cache.remove(cache.keys.first())
            cache[key] = now to info
            info
        }
    }
    fun daysRemaining(expiry: Long, now: Long): Long =
        if (expiry <= now) 0 else (expiry - now + 86399) / 86400
    fun date(expiry: Long, language: String): String = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag(language))
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(expiry))
}
