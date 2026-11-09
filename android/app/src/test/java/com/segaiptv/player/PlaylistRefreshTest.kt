package com.segaiptv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaylistRefreshTest {
    private fun row(id: Int) = Channel("Channel $id", "https://provider.test/live/$id.ts", "Group", "", "P")

    @Test fun failureAfterFirstDisplayCannotEraseChannelsWithoutDiskCache() = runBlocking {
        var visible = emptyList<Channel>()
        val prefix = (1..30).map(::row)
        val result = refreshVisiblePlaylist(emptyList(), download = { publish ->
            publish(prefix)
            assertEquals(prefix, visible)
            throw IOException("stream interrupted after first display")
        }, onPartial = { visible = it })
        visible = result.entries
        assertEquals(prefix, visible)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun refreshKeepsCachedCategoriesUntilTheEntireResponseSucceeds() = runBlocking {
        val old = listOf(row(1), row(2).copy(category = "Later group"))
        val updated = row(1).copy(name = "Updated")
        val result = refreshVisiblePlaylist(old, download = { publish ->
            publish(listOf(updated))
            PlaylistLoad(listOf(updated))
        }, onPartial = {
            assertEquals(listOf(updated, old[1]), it)
        })
        // A complete nonempty response remains authoritative, including legitimate removals.
        assertEquals(listOf(updated), result.entries)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun failedRefreshRetainsBothFreshPrefixAndUnseenCachedRows() = runBlocking {
        val result = refreshVisiblePlaylist(listOf(row(2)), download = { publish ->
            publish(listOf(row(1)))
            error("Playlist too large")
        }, onPartial = {})
        assertEquals(listOf(row(1), row(2)), result.entries)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun emptyResponseDoesNotSilentlyEraseAnExistingLibrary() = runBlocking {
        val result = refreshVisiblePlaylist(listOf(row(1)), download = { PlaylistLoad(emptyList()) }, onPartial = {})
        assertEquals(listOf(row(1)), result.entries)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test fun cancellationIsNeverTreatedAsRecoverableDownloadFailure() = runBlocking {
        try {
            refreshVisiblePlaylist(listOf(row(1)), download = { throw CancellationException() }, onPartial = {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun realTruncatedHttpResponseDrainsThePrefixBeforeReportingFailure() = runBlocking {
        MockWebServer().use { server ->
            val body = buildString {
                append("#EXTM3U\n")
                for (i in 1..2000) append("#EXTINF:-1 group-title=\"Group\",Channel $i\nhttps://provider.test/live/$i.ts\n")
            }
            server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val playlist = Playlist(1, "P", server.url("/list.m3u").toString())
            var visible = emptyList<Channel>()
            val result = refreshVisiblePlaylist(emptyList(), download = { Api.downloadPlaylistProgressive(playlist, it) },
                onPartial = { visible = it })
            assertTrue("The prefix must reach the UI even when HTTP fails", visible.size >= 30)
            assertEquals(visible, result.entries)
            assertTrue(result.entries.size < 2000)
            assertTrue(result.warnings.isNotEmpty())
        }
    }
    @Test fun parserPublishesRowsSinceLastThrottledUpdateBeforeReadFailure() {
        var last = emptyList<Channel>()
        val lines = sequence {
            yield("#EXTM3U")
            for (i in 1..55) {
                yield("#EXTINF:-1,Channel $i")
                yield("https://provider.test/live/$i.ts")
            }
            throw IOException("Read interrupted")
        }
        try {
            M3uParser.parseLines(lines, "P", "", onPartial = { last = it })
            fail("Read failure must remain visible")
        } catch (_: IOException) {
            assertEquals(55, last.size)
            assertEquals("Channel 55", last.last().name)
        }
    }
}
