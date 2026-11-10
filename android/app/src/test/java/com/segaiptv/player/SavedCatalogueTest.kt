package com.segaiptv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class SavedCatalogueTest {
    private val playlist = Playlist(991, "Saved library", "https://saved.test/list.m3u")
    private val rows = listOf("live", "movie", "series").mapIndexed { i, kind ->
        Channel("Item $i", "https://saved.test/$i", "Group $i", "", playlist.name,
            mediaType = kind, sourceUrl = playlist.url)
    }

    @Test fun reopeningUsesDiskCatalogueForAllSectionsWithoutNetworkEvenAfterFourteenDays() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        LibraryCache.write(context, playlist, rows)
        File(context.noBackupFilesDir, "catalogue_v1").listFiles().orEmpty().forEach {
            assertTrue(it.setLastModified(System.currentTimeMillis() - 30L * 86400_000))
        }
        val saved = LibraryCache.read(context, playlist)
        assertEquals(rows, saved)
        val result = refreshVisiblePlaylist(saved, download = { error("Opening must not download") },
            onPartial = { error("No partial updates on a cache hit") }, forceRefresh = false)
        assertEquals(rows, result.entries)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun refreshButtonFetchesAndReplacesSavedCatalogue() = runBlocking {
        var requests = 0
        val updated = rows.take(1)
        val result = refreshVisiblePlaylist(rows, download = { requests++; PlaylistLoad(updated) },
            onPartial = {}, forceRefresh = true)
        assertEquals(1, requests)
        assertEquals(updated, result.entries)
    }

    @Test fun missingOrChangedSourceIsDownloadedOnFirstOpen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        LibraryCache.write(context, playlist, rows)
        val different = playlist.copy(url = "https://different.test/list.m3u")
        val saved = LibraryCache.read(context, different)
        assertTrue(saved.isEmpty())
        var requests = 0
        val result = refreshVisiblePlaylist(saved, download = { requests++; PlaylistLoad(rows) },
            onPartial = {}, forceRefresh = false)
        assertEquals(1, requests)
        assertEquals(rows, result.entries)
        LibraryCache.prune(context, listOf(different))
        assertTrue(LibraryCache.read(context, playlist).isEmpty())
    }
}
