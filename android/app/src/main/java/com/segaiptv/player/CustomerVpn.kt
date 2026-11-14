package com.segaiptv.player

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Customer credentials never leave the device except to establish their own VPN tunnel. */
internal object CustomerVpn {
    val active = MutableStateFlow(false)
    private val mutex = Mutex()
    private var backend: GoBackend? = null
    private const val KEY = "sega_customer_wireguard_v1"
    private val tunnel = object : Tunnel {
        override fun getName() = "SEGA-Customer"
        override fun onStateChange(newState: Tunnel.State) {
            active.value = newState == Tunnel.State.UP
            NetworkClient.resetConnections()
        }
    }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "customer-vpn.enc"))
    fun hasProfile(context: Context) = file(context).baseFile.exists()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun importProfile(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!active.value)
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 65536); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: error("Profile unavailable")
            try {
                val parsed = Config.parse(bytes.inputStream())
                require(parsed.peers.isNotEmpty())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val encrypted = cipher.iv + cipher.doFinal(bytes)
                val target = file(context)
                val stream = target.startWrite()
                try { stream.write(encrypted); target.finishWrite(stream) }
                catch (e: Exception) { target.failWrite(stream); throw e }
            } finally { bytes.fill(0) }
        }
    }
    private fun read(context: Context): Config {
        val data = file(context).openRead().use { it.readBytes() }
        require(data.size in 29..70000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val plain = cipher.doFinal(data.copyOfRange(12, data.size))
        try {
            val parsed = Config.parse(plain.inputStream())
            // Scope to this app, regardless of application lists in the imported profile.
            val original = parsed.`interface`
            val builder = Interface.Builder().addAddresses(original.addresses)
                .addDnsServers(original.dnsServers).addDnsSearchDomains(original.dnsSearchDomains)
                .setKeyPair(original.keyPair).includeApplication(context.packageName)
            original.listenPort.ifPresent { builder.setListenPort(it) }
            original.mtu.ifPresent { builder.setMtu(it) }
            return Config.Builder().setInterface(builder.build()).addPeers(parsed.peers).build()
        } finally { plain.fill(0) }
    }
    suspend fun connect(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = backend ?: GoBackend(context.applicationContext).also { backend = it }
            engine.setState(tunnel, Tunnel.State.UP, read(context))
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { backend?.setState(tunnel, Tunnel.State.DOWN, null); active.value = false }
    }
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!active.value)
            file(context).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY) }
        }
    }
}
