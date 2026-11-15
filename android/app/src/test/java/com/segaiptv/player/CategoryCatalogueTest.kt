package com.segaiptv.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogueTest {
    private val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=a%2Bb&password=p%26q&type=m3u_plus")
    @Test fun browsingCategoriesDoesNotDownloadStreams() = runBlocking {
        for (kind in listOf("live", "vod", "series")) {
            val calls = mutableListOf<String>()
            val result = CategoryCatalogue.categories(playlist, kind) { url ->
                calls += url
                """[{"category_id":"1231","category_name":"4K"}]"""
            }
            assertEquals(1, calls.size)
            assertTrue(calls.single().endsWith("action=get_${kind}_categories"))
            assertEquals("4K", result.single().name)
        }
    }
    @Test fun selectedCategoryIsAlwaysSentAndTypesArePreserved() = runBlocking {
        for (kind in listOf("live", "vod", "series")) {
            val calls = mutableListOf<String>()
            val category = ProviderCategory(playlist, kind, "1231", "4K")
            val result = CategoryCatalogue.entries(category) { url ->
                calls += url
                if (kind == "series") """[{"series_id":42,"name":"Show","category_id":"1231"}]"""
                else """[{"stream_id":42,"name":"Video","category_id":"1231","container_extension":"mkv"}]"""
            }
            assertEquals(1, calls.size)
            assertTrue(calls.single().endsWith("&category_id=1231"))
            assertTrue(calls.single().contains("username=a%2Bb&password=p%26q"))
            assertEquals("4K", result.single().category)
            assertEquals(if (kind == "vod") "movie" else kind, result.single().mediaType)
            if (kind == "vod") assertTrue(result.single().url.endsWith("/42.mkv"))
        }
    }
    @Test fun invalidCategoriesCannotTriggerUnfilteredRequests() {
        val result = CategoryCatalogue.parseCategories(playlist, "vod",
            """[{"category_id":"","category_name":"All"},{"category_id":"1&action=bad","category_name":"Bad"},{"category_id":7,"category_name":"Good"},{"category_id":7,"category_name":"Duplicate"}]""")
        assertEquals(listOf("7"), result.map { it.id })
    }
    @Test fun categoryCountsAcceptCommonProviderFieldsAndIgnoreInvalidValues() {
        val result = CategoryCatalogue.parseCategories(playlist, "live", """[
            {"category_id":"1","category_name":"News","streams_count":"24"},
            {"category_id":"2","category_name":"Sports","total_streams":18},
            {"category_id":"3","category_name":"Other","count":-1}
        ]""")
        assertEquals(listOf(24, 18, null), result.map { it.channelCount })
    }
    @Test fun providerFailureDoesNotFallBackToFullCatalogue() = runBlocking {
        var calls = 0
        try {
            CategoryCatalogue.entries(ProviderCategory(playlist, "vod", "2", "Movies")) {
                calls++; throw java.io.IOException("offline")
            }
            fail("Expected failure")
        } catch (_: java.io.IOException) { assertEquals(1, calls) }
    }
}

