package com.segaiptv.player

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class UpdateFileCommitTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun verifiedDownloadReplacesPreviousCachedApk() {
        val cached = files.newFile("update.apk").apply { writeText("previous") }
        val verified = files.newFile("incoming.apk").apply { writeText("verified-new-apk") }
        commitVerifiedUpdate(verified, cached)
        assertEquals("verified-new-apk", cached.readText())
        assertFalse(verified.exists())
    }

    @Test fun failedCommitKeepsPreviousCachedApk() {
        val cached = files.newFile("update.apk").apply { writeText("previous") }
        val missing = File(files.root, "missing.apk")
        assertThrows(IllegalStateException::class.java) { commitVerifiedUpdate(missing, cached) }
        assertEquals("previous", cached.readText())
    }
}
