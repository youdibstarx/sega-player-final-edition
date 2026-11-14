package com.segaiptv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class CategoryDiskCacheTest {
    private fun playlist(suffix: String) = Playlist(1, "P", "https://provider.test/$suffix/get.php?username=test&password=test")
    @Test fun categoriesReopenFromDiskAndManualRefreshUpdatesNames() = runBlocking {
        val c = RuntimeEnvironment.getApplication()
        val p = playlist("categories")
        val first = listOf(ProviderCategory(p, "vod", "1", "Films"), ProviderCategory(p, "vod", "2", "Documentaries"))
        assertEquals(first, CategoryDiskCache.categories(c, p, "vod", 0) { first })
        assertEquals(first, CategoryDiskCache.categories(c, p, "vod", 0) { error("Must use saved categories") })
        val updated = first.take(1).map { it.copy(name = "New films") }
        assertEquals(updated, CategoryDiskCache.categories(c, p, "vod", 1) { updated })
        assertEquals(updated, CategoryDiskCache.categories(c, p, "vod", 1) { error("Refresh token already loaded") })
    }
    @Test fun eachSelectedCategoryHasItsOwnPersistentEntriesAndPreservesSourceIdentity() = runBlocking {
        val c = RuntimeEnvironment.getApplication()
        val p = playlist("entries")
        val a = ProviderCategory(p, "live", "1", "Sports")
        val b = a.copy(id = "2", name = "News")
        val rows = listOf(Channel("Sports channel", "https://provider.test/live/1.ts", a.name, "", p.name, sourceUrl = p.url))
        assertEquals(rows, CategoryDiskCache.entries(c, a, 0) { rows })
        assertEquals(rows, CategoryDiskCache.entries(c, a, 0) { error("Must use selected category cache") })
        assertTrue(CategoryDiskCache.entries(c, b, 0) { emptyList() }.isEmpty())
        LibraryCache.prune(c, listOf(p))
        assertEquals(rows, CategoryDiskCache.entries(c, a, 0) { error("Prune must retain assigned category cache") })
        assertEquals(rows, CategoryDiskCache.entries(c, a, 2) { throw java.io.IOException() })
    }
    @Test fun extendedMetadataAfterTitleAndSmartQuotesKeepGroupsAndArtwork() {
        val rows = M3uParser.parse("""#EXTM3U
#EXTINF:-1,First group-title="Sports" tvg-logo="https://images.test/1.png"
https://provider.test/live/1.ts
#EXTINF:-1 group-title=“News” tvg-logo=“https://images.test/2.png”,Second
https://provider.test/live/2.ts
""", "P", "")
        assertEquals(listOf("Sports", "News"), rows.map { it.category })
        assertEquals(listOf("First", "Second"), rows.map { it.name })
        assertEquals(listOf("https://images.test/1.png", "https://images.test/2.png"), rows.map { it.logo })
    }
}
