package com.segaiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSelectionTest {
    private val first = Playlist(7, "First", "https://example.test/first.m3u")
    private val second = Playlist(11, "Second", "https://example.test/second.m3u")

    @Test fun keepsSavedPlaylistWhenServerChangesListOrder() {
        assertEquals(second, choosePlaylist(listOf(first, second), 11))
    }

    @Test fun selectsFirstAvailablePlaylistWhenSavedChoiceWasRemoved() {
        assertEquals(first, choosePlaylist(listOf(first, second), 99))
    }

    @Test fun followsLastPlayedSourceWhenNoPlaylistChoiceWasSavedYet() {
        assertEquals(second, choosePlaylist(listOf(first, second), 0, second.url))
    }

    @Test fun returnsNoSelectionWhenNoPlaylistsAreAssigned() {
        assertNull(choosePlaylist(emptyList(), 11))
    }

    @Test fun resolvesThePlaylistForTheLastLiveChannelSource() {
        assertEquals(second, playlistForSource(listOf(first, second), second.url))
        assertNull(playlistForSource(listOf(first, second), "https://example.test/removed.m3u"))
    }
}

class AppThemeSelectionTest {
    @Test fun resolvesRubyThemeFromPanelConfig() {
        assertEquals(AppTheme.RUBY, AppTheme.fromId("ruby"))
    }

    @Test fun unsupportedThemeIdsFallBackToClassic() {
        assertEquals(AppTheme.CLASSIC, AppTheme.fromId("unknown"))
    }
}
