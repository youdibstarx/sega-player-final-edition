package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class BackendEndpointsTest {
    private val hosts = listOf("https://segaplayer.site", "https://tchach.online")

    @Test fun retryTemporaryFailureThenUseBackupAndRecoverOnNextCall() {
        val seen = mutableListOf<String>()
        val result = BackendEndpoints.failover(hosts) {
            seen.add(it)
            if (it == hosts.first()) throw BackendTransportFailure(BackendTransportFailure.Reason.DNS)
            "ok"
        }
        assertEquals(hosts, seen)
        assertEquals(hosts.last() to "ok", result)
        assertEquals(hosts.first() to "ok", BackendEndpoints.failover(hosts) { "ok" })
    }

    @Test fun accessDenialAndClientErrorsNeverRetryAnotherHost() {
        for (failure in listOf(BackendAccessDenied(), BackendHttpError(429), BackendHttpError(404), BackendHttpError(302))) {
            val seen = mutableListOf<String>()
            val result = assertThrows(IllegalStateException::class.java) {
                BackendEndpoints.failover(hosts) { seen.add(it); throw failure }
            }
            assertSame(failure, result)
            assertEquals(listOf(hosts.first()), seen)
        }
    }

    @Test fun invalidOrCredentialBearingUrlsRejected() {
        for (value in listOf("http://segaplayer.site", "https://user:secret@segaplayer.site",
            "https://127.0.0.1", "https://segaplayer.site:443", "https://segaplayer.site/path",
            "https://segaplayer.site?x=1", "https://segaplayer.site#x")) {
            assertThrows(Exception::class.java) { BackendEndpoints.normalize(value) }
        }
        assertEquals(hosts.first(), BackendEndpoints.normalize(hosts.first() + "/"))
    }

    @Test fun configurationSignatureRejectsTamperingAndOtherKeys() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val key = generator.generateKeyPair()
        val bytes = "signed endpoint config".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(bytes); sign() }
        assertTrue(BackendEndpoints.verify(key.public.encoded, bytes, sig))
        assertFalse(BackendEndpoints.verify(key.public.encoded, "changed endpoint".toByteArray(), sig))
        assertFalse(BackendEndpoints.verify(generator.generateKeyPair().public.encoded, bytes, sig))
    }
}
