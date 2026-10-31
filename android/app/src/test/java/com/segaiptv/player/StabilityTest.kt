package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class StabilityTest {
    private val playlists = listOf(Playlist(1, "Test", "https://provider.test/list.m3u"))
    @Test fun activationPollingDoesNotReloadAnUnchangedCatalogue() {
        assertTrue(catalogueNeedsRefresh(-1, 0, null, playlists))
        repeat(20) { assertFalse(catalogueNeedsRefresh(0, 0, playlists, playlists)) }
        assertTrue(catalogueNeedsRefresh(0, 1, playlists, playlists))
        assertTrue(catalogueNeedsRefresh(1, 1, playlists, emptyList()))
        assertTrue(catalogueNeedsRefresh(1, 1, null, playlists))
    }
    @Test fun crashDiagnosticsExcludeMessagesAndUrls() {
        val error = IllegalStateException("https://provider.test?password=secret",
            NullPointerException("device key ABC123"))
        error.stackTrace = arrayOf(StackTraceElement("com.segaiptv.player.PlaybackKt", "render", "Playback.kt", 42))
        val summary = CrashDiagnostics.summary(error)
        assertTrue(summary.contains("IllegalStateException"))
        assertTrue(summary.contains("PlaybackKt#render:42"))
        assertTrue(summary.contains("NullPointerException"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("ABC123"))
        assertFalse(summary.contains("https://"))
    }
}
