package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class AppAuthenticityTest {
    @Test fun ownerSignedSecurePackageIsAccepted() {
        assertEquals(64, AppAuthenticity.OWNER_SHA256.length)
        assertTrue(AppAuthenticity.matchesOwner(AppAuthenticity.PACKAGE, listOf(AppAuthenticity.OWNER_SHA256), false))
    }
    @Test fun renamedResignedUnsignedAndDebuggablePackagesAreRejected() {
        val owner = listOf(AppAuthenticity.OWNER_SHA256)
        assertFalse(AppAuthenticity.matchesOwner("com.other.player", owner, false))
        assertFalse(AppAuthenticity.matchesOwner(AppAuthenticity.PACKAGE, listOf("0".repeat(64)), false))
        assertFalse(AppAuthenticity.matchesOwner(AppAuthenticity.PACKAGE, emptyList(), false))
        assertFalse(AppAuthenticity.matchesOwner(AppAuthenticity.PACKAGE, owner + "0".repeat(64), false))
        assertFalse(AppAuthenticity.matchesOwner(AppAuthenticity.PACKAGE, owner, true))
    }
}
