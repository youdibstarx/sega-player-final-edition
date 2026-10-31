package com.segaiptv.player

import android.content.Context

/** Stores only exception types and app frame locations, never exception messages or stream URLs. */
internal object CrashDiagnostics {
    @Volatile private var installed = false
    internal fun summary(error: Throwable): String {
        val parts = mutableListOf<String>()
        var cause: Throwable? = error
        repeat(3) {
            val current = cause ?: return@repeat
            parts += current.javaClass.simpleName
            parts += current.stackTrace.filter { it.className.startsWith("com.segaiptv.player.") }
                .take(4).map { it.className.substringAfterLast('.') + "#" + it.methodName + ":" + it.lineNumber }
            cause = current.cause?.takeUnless { it === current }
        }
        return parts.joinToString("\n").take(1600)
    }
    fun install(context: Context) {
        if (installed) return
        installed = true
        val prefs = context.getSharedPreferences("sega_crash_diagnostics", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { prefs.edit().putString("last", summary(error)).commit() } catch (_: Throwable) { }
            // Preserve Android's normal crash handling; never pretend a failed process is healthy.
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
    fun last(context: Context): String = context.getSharedPreferences("sega_crash_diagnostics", Context.MODE_PRIVATE)
        .getString("last", "").orEmpty()
}
