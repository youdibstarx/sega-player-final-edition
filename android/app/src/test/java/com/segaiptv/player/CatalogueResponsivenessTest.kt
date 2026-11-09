package com.segaiptv.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class CatalogueResponsivenessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun catalogueWorkDoesNotBlockInputAndCancelledSnapshotCannotOverwriteNewerOne() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var generation by mutableIntStateOf(0)
        var clicks by mutableIntStateOf(0)
        try {
            compose.setContent {
                val requested = generation
                val result = rememberCatalogueWork(-1, requested) {
                    assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
                    if (requested == 0) {
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    requested
                }
                androidx.compose.foundation.layout.Column {
                    Text("snapshot:$result")
                    androidx.compose.material3.Button(onClick = { clicks++; generation++ }) { Text("Navigate") }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Navigate").performClick()
            compose.runOnIdle { assertEquals(1, clicks) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("snapshot:1").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            compose.waitForIdle()
            compose.onNodeWithText("snapshot:1").assertExists()
        } finally { release.countDown() }
    }

    @Test fun fourteenThousandRowsKeepProviderOrderAndTypeSeparation() = kotlinx.coroutines.runBlocking {
        val rows = (0 until 14366).map { n ->
            Channel("Entry $n", "https://provider.test/$n", if (n % 2 == 0) "First" else "Second",
                "", "Provider", mediaType = when (n % 3) { 0 -> "live"; 1 -> "movie"; else -> "series" })
        }
        for (kind in listOf("live", "vod", "series")) {
            val result = localCatalogueGroups(rows, kind, true)
            assertEquals(rows.filter { catalogueKind(it) == kind }.groupBy { it.category }, result)
            assertEquals(listOf("First", "Second").toSet(), result.keys.toSet())
        }
    }

    @Test fun snapshotKeyNeverWalksLargeLists() {
        val list = object : AbstractList<Channel>() {
            override val size = 150000
            override fun get(index: Int): Channel = error("UI key must not inspect rows")
        }
        assertEquals(CatalogueIdentity(list), CatalogueIdentity(list))
        assertNotEquals(CatalogueIdentity(list), CatalogueIdentity(emptyList<Channel>()))
        CatalogueIdentity(list).hashCode()
    }

    @Test fun remoteFocusSurvivesProgressiveCategoryUpdates() {
        compose.activity.getSharedPreferences("segaiptv_ui", android.content.Context.MODE_PRIVATE)
            .edit().putString("language", "en").commit()
        val rows = androidx.compose.runtime.mutableStateOf(listOf(
            Channel("Movie one", "https://provider.test/movie/1.mp4", "Group", "", "P", mediaType = "movie")))
        compose.setContent {
            LanguageHost(onExit = {}) {
                CategoryBrowser(androidx.compose.ui.Modifier, emptyList(), "movies", rows.value, true,
                    emptySet(), androidx.compose.ui.graphics.Color.Yellow, "", 0, {}, {}, {}, {}, m3uOnly = true)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Group (1)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Group (1)").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Group (1)").assertIsFocused()
        compose.runOnIdle { rows.value = rows.value + Channel("Movie two",
            "https://provider.test/movie/2.mp4", "Group", "", "P", mediaType = "movie") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Group (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Group (2)").assertIsFocused()
        compose.onNodeWithText("Group (2)").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Movie two").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Movie two").assertIsDisplayed()
    }
    @Test fun displayedCategoryAndRemoteFocusSurviveDownloadFailure() {
        compose.activity.getSharedPreferences("segaiptv_ui", android.content.Context.MODE_PRIVATE)
            .edit().putString("language", "en").commit()
        val rows = androidx.compose.runtime.mutableStateOf<List<Channel>>(emptyList())
        var finish by mutableIntStateOf(0)
        var finished by mutableIntStateOf(0)
        compose.setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val result = refreshVisiblePlaylist(emptyList(), download = { publish ->
                    publish(listOf(Channel("Test channel", "https://provider.test/live/1.ts", "Group", "", "P")))
                    androidx.compose.runtime.snapshotFlow { finish }.first { it == 1 }
                    throw java.io.IOException("Truncated response")
                }, onPartial = { rows.value = it })
                rows.value = result.entries
                finished = 1
            }
            LanguageHost(onExit = {}) {
                CategoryBrowser(androidx.compose.ui.Modifier, emptyList(), "live", rows.value, finished == 0,
                    emptySet(), androidx.compose.ui.graphics.Color.Yellow, "", 0, {}, {}, {}, {}, m3uOnly = true)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Group (1)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Group (1)").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Group (1)").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test channel").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { finish = 1 }
        compose.waitUntil(5000) { finished == 1 }
        compose.onNodeWithText("Group (1)").assertIsFocused()
        compose.onNodeWithText("Test channel").assertIsDisplayed()
    }
}
