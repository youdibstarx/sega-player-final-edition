package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    @Test fun graceExpiresAtBoundaryAndRejectsClockReset() {
        assertEquals(AccessGrace.DURATION_MS, AccessGrace.remaining(100L, 100L))
        assertEquals(1L, AccessGrace.remaining(100L, 100L + AccessGrace.DURATION_MS - 1))
        assertEquals(0L, AccessGrace.remaining(100L, 100L + AccessGrace.DURATION_MS))
        assertEquals(0L, AccessGrace.remaining(100L, 99L))
    }
    @Test fun signingClockUsesElapsedTimeAndResynchronizes() {
        var elapsed = 100L
        val clock = ServerClock { elapsed }
        assertThrows(IllegalStateException::class.java) { clock.seconds() }
        clock.synchronize(1_800_000_000L)
        elapsed += 61_000L
        assertEquals(1_800_000_061L, clock.seconds())
        clock.synchronize(1_800_000_070L)
        assertEquals(1_800_000_070L, clock.seconds())
        assertThrows(IllegalArgumentException::class.java) { clock.synchronize(0) }
    }
}
