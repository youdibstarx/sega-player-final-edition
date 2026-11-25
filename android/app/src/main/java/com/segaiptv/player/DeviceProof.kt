package com.segaiptv.player

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.util.Base64
import java.util.UUID

/** Proof of possession of an approved installation key, NOT app attestation. */
internal object DeviceProof {
    private val clock = ServerClock { android.os.SystemClock.elapsedRealtime() }
    fun synchronize(seconds: Long) = clock.synchronize(seconds)
    private const val ALIAS = "sega_v3_request_signing_v1"
    internal fun canonical(id: String, method: String, path: String, body: String, time: String, nonce: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return listOf("SEGA-PROOF-1", id, method, path, digest, time, nonce).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
    internal fun sign(key: PrivateKey, data: ByteArray): String = Base64.encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }, Base64.NO_WRAP)

    fun fingerprint(): String = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val encoded = ks.getCertificate(ALIAS)?.publicKey?.encoded ?: return "—"
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrDefault("—")

    @Synchronized
    fun headers(id: String, method: String, path: String, body: String?): Map<String, String> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val time = clock.seconds().toString()
        val nonce = UUID.randomUUID().toString()
        val key = ks.getKey(ALIAS, null) as PrivateKey
        return mapOf("X-Proof-Time" to time, "X-Proof-Nonce" to nonce,
            "X-Proof-Public-Key" to Base64.encodeToString(ks.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP),
            "X-Proof-Signature" to sign(key, canonical(id, method, path, body.orEmpty(), time, nonce)))
    }
}

internal object BackendAddress {
    @Volatile private var active: String? = null
    fun value(): String = active ?: builtIn()
    fun select(url: String) { active = url }
    fun builtIn(): String = BuildConfig.BACKEND_BYTES.mapIndexed { i, v ->
        (v xor ((i * 31 + 93) and 255)).toByte()
    }.toByteArray().toString(Charsets.UTF_8)
}
