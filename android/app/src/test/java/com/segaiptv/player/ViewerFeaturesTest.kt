package com.segaiptv.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerFeaturesTest {
    private lateinit var context: Context
    private lateinit var store: ViewerStore
    private val movie = Channel("Movie", "https://provider.test/movie/demo/example/1.mp4", "Films", "", "Test",
        sourceUrl = "https://provider.test/get.php?username=demo&password=example")
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sega_viewer_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = ViewerStore(context)
    }
    @Test fun resumeSurvivesStoreRecreationAndCompletedItemsAreRemoved() {
        store.record(movie, 90_000, 600_000)
        assertEquals(90_000L, ViewerStore(context).position(movie))
        store.record(movie, 590_000, 600_000)
        assertTrue(store.history(true).isEmpty())
        assertEquals(0L, resumablePosition(5000, 600_000))
    }
    @Test fun recentChannelsAreDistinctAndBounded() {
        repeat(40) { store.record(movie.copy(url = "https://provider.test/live/demo/example/$it.ts", mediaType = "live")) }
        val latest = movie.copy(url = "https://provider.test/live/demo/example/39.ts", mediaType = "live")
        store.record(latest)
        assertEquals(30, store.history(false).size)
        assertEquals(latest.url, store.history(false).first().channel.url)
        assertTrue(store.history(true).isEmpty())
    }
    @Test fun pinProtectsItemsAndBouquetsAndRelocksAfterBackground() {
        store.setPin("1234")
        assertFalse(store.verify("9999"))
        assertTrue(store.verify("1234"))
        store.lock(movie, true)
        assertTrue(store.blocked(movie))
        assertTrue(store.blocked(movie.copy(url = "https://provider.test/movie/demo/example/2.mp4")))
        assertFalse(store.blocked(movie.copy(playlist = "Other provider")))
        assertTrue(ViewerStore(context).blocked(movie))
        assertTrue(store.verify("1234"))
        assertFalse(store.blocked(movie))
        store.relock()
        assertTrue(store.blocked(movie))
        assertThrows(IllegalStateException::class.java) { store.clearLocks() }
        assertTrue(store.verify("1234"))
        store.clearLocks()
        assertFalse(store.blocked(movie))
    }
    @Test fun pinAttemptsAreRateLimited() {
        store.setPin("1234")
        repeat(5) { assertFalse(store.verify("0000")) }
        assertFalse(store.verify("1234"))
    }
    @Test fun nextEpisodeRespectsShowProviderAndSeason() {
        val current = movie.copy(mediaType = "episode", seriesTitle = "Show", season = 1, episode = 2, seriesId = "9")
        val next = current.copy(url = "https://provider.test/series/demo/example/3.mp4", season = 2, episode = 1)
        assertEquals(next, nextEpisode(current, listOf(
            next.copy(sourceUrl = "https://other.test/list.m3u", season = 1, episode = 3),
            next.copy(seriesId = "10", season = 1, episode = 3), next)))
        assertNull(nextEpisode(current.copy(episode = null, name = "Unknown"), listOf(next)))
    }
    @Test fun cacheOnlyRestoresFailedSections() {
        val live = movie.copy(url = "https://provider.test/live/1.ts", mediaType = "live")
        val restored = mergeCachedCatalogue(PlaylistLoad(listOf(live), listOf("vod catalogue unavailable")), listOf(movie))
        assertEquals(listOf(live, movie), restored.entries)
        assertEquals(listOf("saved catalogue; refresh unavailable"), restored.warnings)
        assertTrue(mergeCachedCatalogue(PlaylistLoad(emptyList()), listOf(movie)).entries.isEmpty())
    }

    @Test fun cacheRoundTripsMetadataAndPrunesRemovedSources() = runBlocking {
        val playlist = Playlist(1, "Test", movie.sourceUrl)
        val item = movie.copy(season = 2, episode = 3, epgId = "id", epgUrl = "https://provider.test/epg.xml",
            description = "A story", seriesId = "9", streamId = "42")
        LibraryCache.write(context, playlist, listOf(item))
        assertEquals(listOf(item), LibraryCache.read(context, playlist))
        LibraryCache.prune(context, emptyList())
        assertTrue(LibraryCache.read(context, playlist).isEmpty())
    }
}
