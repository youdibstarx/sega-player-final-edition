package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallPermissionTest {
    @Test fun android7NeverCallsTheAndroid8PermissionApi() {
        for (sdk in listOf(24, 25)) {
            assertFalse(needsUpdateInstallPermission(sdk) {
                throw NoSuchMethodError("canRequestPackageInstalls requires API 26")
            })
        }
    }

    @Test fun android8AndLaterRequireThePerAppPermission() {
        for (sdk in listOf(26, 28, 35)) {
            assertTrue(needsUpdateInstallPermission(sdk) { false })
            assertFalse(needsUpdateInstallPermission(sdk) { true })
        }
    }
}
