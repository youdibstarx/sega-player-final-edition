package com.segaiptv.player

/** In-memory only: a cold start always requires successful server verification. */
internal object AccessGrace {
    const val DURATION_MS = 24 * 60 * 60 * 1000L
    fun remaining(verifiedAt: Long, now: Long): Long =
        if (now < verifiedAt) 0L else (DURATION_MS - (now - verifiedAt)).coerceAtLeast(0L)
}
