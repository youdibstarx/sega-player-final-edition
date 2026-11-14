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
    private fun resolver(url: String, vararg ips: String): Dns = try {
        DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build()
    } catch (_: Exception) {
        Dns.SYSTEM
    }
    val client: OkHttpClient = bootstrap.newBuilder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Let a customer VPN resolve its own private domains and avoid bypassing its DNS policy.
            val resolver = if (vpnActive()) Dns.SYSTEM else when (mode) {
                DnsMode.AUTOMATIC -> Dns.SYSTEM
                DnsMode.GOOGLE -> google
                DnsMode.CLOUDFLARE -> cloudflare
            }
            if (resolver === Dns.SYSTEM) return Dns.SYSTEM.lookup(hostname)
            return try {
                resolver.lookup(hostname).ifEmpty { Dns.SYSTEM.lookup(hostname) }
            } catch (_: Exception) {
                // A DoH outage, blocked endpoint or older Android TLS issue must not
                // crash playback or the app; fall back to the device's resolver.
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }).build()
    fun initialize(app: Context) {
        context = app.applicationContext
        mode = runCatching { DnsMode.valueOf(app.getSharedPreferences("network", Context.MODE_PRIVATE)
            .getString("dns", DnsMode.AUTOMATIC.name)!!) }.getOrDefault(DnsMode.AUTOMATIC)
        // Coil uses its own OkHttp dispatcher on TV boxes so a large poster
        // grid cannot starve catalogue / playback connections.
        val pm = app.packageManager
        val isTvBox = AudioDecoderPolicy.preferFfmpeg(
            hasLeanback = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
            tvUiMode = (app.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
                ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION,
            hasTelevisionFeature = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION),
            hasTouchscreen = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        )
        val loader = coil.ImageLoader.Builder(app)
        if (isTvBox) {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 6
                maxRequestsPerHost = 3
            }
            val posterClient = client.newBuilder()
                .dispatcher(imageDispatcher)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()
            loader.okHttpClient(posterClient)
            loader.memoryCache {
                coil.memory.MemoryCache.Builder(app).maxSizePercent(0.10).build()
            }
        } else {
            // Keep the working phone image pipeline unchanged.
            loader.okHttpClient(client)
        }
        coil.Coil.setImageLoader(loader.build())
    }
    fun select(app: Context, selected: DnsMode) {
        mode = selected
        runCatching {
            app.applicationContext.getSharedPreferences("network", Context.MODE_PRIVATE)
                .edit().putString("dns", selected.name).apply()
        }
        // Keep active streams and activation requests alive. New connections use
        // the selected resolver; existing pooled connections remain undisturbed.
    }
    // Used when VPN routing changes. DNS selection itself leaves active requests untouched.
    fun resetConnections() { client.connectionPool.evictAll() }
    fun vpnActive(): Boolean {
        return try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) { false }
    }
    fun open(url: URL): HttpURLConnection = DnsHttpConnection(url, client)
}
