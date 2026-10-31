package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class BackendTransportTest {
    private class Fake(val status: Int, val body: String) : HttpsURLConnection(URL("https://panel.test/api/v1/config")) {
        var closed = false
        var errorRead = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream { errorRead = true; return ByteArrayInputStream(body.toByteArray()) }
    }
    @Test fun rejectsCleartextAndCredentialBearingBackendBeforeConnecting() {
        for (base in listOf("http://panel.test", "https://user:secret@panel.test", "https://panel.test?password=secret")) {
            var opened = false
            assertThrows(IllegalArgumentException::class.java) {
                BackendTransport.request(base, "/api/v1/config", "GET", deviceKey = "ABC123") {
                    opened = true; Fake(200, "{}")
                }
            }
            assertFalse(opened)
        }
        assertEquals("https://panel.test/sub/api/v1/config?device_id=abc",
            BackendTransport.endpoint("https://panel.test/sub/", "/api/v1/config?device_id=abc").toString())
    }
    @Test fun redirectsCannotForwardCredentials() {
        val con = Fake(302, "https://attacker.test/?device_key=secret")
        assertThrows(IllegalStateException::class.java) {
            BackendTransport.request("https://panel.test", "/api/v1/config", "GET", deviceKey = "ABC123") { con }
        }
        assertFalse(con.instanceFollowRedirects)
        assertFalse(con.errorRead)
        assertTrue(con.closed)
    }
    @Test fun successfulResponsePreservesProtocolAndDisablesCache() {
        val con = Fake(200, """{"active":true,"playlists":[]}""")
        val result = BackendTransport.request("https://panel.test", "/api/v1/config", "GET", deviceKey = "ABC123") { con }
        assertTrue(result.getBoolean("active"))
        assertEquals("ABC123", con.getRequestProperty("X-Device-Key"))
        assertFalse(con.useCaches)
        assertTrue(con.closed)
    }
    @Test fun errorsNeverContainResponseBodyOrOriginalException() {
        val con = Fake(403, "password=secret")
        val error = assertThrows(IllegalStateException::class.java) {
            BackendTransport.request("https://panel.test", "/api/v1/config", "GET") { con }
        }
        assertTrue(error is BackendAccessDenied)
        assertEquals("Backend access denied", error.message)
        assertNull(error.cause)
        assertFalse(con.errorRead)
        val tls = assertThrows(IllegalStateException::class.java) {
            BackendTransport.request("https://panel.test", "/api/v1/config", "GET") {
                throw javax.net.ssl.SSLHandshakeException("private provider URL")
            }
        }
        assertEquals("Backend TLS verification failed", tls.message)
        assertNull(tls.cause)
    }
    @Test fun oversizedResponsesAreStopped() {
        val input = ByteArrayInputStream(ByteArray(BackendTransport.MAX_RESPONSE_BYTES + 1))
        assertThrows(IllegalStateException::class.java) { BackendTransport.readBounded(input) }
    }
    @Test fun onlyAuthenticationDenialsInvalidateAccess() {
        for (status in listOf(401, 403, 429, 500, 503)) {
            val con = Fake(status, "private server response")
            val error = assertThrows(IllegalStateException::class.java) {
                BackendTransport.request("https://panel.test", "/api/v1/config", "GET") { con }
            }
            assertEquals(status == 401 || status == 403, error is BackendAccessDenied)
            assertNull(error.cause)
            assertFalse(con.errorRead)
            assertTrue(con.closed)
        }
    }
}

