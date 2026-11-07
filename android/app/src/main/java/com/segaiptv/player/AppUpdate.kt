package com.segaiptv.player

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private data class Release(val code: Int, val name: String, val hash: String)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

private fun <T> fetchReleaseFile(name: String, read: (InputStream) -> T): T {
    val url = URL(BackendAddress.value().trimEnd('/') + "/sega-updates/" + name)
    val connection = NetworkClient.open(url) as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        check(connection.responseCode == 200)
        return connection.inputStream.use(read)
    } finally { connection.disconnect() }
}

private fun latestRelease(): Release {
    val data = fetchReleaseFile("latest.json") { JSONObject(BackendTransport.readBounded(it)) }
    return Release(data.getInt("versionCode"), data.getString("versionName"), data.getString("sha256")).also {
        require(it.code > 0 && it.name.matches(Regex("[0-9]+([.][0-9]+){1,3}")) && it.hash.matches(Regex("[a-f0-9]{64}")))
    }
}

@Suppress("DEPRECATION")
private suspend fun downloadVerified(context: Context, release: Release): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "apk-updates").apply { mkdirs() }
    val apk = File(directory, "update.apk")
    val temp = File.createTempFile("incoming", ".apk", directory)
    val job = currentCoroutineContext()
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        fetchReleaseFile("sega-${release.code}.apk") { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    job.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 128L * 1024 * 1024)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        check(digest.digest().hex() == release.hash)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(temp.path, flags))
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }
        check(info.versionCode == release.code && release.code > BuildConfig.VERSION_CODE)
        check(AppAuthenticity.matchesOwner(info.packageName, signers,
            requireNotNull(info.applicationInfo).flags and ApplicationInfo.FLAG_DEBUGGABLE != 0))
        java.nio.file.Files.move(temp.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        apk
    } finally { temp.delete() }
}

@Composable
internal fun rememberUpdatePrompt(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var message by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val check: (Boolean) -> Unit = { manual ->
        if (!busy) {
            busy = true
            release = null
            shown = manual
            message = R.string.update_checking
            scope.launch {
                try {
                    release = withContext(Dispatchers.IO) { latestRelease() }.takeIf { it.code > BuildConfig.VERSION_CODE }
                    message = if (release == null) R.string.update_current else R.string.update_available
                    shown = manual || release != null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = R.string.update_failed }
                finally { busy = false }
            }
        }
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (BuildConfig.ENFORCE_OWNER_SIGNATURE && now - prefs.getLong("last_check", 0) !in 0L until 3_600_000L) {
            prefs.edit().putLong("last_check", now).apply()
            check(false)
        }
    }
    if (shown) AlertDialog(onDismissRequest = { shown = false }, title = { Text(tr(R.string.update_group)) },
        text = { Text(tr(message) + (release?.let { " · " + it.name } ?: "")) },
        confirmButton = {
            val candidate = release
            if (candidate != null) TextButton(enabled = !busy, onClick = {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    message = R.string.update_permission
                    try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))) }
                    catch (_: Exception) { message = R.string.update_permission }
                } else {
                    busy = true
                    message = R.string.update_downloading
                    scope.launch {
                        try {
                            val apk = downloadVerified(context, candidate)
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            shown = false
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = R.string.update_failed }
                        finally { busy = false }
                    }
                }
            }) { Text(tr(R.string.update_install)) }
        }, dismissButton = { TextButton(onClick = { shown = false }) { Text(tr(R.string.close)) } })
    return { check(true) }
}

