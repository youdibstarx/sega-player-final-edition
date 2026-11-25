package com.segaiptv.player

import android.content.Context
import android.util.Base64
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONObject

internal object BackendEndpoints {
    private const val backup = "https://tchach.online"
    private val hostPattern = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
    internal fun normalize(value: String): String {
        val uri = URI(value)
        val host = uri.host ?: error("Invalid endpoint")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && hostPattern.matches(host))
        return "https://$host"
    }
    internal fun retryable(ex: Exception): Boolean =
        ex is BackendTransportFailure && ex.reason != BackendTransportFailure.Reason.UNKNOWN ||
            ex is BackendHttpError && ex.statusCode in setOf(502, 503, 504, 500)

    internal fun <T> failover(endpoints: List<String>, action: (String) -> T): Pair<String, T> {
        require(endpoints.isNotEmpty())
        var last: Exception? = null
        for (endpoint in endpoints) {
            try { return endpoint to action(endpoint) }
            catch (ex: Exception) {
                if (!retryable(ex)) throw ex
                last = ex
            }
        }
        throw checkNotNull(last)
    }

    internal fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withRSA").run { initVerify(key); update(payload); verify(signature) }
        }.getOrDefault(false)

    private data class Config(val revision: Long, val urls: List<String>)
    private fun decode(envelope: String): Config? = runCatching {
        if (BuildConfig.ENDPOINT_PUBLIC_KEY.isBlank() || envelope.length > 16384) return null
        val json = JSONObject(envelope)
        val payload = Base64.decode(json.getString("payload"), Base64.NO_WRAP)
        val signature = Base64.decode(json.getString("signature"), Base64.NO_WRAP)
        if (!verify(Base64.decode(BuildConfig.ENDPOINT_PUBLIC_KEY, Base64.NO_WRAP), payload, signature)) return null
        val data = JSONObject(payload.toString(Charsets.UTF_8))
        require(data.getString("schema") == "sega-endpoints-v1")
        val rev = data.getLong("revision")
        require(rev >= 1)
        val urls = listOf(normalize(data.getString("primary")), normalize(data.getString("backup")))
        require(urls.distinct().size == 2)
        Config(rev, urls)
    }.getOrNull()

    // Discovery requests contain no identity, proof or device key. Signed cache is verified on every read.
    @Synchronized
    fun resolve(context: Context): List<String> {
        val defaults = listOf(BackendAddress.builtIn(), backup).distinct()
        if (BuildConfig.ENDPOINT_PUBLIC_KEY.isBlank()) return defaults
        val prefs = context.getSharedPreferences("sega_endpoints_v1", Context.MODE_PRIVATE)
        var best = decode(prefs.getString("envelope", "").orEmpty())
        val discovery = (best?.urls.orEmpty() + defaults).distinct()
        for (url in discovery) {
            val envelope = runCatching {
                BackendTransport.request(url, "/api/v1/endpoints", "GET",
                    connectTimeoutMs = 4000, readTimeoutMs = 4000).toString()
            }.getOrNull() ?: continue
            val candidate = decode(envelope) ?: continue
            if (candidate.revision > (best?.revision ?: 0L)) {
                if (prefs.edit().putString("envelope", envelope).commit()) best = candidate
            }
            // Once a non-stale signed response is found, avoid extra network calls.
            if (candidate.revision == best?.revision && candidate.urls == best?.urls) break
        }
        // Never send credentials to a bootstrap address removed by signed configuration.
        return best?.urls ?: defaults
    }
}
