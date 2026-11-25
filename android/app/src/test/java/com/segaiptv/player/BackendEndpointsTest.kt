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

    @Test fun manualRecoveryRequiresValidatedInternetAndStartupTransportFailure() {
        val timeout = BackendTransportFailure(BackendTransportFailure.Reason.TIMEOUT)
        assertTrue(BackendEndpoints.canOfferRecovery(true, true, timeout))
        assertFalse(BackendEndpoints.canOfferRecovery(true, false, timeout))
        assertFalse(BackendEndpoints.canOfferRecovery(false, true, timeout))
        assertFalse(BackendEndpoints.canOfferRecovery(true, true, BackendAccessDenied()))
        assertFalse(BackendEndpoints.canOfferRecovery(true, true, BackendHttpError(429)))
    }

    @Test fun manualAddressRequiresHttpsAndSignedMembershipWithoutRollback() {
        assertEquals("https://segaplayer.site", BackendEndpoints.manualUrl(" segaplayer.site "))
        assertThrows(Exception::class.java) { BackendEndpoints.manualUrl("http://segaplayer.site") }
        val replacement = listOf("https://new.example.com", hosts.last())
        assertTrue(BackendEndpoints.acceptsManualHost(replacement.first(), 3, replacement, 2, hosts))
        assertFalse(BackendEndpoints.acceptsManualHost(replacement.first(), 1, replacement, 2, hosts))
        assertFalse(BackendEndpoints.acceptsManualHost(replacement.first(), 2, replacement, 2, hosts))
        assertFalse(BackendEndpoints.acceptsManualHost("https://other.example.com", 3, replacement, 2, hosts))
        assertTrue(BackendEndpoints.acceptsManualHost(hosts.first(), 2, hosts, 2, hosts))
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
