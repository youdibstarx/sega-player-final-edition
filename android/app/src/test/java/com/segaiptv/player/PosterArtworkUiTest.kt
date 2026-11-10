package com.segaiptv.player

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class PosterArtworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun missingListArtworkLoadsDetailsThenDisplaysDownloadedPosterOnAndroid7() {
        MockWebServer().use { server ->
            val bytes = ByteArrayOutputStream().also {
                Bitmap.createBitmap(30, 45, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/player_api.php" -> MockResponse().setBody("""{"info":{"movie_image":"${server.url("/poster.png")}"}}""")
                    "/poster.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            compose.activity.getSharedPreferences("segaiptv_ui", android.content.Context.MODE_PRIVATE)
                .edit().putString("language", "en").commit()
            NetworkClient.initialize(compose.activity)
            val e = Channel("Poster test", server.url("/movie/u/p/77.mp4").toString(), "Films", "", "P",
                mediaType = "movie", sourceUrl = server.url("/get.php?username=u&password=p").toString(), streamId = "77")
            compose.setContent {
                LanguageHost(onExit = {}) { PosterArtwork(VodTitle(e.name, listOf(e)), Modifier.size(120.dp, 180.dp)) }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("Poster test").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("No cover available").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Poster test").assertIsDisplayed()
        }
    }
}
