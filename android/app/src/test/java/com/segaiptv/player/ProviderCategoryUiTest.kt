package com.segaiptv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class ProviderCategoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun largeProviderOpensRealCategoriesAndFetchesOnlyChosenCategoryWithoutFullM3u() {
        MockWebServer().use { server ->
            val requests = Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val u = request.requestUrl!!
                    val action = u.queryParameter("action").orEmpty()
                    requests += u.encodedPath + ":" + action + ":" + u.queryParameter("category_id")
                    return when {
                        action == "get_vod_categories" -> MockResponse().setBody("""[{"category_id":"7","category_name":"Provider films"},{"category_id":"8","category_name":"Documentaries"}]""")
                        action == "get_vod_streams" && u.queryParameter("category_id") == "7" -> MockResponse().setBody("""[{"stream_id":123,"name":"Selected movie","category_id":"7","container_extension":"mp4"}]""")
                        action == "get_vod_info" -> MockResponse().setBody("""{"info":{}}""")
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            val p = Playlist(41, "P", server.url("/get.php?username=test&password=test&type=m3u_plus").toString())
            compose.activity.getSharedPreferences("segaiptv_ui", android.content.Context.MODE_PRIVATE)
                .edit().putString("language", "en").commit()
            compose.setContent {
                LanguageHost(onExit = {}) {
                    CategoryBrowser(androidx.compose.ui.Modifier, listOf(p), "movies", emptyList(), false,
                        emptySet(), androidx.compose.ui.graphics.Color.Yellow, "", 0, {}, {}, {}, {})
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Provider films (—)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Documentaries (—)").assertExists()
            compose.onNodeWithText("Provider films (—)").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Selected movie").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Selected movie").assertIsDisplayed()
            compose.onNodeWithText("Provider films (1)").assertExists()
            val recorded = synchronized(requests) { requests.toList() }
            assertEquals(listOf("/player_api.php:get_vod_categories:null", "/player_api.php:get_vod_streams:7"),
                recorded.filterNot { it.contains(":get_vod_info:") })
            assertTrue(recorded.all { it.startsWith("/player_api.php:") })
        }
    }
}

