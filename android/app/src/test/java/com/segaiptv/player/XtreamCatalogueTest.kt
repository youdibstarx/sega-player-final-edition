package com.segaiptv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class XtreamCatalogueTest {
    private val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=demo&password=example&type=m3u_plus&output=ts")
    private val source = XtreamSource.from(playlist.url)!!

    @Test fun readsTypesCoversAndCategoriesFromSeparateEndpoints() {
        val movies = XtreamCatalogue.parseSection(source, playlist, "vod",
            """[{"stream_id":17,"name":"A movie","stream_icon":"/art/poster.jpg","category_id":"9","container_extension":"mkv"}]""",
            """[{"category_id":"9","category_name":"Drama"}]""")
        assertEquals("https://provider.test/art/poster.jpg", movies.single().logo)
        assertEquals("Drama", movies.single().category)
        assertEquals("movie", movies.single().mediaType)
        assertEquals("https://provider.test/movie/demo/example/17.mkv", movies.single().url)
        val shows = XtreamCatalogue.parseSection(source, playlist, "series",
            """[{"series_id":5,"name":"A show","cover":"https://art.test/show.jpg","category_id":"7","plot":"Synopsis"}]""", "[]")
        assertEquals("5", shows.single().seriesId)
        assertEquals("https://art.test/show.jpg", shows.single().logo)
        assertEquals("Synopsis", shows.single().description)
        assertTrue(shows.single().url.startsWith("xtream-series:"))
    }

    @Test fun episodesAreReadAndOrderedFromServerSeasonBuckets() {
        val show = Channel("Show", "xtream-series:test:5", "Drama", "https://art.test/show.jpg", "Test",
            mediaType = "series", sourceUrl = playlist.url, seriesId = "5")
        val episodes = XtreamCatalogue.parseEpisodes(source, show,
            """{"episodes":{"2":[{"id":"23","title":"Finale","season":2,"episode_num":3,"container_extension":"mkv"}],
            "1":[{"id":"11","title":"Pilot","episode_num":1,"container_extension":"mp4","info":{"movie_image":"/art/ep.jpg"}}]}}""")
        assertEquals(listOf(1, 2), episodes.map { it.season })
        assertEquals(listOf(1, 3), episodes.map { it.episode })
        assertEquals("https://provider.test/series/demo/example/11.mp4", episodes.first().url)
        assertEquals("https://provider.test/art/ep.jpg", episodes.first().logo)
        assertEquals(show.logo, episodes.last().logo)
    }

    @Test fun loadFetchesLibraryWithoutFetchingEveryShowsEpisodes() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val live = Channel("Sports", "https://provider.test/demo/example/7.ts", "SERIE A", "", "Test")
        val result = XtreamCatalogue.load(playlist, listOf(live), false) { url ->
            val action = url.substringAfter("&action=")
            calls += action
            when (action) {
                "get_live_streams" -> """[{"stream_id":7,"name":"Sports","category_id":"1"}]"""
                "get_vod_streams" -> """[{"stream_id":8,"name":"Movie","stream_icon":"https://art.test/movie.jpg"}]"""
                "get_series" -> """[{"series_id":9,"name":"Show","cover":"https://art.test/show.jpg"}]"""
                else -> "[]"
            }
        }
        assertEquals(3, result.entries.size)
        assertEquals(live.url, result.entries.first { it.mediaType == "live" }.url)
        assertEquals(1, buildVodTitles(result.entries, true).size)
        assertEquals(1, buildVodTitles(result.entries, false).size)
        assertFalse(calls.any { it.startsWith("get_series_info") })
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun partialFailurePreservesM3uAndReportsMissingSectionWithoutCredentials() = runBlocking {
        val live = Channel("PEACOCK SERIES", "https://provider.test/7.ts", "CINE & SERIES", "", "Test")
        val result = XtreamCatalogue.load(playlist, listOf(live), false) { url ->
            if (url.endsWith("get_series")) """[{"series_id":1,"name":"Show"}]"""
            else throw IllegalStateException("https://provider.test?password=example")
        }
        assertEquals(live.copy(sourceUrl = playlist.url), result.entries.first { it.url == live.url })
        assertEquals(1, buildVodTitles(result.entries, true).size)
        assertEquals(listOf("live M3U fallback", "vod catalogue unavailable"), result.warnings)
        assertEquals("", catalogueWarnings(result.warnings.joinToString("\n"), "live"))
        assertEquals("vod catalogue unavailable", catalogueWarnings(result.warnings.joinToString("\n"), "vod"))
        assertFalse(result.warnings.joinToString().contains("password"))
    }

    @Test fun successfulFallbackNeverAppearsAsPlaybackError() = runBlocking {
        val movie = Channel("Film", "https://provider.test/movie/demo/example/42.mp4", "Films", "", "Test")
        val result = XtreamCatalogue.load(playlist, listOf(movie), false) { url ->
            if (url.endsWith("get_vod_streams")) error("Unavailable") else "[]"
        }
        assertEquals(listOf("vod M3U fallback"), result.warnings)
        assertEquals(movie.url, result.entries.single().url)
        assertEquals("", catalogueWarnings("Test: " + result.warnings.single(), "vod"))
        assertEquals("Broken: download unavailable", catalogueWarnings("Broken: download unavailable", "live"))
        assertEquals("", catalogueWarnings("Test: series catalogue unavailable", "live"))
    }

    @Test fun encodedCredentialsAndUnsupportedUrls() {
        val escaped = XtreamSource.from("https://provider.test/sub/get.php?username=a%2Bb&amp;password=p%26q&type=m3u")!!
        assertEquals("https://provider.test/sub/movie/a%2Bb/p%26q/1.mp4", escaped.stream("movie", "1", "mp4"))
        assertTrue(escaped.api("get_series").contains("username=a%2Bb&password=p%26q"))
        assertNull(XtreamSource.from("https://provider.test/file.m3u?username=a&password=b"))
    }
}
