package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class DeviceProofTest {
    @Test fun canonicalWireFormatIsStable() {
        assertEquals("SEGA-PROOF-1\nid\nGET\n/api/v1/config?device_id=id\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n100\nnonce",
            DeviceProof.canonical("id", "GET", "/api/v1/config?device_id=id", "", "100", "nonce").toString(Charsets.UTF_8))
    }
    @Test fun signatureBindsBodyAndDevice() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = DeviceProof.canonical("id", "POST", "/api/v1/register", "{}", "100", "nonce")
        val signed = Base64.getDecoder().decode(DeviceProof.sign(pair.private, data))
        fun checkSignature(bytes: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(bytes); this.verify(signed) }
        assertTrue(checkSignature(data))
        assertFalse(checkSignature(DeviceProof.canonical("other", "POST", "/api/v1/register", "{}", "100", "nonce")))
        assertFalse(checkSignature(DeviceProof.canonical("id", "POST", "/api/v1/register", "{\"active\":true}", "100", "nonce")))
    }
    @Test fun obfuscatedAddressDecodesToHttps() {
        assertEquals("https", java.net.URI(BackendAddress.value()).scheme)
        assertNotNull(java.net.URI(BackendAddress.value()).host)
    }
}
