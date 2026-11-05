package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.Reader

class ProviderOrderTest {
    @Test fun stopsReadingAfterAllRequestedGroupsWithoutChangingTheirOrder() {
        val prefix = "#EXTM3U\n#EXTINF:-1 group-title=Z,A\n#EXTINF:-1 group-title=Other,B\n#EXTINF:-1 group-title=A,C\n"
        val reader = object : Reader() {
            var supplied = false
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                check(!supplied) { "Should not read the remaining large catalogue" }
                supplied = true
                prefix.toCharArray().copyInto(buffer, offset)
                return prefix.length
            }
            override fun close() {}
        }
        val ranks = ProviderOrder.parse(reader, setOf("Z", "A"))
        assertEquals(listOf("Z", "Other", "A"), ranks.keys.toList())
    }
    @Test fun missingGroupRequiresEndOfFileAndRetainsKnownOrder() {
        val ranks = ProviderOrder.parse(StringReader("#EXTM3U\n#EXTINF:-1 group-title=Z,A\n#EXTINF:-1 group-title=A,B"), setOf("Z", "Missing"))
        assertEquals(listOf("Z", "A"), ranks.keys.toList())
        assertFalse(ranks.containsKey("Missing"))
    }
    private val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=a&password=b")
    @Test fun firstM3uOccurrenceWinsWithoutAlphabeticalOrIdSorting() {
        val ranks = ProviderOrder.parse(StringReader("""#EXTM3U
#EXTINF:-1 group-title="Zulu",Channel
http://provider.test/live/1.ts
#EXTINF:-1 group-title='Alpha',Channel
http://provider.test/live/2.ts
#EXTINF:-1 group-title="Zulu",Duplicate
http://provider.test/live/3.ts
#EXTGRP:Drama, World
http://provider.test/movie/4.mp4
"""))
        assertEquals(listOf("Zulu", "Alpha", "Drama, World"), ranks.keys.toList())
        for (kind in listOf("live", "vod", "series")) {
            val categories = listOf("Unknown B", "Alpha", "Unknown A", "Drama, World", "Zulu").mapIndexed { i, name -> ProviderCategory(playlist, kind, "$i", name) }
            assertEquals(listOf("Zulu", "Alpha", "Drama, World", "Unknown B", "Unknown A"), ProviderOrder.arrange(categories, ranks).map { it.name })
        }
    }
    @Test fun updatedServerOrderChangesAllTabs() {
        val old = ProviderOrder.parse(StringReader("#EXTM3U\n#EXTINF:-1 group-title=Z,A\n#EXTINF:-1 group-title=A,B"))
        val fresh = ProviderOrder.parse(StringReader("#EXTM3U\n#EXTINF:-1 group-title=A,B\n#EXTINF:-1 group-title=Z,A"))
        val categories = listOf(ProviderCategory(playlist,"live","1","A"), ProviderCategory(playlist,"live","2","Z"))
        assertEquals(listOf("Z","A"),ProviderOrder.arrange(categories,old).map { it.name })
        assertEquals(listOf("A","Z"),ProviderOrder.arrange(categories,fresh).map { it.name })
    }
    @Test fun oversizedLinesAndInvalidResponsesAreRejected() {
        for (text in listOf("<html>Unavailable</html>", "#EXTM3U\n" + "x".repeat(65537))) {
            try { ProviderOrder.parse(StringReader(text)); fail("Expected bounded rejection") } catch (_: IllegalArgumentException) { }
        }
    }
}

