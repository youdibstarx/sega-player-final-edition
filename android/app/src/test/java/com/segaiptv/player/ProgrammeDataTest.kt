package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProgrammeDataTest {
    @Test fun detailsFetchWorksEvenWhenCatalogueWasUnavailable() = kotlinx.coroutines.runBlocking {
        val item = Channel("Film", "https://provider.test/movie/demo/example/42.mp4", "", "", "",
            sourceUrl = "https://provider.test/get.php?username=demo&password=example")
        var request = ""
        val result = ProgrammeData.details(item, true) { url ->
            request = url
            """{"info":{"plot":"Provider synopsis"}}"""
        }
        assertTrue(request.endsWith("&action=get_vod_info&vod_id=42"))
        assertEquals("Provider synopsis", result.synopsis)
        val episode = item.copy(url = "https://provider.test/series/demo/example/42.mp4")
        val fallback = ProgrammeData.details(episode, true) { error("Must not guess a series ID") }
        assertEquals("", fallback.synopsis)
    }

    @Test fun metadataAndPlaintextDescriptions() {
        assertEquals("A story\n& more", ProgrammeData.parseSynopsis("""{"info":{"plot":"<b>A story</b><br>&amp; more"}}"""))
        assertEquals("Fallback", ProgrammeData.parseSynopsis("""{"info":{"plot":null},"movie_data":{"plot":"Fallback"}}"""))
        assertEquals("News tonight", decodeEpgText("TmV3cyB0b25pZ2h0"))
        assertEquals("News tonight", decodeEpgText("News tonight"))
        assertEquals("", ProgrammeData.parseSynopsis("""{"info":{}}"""))
    }

    @Test fun streamCredentialsAndIdsAreResolvedWithoutInventingSeriesIds() {
        val movie = Channel("Film", "https://provider.test/sub/movie/a%2Bb/p%26q/42.mkv", "", "", "")
        val source = XtreamSource.forEntry(movie)!!
        assertEquals("42", streamId(movie))
        assertTrue(source.api("get_vod_info", "42", "vod_id").endsWith("&action=get_vod_info&vod_id=42"))
        assertTrue(source.api("get_vod_info").contains("username=a%2Bb&password=p%26q"))
        assertEquals("", movie.seriesId)
        assertNull(XtreamSource.forEntry(movie.copy(url = "https://provider.test/file.mkv")))
    }

    @Test fun m3uRetainsGuideSourceAndChannelIdentifier() {
        val entries = M3uParser.parse("""
            #EXTM3U x-tvg-url="guide.xml.gz"
            #EXTINF:-1 tvg-id="news.example" tvg-logo="logo.png",News
            live/1.ts
            #EXTINF:-1,Other
            live/2.ts
        """.trimIndent(), "Test", "https://provider.test/list.m3u")
        assertEquals("news.example", entries[0].epgId)
        assertEquals("https://provider.test/guide.xml.gz", entries[0].epgUrl)
        assertEquals("", entries[1].epgId)
        assertEquals("https://provider.test/list.m3u", entries[1].sourceUrl)
    }

    @Test fun xtreamGuideFiltersExpiredEventsAndDecodesText() {
        val entries = ProgrammeData.parseEpg("""{"epg_listings":[
            {"title":"Old","start_timestamp":"100","stop_timestamp":"200"},
            {"title":"TmV3cw==","description":"U3Rvcnk=","start_timestamp":"300","stop_timestamp":"500"},
            {"title":"Bad","start_timestamp":"600","stop_timestamp":"550"}
        ]}""", 400_000)
        assertEquals(1, entries.size)
        assertEquals("News", entries[0].title)
        assertEquals("Story", entries[0].description)
        assertEquals(300_000L, entries[0].start)
    }

    @Test fun xmltvMatchesIdsAndHonoursOffsetsAndExternalDoctype() {
        val guide = """<?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM "https://invalid.test/xmltv.dtd">
            <tv>
              <programme channel="other" start="20261021140000 +0200" stop="20261021150000 +0200"><title>Wrong</title></programme>
              <programme channel="news.example" start="20261021140000 +0200" stop="20261021150000 +0200"><title>News &amp; weather</title><desc>A description</desc></programme>
            </tv>"""
        val now = Instant.parse("2026-10-21T12:30:00Z").toEpochMilli()
        val result = XmltvGuide.parse(guide.reader(), "news.example", now).single()
        assertEquals("News & weather", result.title)
        assertEquals("A description", result.description)
        assertEquals(Instant.parse("2026-10-21T12:00:00Z").toEpochMilli(), result.start)
        assertTrue(XmltvGuide.parse(guide.reader(), "missing", now).isEmpty())
    }

    @Test fun xmltvRejectsInternalEntityExpansion() {
        val xml = """<!DOCTYPE tv [<!ENTITY payload "expanded">]><tv><programme channel="one" start="20261021120000 +0000" stop="20261021130000 +0000"><title>&payload;</title></programme></tv>"""
        assertThrows(Exception::class.java) { XmltvGuide.parse(xml.reader(), "one", 0) }
    }
}
