package com.segaiptv.player

import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only group names are retained. Never constructs a full channel/VOD catalogue. */
internal object ProviderOrder {
    private val lock = Mutex()
    private val cached = linkedMapOf<String, Pair<Int, Map<String, Int>>>()
    private val attribute = Regex("""(?:^|\s)(group-title|group)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""", RegexOption.IGNORE_CASE)

    fun parse(reader: Reader, check: () -> Unit = {}): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        val buffer = CharArray(8192)
        val line = StringBuilder()
        var total = 0L
        var header = false
        fun accept() {
            val value = line.toString().trim().trimStart('\uFEFF')
            line.setLength(0)
            if (value.startsWith("#EXTM3U", true)) header = true
            val name = when {
                value.startsWith("#EXTINF", true) -> attribute.findAll(value).map { match ->
                    match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
                }.firstOrNull { it.isNotBlank() }
                value.startsWith("#EXTGRP:", true) -> value.substringAfter(':').trim()
                else -> null
            }
            if (!name.isNullOrBlank() && name !in result) {
                require(result.size < 10000) { "Provider order too large" }
                result[name] = result.size
            }
        }
        while (true) {
            check()
            val n = reader.read(buffer)
            if (n < 0) break
            total += n
            require(total <= 128L * 1024 * 1024) { "Provider order too large" }
            for (i in 0 until n) {
                if (buffer[i] == '\n') accept()
                else { require(line.length < 65536) { "Provider order line too long" }; line.append(buffer[i]) }
            }
        }
        if (line.isNotEmpty()) accept()
        require(header && result.isNotEmpty()) { "Provider order unavailable" }
        return result
    }

    fun arrange(categories: List<ProviderCategory>, ranks: Map<String, Int>): List<ProviderCategory> =
        categories.sortedBy { ranks[it.name] ?: Int.MAX_VALUE }

    suspend fun load(playlist: Playlist, refreshToken: Int): Map<String, Int> = withContext(Dispatchers.IO) {
        lock.withLock {
            cached[playlist.url]?.takeIf { it.first == refreshToken }?.let { return@withLock it.second }
            val context = currentCoroutineContext()
            val started = System.nanoTime()
            val con = URL(PlaylistSource.normalize(playlist.url)).openConnection() as HttpURLConnection
            con.connectTimeout = 12000
            con.readTimeout = 18000
            con.setRequestProperty("Accept", "audio/x-mpegurl, text/plain, */*")
            try {
                check(con.responseCode in 200..299) { "Provider order unavailable" }
                val ranks = con.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    parse(reader) {
                        context.ensureActive()
                        check(System.nanoTime() - started < 90_000_000_000L) { "Provider order timed out" }
                    }
                }
                if (cached.size >= 30) cached.remove(cached.keys.first())
                cached[playlist.url] = refreshToken to ranks
                ranks
            } finally { con.disconnect() }
        }
    }
}
