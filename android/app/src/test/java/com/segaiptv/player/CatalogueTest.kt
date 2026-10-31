package com.segaiptv.player

import org.junit.Assert.*
import org.junit.Test

class CatalogueTest {
    @Test fun seriesTakesPrecedenceOverVodGroup() {
        val item = Channel("Show S02E03", "https://provider/series/u/p/1.mp4", "VOD", "", "Test")
        assertTrue(isVodEntry(item, true))
        assertFalse(isVodEntry(item, false))
    }
    @Test fun liveIsNotMisclassifiedByQueryCredentials() {
        val item = Channel("News", "https://provider/live/1.ts?username=movie", "News", "", "Test")
        assertFalse(isVodEntry(item, false))
        assertFalse(isVodEntry(item, true))
    }
    @Test fun episodeLabelsPreserveUnknownTitles() {
        assertEquals(EpisodeLabel("Show", 2, 3), episodeLabel("Show S02E03 HD"))
        assertEquals(EpisodeLabel("Show", 1, 12), episodeLabel("Show 1x12"))
        assertEquals(EpisodeLabel("News 2026", null, null), episodeLabel("News 2026"))
    }
    @Test fun m3uResolvesRelativeStreamsAndSingleQuotedAttributes() {
        val parsed = M3uParser.parse("#EXTM3U\n#EXTINF:-1 group-title='Films' tvg-logo='https://img/poster.jpg',Film\nmovie/1.mp4", "Test", "https://provider/list.m3u")
        assertEquals(1, parsed.size)
        assertEquals("https://provider/movie/1.mp4", parsed.single().url)
        assertEquals("Films", parsed.single().category)
    }

    @Test fun episodesInMovieGroupsBecomeSeries() {
        val episodes = listOf("Show S02E03", "Show S01E02", "Show S01E01").mapIndexed { n, name ->
            Channel(name, "https://provider/movie/$n.mp4", "VOD", "", "Test")
        }
        assertTrue(buildVodTitles(episodes, false).isEmpty())
        val show = buildVodTitles(episodes, true).single()
        assertEquals("Show", show.title)
        assertEquals(listOf(1, 2, 3), show.entries.map { episodeLabel(it).episode })
    }
    @Test fun explicitLiveRemainsLive() {
        val item = Channel("Show S01E01", "https://provider/live/1.ts", "Drama", "", "Test")
        assertFalse(isVodEntry(item, true))
        assertFalse(isVodEntry(item, false))
    }
    @Test fun extendedMetadataAndRelativePosterAreReadWithoutLeakingToNextEntry() {
        val text = """#EXTM3U
#EXTINF:-1 group-title="VOD" poster="images/cover.jpg" series-title="My Show" season="2" episode="4" plot="A story",Episode four
episodes/4.mp4
#EXTINF:-1 group-title="Movies",Movie
movies/5.mp4"""
        val items = M3uParser.parse(text, "Test", "https://provider/list.m3u")
        assertEquals("https://provider/images/cover.jpg", items[0].logo)
        assertEquals(EpisodeLabel("My Show", 2, 4), episodeLabel(items[0]))
        assertEquals("A story", items[0].description)
        assertEquals("", items[1].seriesTitle)
        assertNull(items[1].episode)
        assertEquals("", items[1].logo)
        assertEquals(1, buildVodTitles(items, false).size)
        assertEquals(1, buildVodTitles(items, true).size)
    }
    @Test fun localizedEpisodeNamesAndPlainVideoFiles() {
        assertEquals(EpisodeLabel("Show", 2, 3), episodeLabel("Show Saison 2 Episode 3"))
        assertEquals(EpisodeLabel("Show", 1, 8), episodeLabel("Show S01.E08"))
        assertTrue(isVodEntry(Channel("Film", "https://provider/123.mkv", "Other", "", "Test"), false))
        assertFalse(isVodEntry(Channel("News", "https://movie.example/live.ts", "News", "", "Test"), false))
    }

    @Test fun bouquetNamesNeverClassifyLiveStreams() {
        for (group in listOf("FR DAZN SERIE A", "IT DAZN SERIE B", "ES CINE & SERIES", "DE NETFLIX SERIES", "Movies")) {
            for (url in listOf("https://provider/u/p/123", "https://provider/u/p/123.ts", "https://provider/123.m3u8")) {
                val channel = Channel("US PEACOCK SERIES 12 HD", url, group, "", "Test")
                assertFalse(isVodEntry(channel, true))
                assertFalse(isVodEntry(channel, false))
            }
        }
    }
    @Test fun episodeShapedLiveProgrammeTitleIsNotVod() {
        val item = Channel("Show S01E02", "https://provider/123.ts", "Films", "", "Test")
        assertFalse(isVodEntry(item, true))
        assertFalse(isVodEntry(item, false))
    }
    @Test fun authoritativeTypeWinsOverPathAndName() {
        assertTrue(isVodEntry(Channel("Film", "https://provider/123.ts", "Other", "", "Test", mediaType = "movie"), false))
        assertFalse(isVodEntry(Channel("Show S01E02", "https://provider/123.mp4", "Movies", "", "Test", mediaType = "live"), true))
    }
}
