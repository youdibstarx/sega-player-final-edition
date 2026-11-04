package com.segaiptv.player

/** HTTPS server time advanced using monotonic elapsed time, never the device wall clock. */
internal class ServerClock(private val elapsed: () -> Long) {
    private var serverSeconds: Long? = null
    private var synchronizedAt = 0L
    @Synchronized fun synchronize(seconds: Long) {
        require(seconds > 0 && seconds < 253402300799L)
        serverSeconds = seconds
        synchronizedAt = elapsed()
    }
    @Synchronized fun seconds(): Long {
        val base = checkNotNull(serverSeconds) { "Server clock has not been synchronized" }
        val delta = elapsed() - synchronizedAt
        check(delta >= 0) { "Server clock requires synchronization" }
        return base + delta / 1000
    }
}
