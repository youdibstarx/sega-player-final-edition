package com.segaiptv.player

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class PosterRepositoryTest {
    private fun movie(id: Int, logo: String = "") = Channel("Movie $id", "https://provider.test/movie/u/p/$id.mp4",
        "Films", logo, "P", mediaType = "movie", sourceUrl = "https://provider.test/get.php?username=u&password=p", streamId = "$id")
    @Test fun artworkNormalizesSpacesEntitiesRelativeAndProtocolRelativeUrls() {
        assertEquals("https://images.test/a%20b.jpg?x=1&y=2", ArtworkUrls.resolve("", " https://images.test/a b.jpg?x=1&amp;y=2 "))
        assertEquals("https://images.test/a.jpg", ArtworkUrls.resolve("https://provider.test", "//images.test/a.jpg"))
        assertEquals("https://provider.test/posters/a.jpg", ArtworkUrls.resolve("https://provider.test", "/posters/a.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", ArtworkUrls.resolve("", "http://image.tmdb.org/t/p/w500/a.jpg"))
        assertEquals("", ArtworkUrls.resolve("https://provider.test", "null"))
        assertEquals("", ArtworkUrls.resolve("https://provider.test", "javascript:alert(1)"))
    }
    @Test fun catalogueFindsAlternateMovieAndSeriesCoverFields() {
        assertEquals("https://images.test/a.jpg", ArtworkUrls.fromJson("https://provider.test",
            JSONObject("""{"stream_icon":null,"movie_image":"https://images.test/a.jpg"}"""), false))
        assertEquals("https://images.test/b.jpg", ArtworkUrls.fromJson("https://provider.test",
            JSONObject("""{"cover":"","cover_big":"https://images.test/b.jpg"}"""), true))
        assertEquals("https://image.tmdb.org/t/p/w500/c.jpg", ArtworkUrls.fromJson("https://provider.test",
            JSONObject("""{"poster_path":"/c.jpg"}"""), true))
    }
    @Test fun missingPosterLoadsFromTitleDetailsAndReusesSavedMetadata() = runBlocking {
        val c = RuntimeEnvironment.getApplication()
        var requests = 0
        val first = PosterRepository.resolve(c, movie(841)) { url ->
            assertTrue(url.contains("action=get_vod_info")); assertTrue(url.contains("vod_id=841")); requests++
            """{"info":{"movie_image":"https://images.test/841.jpg"}}"""
        }
        assertEquals("https://images.test/841.jpg", first)
        assertEquals(first, PosterRepository.resolve(c, movie(841)) { error("Must reuse poster metadata") })
        assertEquals(1, requests)
    }
    @Test fun failedImageCanRecoverThroughDetailsButExistingValidPosterNeedsNoDetailsRequest() = runBlocking {
        val c = RuntimeEnvironment.getApplication()
        val e = movie(842, "https://images.test/old.jpg")
        assertEquals(e.logo, PosterRepository.resolve(c, e) { error("Existing poster needs no extra request") })
        assertEquals("https://images.test/new.jpg", PosterRepository.resolve(c, e, true) {
            """{"info":{"cover":"https://images.test/new.jpg"}}"""
        })
        assertEquals("https://images.test/new.jpg", PosterRepository.resolve(c, e) { error("Reuse recovered URL") })
    }
    @Test fun seriesUsesSeriesIdAndMetadataRequestsAreBounded() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val c = RuntimeEnvironment.getApplication()
        (0 until 8).map { n -> async(Dispatchers.Default) {
            val e = movie(900+n).copy(mediaType = "series", seriesId = "${900+n}")
            PosterRepository.resolve(c, e, true) { url ->
                assertTrue(url.contains("action=get_series_info")); assertTrue(url.contains("series_id="))
                val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it, current) }
                try { Thread.sleep(25); """{"info":{"cover":"https://images.test/series.jpg"}}""" }
                finally { active.decrementAndGet() }
            }
        } }.awaitAll()
        assertTrue(peak.get() in 1..3)
    }
}
