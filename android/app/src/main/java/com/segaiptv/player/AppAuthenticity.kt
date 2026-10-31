package com.segaiptv.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Local tamper deterrent only. A patched client can remove this check; it is not server attestation. */
internal object AppAuthenticity {
    internal const val PACKAGE = "com.segaiptv.player.v3.secure"
    internal const val OWNER_SHA256 = "d25ec500c54c69192ed12bd160ea87ecfbed146fb8ba728cac2b9a95397263b4"

    internal fun matchesOwner(packageName: String, currentSigners: List<String>, debuggable: Boolean): Boolean =
        packageName == PACKAGE && !debuggable && currentSigners.size == 1 && currentSigners.single() == OWNER_SHA256

    @Suppress("DEPRECATION")
    fun isTrusted(context: Context): Boolean = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        // Check the actual current signer, not an old certificate in signing history.
        val digests = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        matchesOwner(context.packageName, digests,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }.getOrDefault(false)
}
