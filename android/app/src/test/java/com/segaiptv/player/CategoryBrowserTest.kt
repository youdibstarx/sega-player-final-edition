package com.segaiptv.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w800dp-h360dp-land")
class CategoryBrowserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun returningFromFullscreenKeepsCategoryEntriesWithoutNetworkReload() {
        compose.activity.getSharedPreferences("segaiptv_ui", Context.MODE_PRIVATE).edit().putString("language", "en").commit()
        val fullscreen = androidx.compose.runtime.mutableStateOf(false)
        val memory = CategoryMemory()
        val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=demo&password=test")
        val movie = Channel("Sample movie", "https://provider.test/movie/1.mp4", "Drama", "", "Test", mediaType = "movie")
        var categoryCalls = 0
        var entryCalls = 0
        compose.setContent {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            LanguageHost(onExit = {}) {
                if (!fullscreen.value) holder.SaveableStateProvider("movies") {
                    CategoryBrowser(Modifier, listOf(playlist), "movies", emptyList(), false,
                        emptySet(), Color.Yellow, "", 0, {}, {}, {}, {},
                        loadCategories = { p, k -> categoryCalls++; listOf(ProviderCategory(p,k,"7","Drama")) },
                        loadEntries = { entryCalls++; listOf(movie) }, memory = memory)
                }
            }
        }
        compose.onNodeWithText("Drama").performClick()
        compose.onNodeWithText("Sample movie").assertIsDisplayed()
        compose.runOnIdle { fullscreen.value = true }
        compose.waitForIdle()
        compose.runOnIdle { fullscreen.value = false }
        compose.onNodeWithText("Sample movie").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1,categoryCalls); assertEquals(1,entryCalls) }
    }
    @Test fun favoriteMetadataSurvivesReloadButRemovedSourcesAreExcluded() = kotlinx.coroutines.runBlocking {
        val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=demo&password=test")
        val channel = Channel("Movie", "https://provider.test/movie/demo/test/1.mkv", "Drama", "", "Test", mediaType = "movie", sourceUrl = playlist.url)
        FavoriteCatalogue.write(compose.activity, listOf(channel))
        assertEquals(listOf(channel), FavoriteCatalogue.read(compose.activity, setOf(channel.url), listOf(playlist)))
        assertTrue(FavoriteCatalogue.read(compose.activity, setOf(channel.url), emptyList()).isEmpty())
        assertTrue(FavoriteCatalogue.read(compose.activity, emptySet(), listOf(playlist)).isEmpty())
    }
    @Test fun selectingAndRetryingOnlyLoadsTheSelectedCategory() {
        compose.activity.getSharedPreferences("segaiptv_ui", Context.MODE_PRIVATE)
            .edit().putString("language", "en").commit()
        val playlist = Playlist(1, "Test", "https://provider.test/get.php?username=demo&password=test")
        val calls = mutableListOf<String>()
        compose.setContent {
            LanguageHost(onExit = {}) {
                CategoryBrowser(Modifier, listOf(playlist), "movies", emptyList(), false,
                    emptySet(), Color.Yellow, "", 0, {}, {}, {}, {},
                    loadCategories = { p, k -> listOf(ProviderCategory(p, k, "7", "Drama"), ProviderCategory(p, k, "8", "Comedy")) },
                    loadEntries = { c ->
                        calls += c.id
                        if (calls.size == 1) throw java.io.IOException("offline")
                        emptyList()
                    })
            }
        }
        compose.onNodeWithText("Drama").assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Drama").performClick()
        compose.onNodeWithText("This category could not be loaded. Check the connection and retry.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("7"), calls) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(listOf("7", "7"), calls) }
        compose.onNodeWithText("‹ Categories").performClick()
        compose.onNodeWithText("Comedy").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls.size) }
    }
}
