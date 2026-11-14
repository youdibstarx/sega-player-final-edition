package com.segaiptv.player

/**
 * Bounded retries for intermittent poster requests on older TV boxes.
 * The first failed request always gets one metadata lookup, because the
 * catalogue artwork URL may be stale. Only retry an unchanged URL for
 * likely transient failures; don't repeatedly hammer 4xx providers.
 */
internal object PosterRetryPolicy {
    const val MAX_RETRIES = 2

    fun delayMillis(retry: Int): Long = if (retry <= 1) 600L else 1500L

    fun retryAllowed(retriesStarted: Int, error: Throwable): Boolean {
        if (retriesStarted >= MAX_RETRIES) return false
        if (retriesStarted == 0) return true // Attempt to recover a replacement URL.

        var current: Throwable? = error
        repeat(6) {
            val reason = current ?: return@repeat
            if (reason is javax.net.ssl.SSLHandshakeException ||
                reason is javax.net.ssl.SSLPeerUnverifiedException ||
                reason is IllegalArgumentException
            ) return false
            // Coil 2 reports status in its HttpException message; do not log
            // the message because some providers embed credential-bearing URLs.
            val text = reason.message.orEmpty()
            if (Regex("""(?i)\b(?:HTTP\s*|code\s*[=:]\s*)(401|403|404|410)\b""")
                    .containsMatchIn(text)) return false
            current = reason.cause
        }
        return true
    }

    fun failureCategory(error: Throwable): String {
        var current: Throwable? = error
        repeat(6) {
            val reason = current ?: return@repeat
            val text = reason.message.orEmpty()
            val code = Regex("""(?i)\b(?:HTTP\s*|code\s*[=:]\s*)([1-5]\d{2})\b""")
                .find(text)?.groupValues?.get(1)
            if (code != null) return "HTTP_$code"
            if (reason is javax.net.ssl.SSLException) return "TLS"
            if (reason is java.net.SocketTimeoutException) return "TIMEOUT"
            current = reason.cause
        }
        return error.javaClass.simpleName.take(40)
    }
}
