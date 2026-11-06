package com.segaiptv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

enum class DnsMode { AUTOMATIC, GOOGLE, CLOUDFLARE }

/** One transport for catalogues, playback, images, panel and updates. TLS validation stays enabled. */
internal object NetworkClient {
    @Volatile private var context: Context? = null
    @Volatile var mode = DnsMode.AUTOMATIC
        private set
    private val bootstrap = OkHttpClient.Builder().dns(Dns.SYSTEM)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).build()
    private val google by lazy { resolver("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4") }
    private val cloudflare by lazy { resolver("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1") }
    private fun resolver(url: String, vararg ips: String): Dns = DnsOverHttps.Builder()
        .client(bootstrap).url(url.toHttpUrl())
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build()
    val client: OkHttpClient = bootstrap.newBuilder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
        // Let a customer VPN resolve its own private domains and avoid bypassing its DNS policy.
        val resolver = if (vpnActive()) Dns.SYSTEM else when (mode) {
            DnsMode.AUTOMATIC -> Dns.SYSTEM
            DnsMode.GOOGLE -> google
            DnsMode.CLOUDFLARE -> cloudflare
        }
        return resolver.lookup(hostname)
        }
    }).build()
    fun initialize(app: Context) {
        context = app.applicationContext
        mode = runCatching { DnsMode.valueOf(app.getSharedPreferences("network", Context.MODE_PRIVATE)
            .getString("dns", DnsMode.AUTOMATIC.name)!!) }.getOrDefault(DnsMode.AUTOMATIC)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).okHttpClient(client).build())
    }
    fun select(app: Context, selected: DnsMode) {
        mode = selected
        app.getSharedPreferences("network", Context.MODE_PRIVATE).edit().putString("dns", selected.name).apply()
        resetConnections()
    }
    fun resetConnections() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    fun vpnActive(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    fun open(url: URL): HttpURLConnection = DnsHttpConnection(url, client)
}
