package com.segaiptv.player

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Explicit server denial, distinct from a temporary network failure. No response data retained. */
internal class BackendAccessDenied : IllegalStateException("Backend access denied")

/** Applies only to the control panel. Provider media URLs have independent transport requirements. */
internal object BackendTransport {
    internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    internal fun endpoint(base: String, path: String): URL {
        val root = try { URI(base.trimEnd('/')) } catch (_: Exception) { error("Invalid backend address") }
        require(root.scheme == "https" && !root.host.isNullOrBlank() && root.rawUserInfo == null &&
            root.rawQuery == null && root.rawFragment == null) { "Backend HTTPS is required" }
        require(path.startsWith("/api/v1/") && !path.contains("\\") &&
            !path.substringBefore('?').contains("..") && !path.contains('#')) { "Invalid API path" }
        return try { URI(root.toString() + path).toURL() }
        catch (_: Exception) { error("Invalid API path") }
    }
    internal fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            check(output.size() + size <= MAX_RESPONSE_BYTES) { "Backend response too large" }
            output.write(buffer, 0, size)
        }
        return output.toString("UTF-8")
    }
    internal fun request(base: String, path: String, method: String, payload: String? = null,
        deviceKey: String? = null, proofHeaders: Map<String, String> = emptyMap(), open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }): JSONObject {
        val url = endpoint(base, path)
        var connection: HttpsURLConnection? = null
        try {
            val con = open(url)
            connection = con
            // Keep Android's normal certificate chain and hostname verification.
            con.instanceFollowRedirects = false
            con.useCaches = false
            con.connectTimeout = 12000
            con.readTimeout = 15000
            con.requestMethod = method
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("Cache-Control", "no-store")
            proofHeaders.forEach { (name, value) -> con.setRequestProperty(name, value) }
            if (deviceKey != null) con.setRequestProperty("X-Device-Key", deviceKey)
            if (payload != null) {
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = con.responseCode
            if (status == 401 || status == 403) throw BackendAccessDenied()
            // Never follow a Location header carrying a device key or registration body.
            check(status in 200..299) { "Backend request rejected" }
            check(con.contentLengthLong <= MAX_RESPONSE_BYTES) { "Backend response too large" }
            return con.inputStream.use { JSONObject(readBounded(it)) }
        } catch (denied: BackendAccessDenied) {
            throw denied
        } catch (_: javax.net.ssl.SSLException) {
            throw IllegalStateException("Backend TLS verification failed")
        } catch (_: java.net.SocketTimeoutException) {
            throw IllegalStateException("Backend request timed out")
        } catch (_: Exception) {
            // Do not propagate server bodies, URLs, request headers or nested exception messages.
            throw IllegalStateException("Backend request failed")
        } finally { connection?.disconnect() }
    }
}


