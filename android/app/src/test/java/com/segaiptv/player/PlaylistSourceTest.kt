package com.segaiptv.player

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlaylistSourceTest {
    private val source = "https://provider.example/get.php?username=test&password=a%26b%2Bc&type=m3u&output=ts"
    private val plus = source.replace("type=m3u&", "type=m3u_plus&")
    private val basic = listOf(Channel("News", "https://provider.example/live/1.ts", "غير مصنف", "", "Test"))

    @Test fun upgradesOnlyPlaylistTypeAndPreservesTsAndEncodedCredentials() {
        assertEquals(plus, PlaylistSource.extendedUrl(source))
        assertEquals(plus, PlaylistSource.extendedUrl(source.replace("&", "&amp;")))
        assertEquals(source, PlaylistSource.normalize(source.replace("&", "&#38;")))
    }

    @Test fun doesNotRewriteOtherEndpointsOrAlreadyExtendedPlaylists() {
        assertNull(PlaylistSource.extendedUrl(source.replace("get.php", "signed.m3u")))
        assertNull(PlaylistSource.extendedUrl(plus))
        val unchanged = "https://provider.example/list.m3u?token=abc%26type%3Dm3u"
        assertEquals(unchanged, PlaylistSource.normalize(unchanged))
        assertNull(PlaylistSource.extendedUrl(unchanged))
    }

    @Test fun extendedResponseRestoresGroupsAndLogosWithoutFetchingBasicAgain() {
        val calls = mutableListOf<String>()
        val loaded = PlaylistSource.load(source) { requested ->
            calls += requested
            M3uParser.parse("""
                #EXTM3U
                #EXTINF:-1 group-title="Sports, HD" tvg-logo="https://img.example/logo.png",Sports One
                https://provider.example/live/1.ts
                #EXTINF:-1 group-title="News",News One
                https://provider.example/live/2.ts
            """.trimIndent(), "Test", requested)
        }
        assertEquals(listOf(plus), calls)
        assertEquals(listOf("Sports, HD", "News"), loaded.map { it.category })
        assertEquals("Sports One", loaded.first().name)
        assertEquals("https://img.example/logo.png", loaded.first().logo)
    }

    @Test fun rejectionOrEmptyResponseFallsBackToOriginal() {
        for (reject in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val loaded = PlaylistSource.load(source) { requested ->
                calls += requested
                if (requested == plus) {
                    if (reject) throw IOException("Unsupported format")
                    emptyList()
                } else basic
            }
            assertEquals(basic, loaded)
            assertEquals(listOf(plus, source), calls)
        }
    }

    @Test fun cancellationIsNotRetriedAsAProviderFailure() {
        var calls = 0
        try {
            PlaylistSource.load(source) { calls++; throw CancellationException("Cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test fun extgrpBeforeAndAfterExtinfAreBothRecognized() {
        val loaded = M3uParser.parse("""
            #EXTM3U
            #EXTGRP:Sports
            #EXTINF:-1,One
            https://provider.example/1.ts
            #EXTINF:-1,Two
            https://provider.example/2.ts
            #EXTINF:-1,Three
            #EXTGRP:News
            https://provider.example/3.ts
        """.trimIndent(), "Test", source)
        assertEquals(listOf("Sports", "Sports", "News"), loaded.map { it.category })
    }

    @Test fun unavailableMetadataRemainsUncategorizedRatherThanInvented() {
        assertEquals(basic, PlaylistSource.load(source) { basic })
    }
}
