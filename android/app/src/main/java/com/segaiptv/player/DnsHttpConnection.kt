package com.segaiptv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Small adapter for the existing bounded readers; redirects and TLS remain enforced by OkHttp. */
internal class DnsHttpConnection(url: URL, private val client: OkHttpClient) : HttpsURLConnection(url) {
    private var response: Response? = null
    private var call: okhttp3.Call? = null
    private val body = ByteArrayOutputStream()
    init { require(url.protocol in setOf("http", "https")); connectTimeout = 12000; readTimeout = 18000 }
    override fun connect() {
        if (connected) return
        val request = Request.Builder().url(url)
        requestProperties.forEach { (key, values) -> values.forEach { request.addHeader(key,it) } }
        val verb = if (doOutput && requestMethod == "GET") "POST" else requestMethod
        val content = if (doOutput || verb in setOf("POST","PUT","PATCH"))
            body.toByteArray().toRequestBody(getRequestProperty("Content-Type")?.toMediaTypeOrNull()) else null
        request.method(verb, content)
        val engine = client.newBuilder().connectTimeout(connectTimeout.toLong(),TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(),TimeUnit.MILLISECONDS)
            .followRedirects(instanceFollowRedirects).followSslRedirects(false).build()
        call = engine.newCall(request.build())
        response = call!!.execute()
        connected = true
    }
    override fun disconnect() { response?.close(); call?.cancel() }
    override fun usingProxy() = false
    override fun getOutputStream(): OutputStream {
        check(!connected && doOutput)
        return object : OutputStream() {
            override fun write(b: Int) { check(body.size() < 2 * 1024 * 1024); body.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check(body.size()+len <= 2 * 1024 * 1024); body.write(b,off,len) }
        }
    }
    override fun getResponseCode(): Int { connect(); return response!!.code }
    override fun getResponseMessage(): String { connect(); return response!!.message }
    override fun getURL(): URL = response?.request?.url?.toUrl() ?: super.getURL()
    override fun getInputStream(): InputStream {
        connect()
        if (response!!.code >= 400) throw FileNotFoundException("HTTP request failed")
        return requireNotNull(response!!.body).byteStream()
    }
    override fun getErrorStream(): InputStream? { connect(); return if (response!!.code >= 400) response!!.body?.byteStream() else null }
    override fun getHeaderField(name: String?): String? { connect(); return if (name == null) "HTTP ${response!!.code}" else response!!.header(name) }
    override fun getHeaderFields(): Map<String, List<String>> { connect(); return response!!.headers.toMultimap() }
    override fun getContentLengthLong(): Long { connect(); return response!!.body?.contentLength() ?: -1 }
    override fun getCipherSuite(): String { connect(); return response!!.handshake?.cipherSuite?.javaName ?: "" }
    override fun getLocalCertificates(): Array<Certificate>? { connect(); return response!!.handshake?.localCertificates?.toTypedArray() }
    override fun getServerCertificates(): Array<Certificate> { connect(); return response!!.handshake?.peerCertificates?.toTypedArray() ?: emptyArray() }
}
