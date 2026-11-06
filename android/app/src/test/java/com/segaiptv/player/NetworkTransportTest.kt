package com.segaiptv.player

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class NetworkTransportTest {
    @Test fun sharedTransportUsesSelectedResolverAndPreservesPostAndHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val lookups = mutableListOf<String>()
            val client = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    lookups += hostname; return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).build()
            val con = DnsHttpConnection(URL("http://provider.test:${server.port}/register"),client)
            try {
                con.requestMethod = "POST"; con.doOutput = true
                con.setRequestProperty("Content-Type","application/json")
                con.setRequestProperty("X-Test","proof")
                con.outputStream.use { it.write("{}".toByteArray()) }
                assertEquals(200,con.responseCode)
                assertEquals("{\"ok\":true}",con.inputStream.bufferedReader().readText())
                assertEquals(listOf("provider.test"),lookups)
                val req = server.takeRequest()
                assertEquals("POST",req.method); assertEquals("{}",req.body.readUtf8())
                assertEquals("proof",req.getHeader("X-Test"))
            } finally { con.disconnect() }
        }
    }
    @Test fun sensitiveRequestsDoNotFollowRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            val con = DnsHttpConnection(server.url("/private").toUrl(),OkHttpClient())
            try {
                con.instanceFollowRedirects = false
                con.setRequestProperty("X-Device-Key","private")
                assertEquals(302,con.responseCode)
                assertEquals(1,server.requestCount)
            } finally { con.disconnect() }
        }
    }
}
