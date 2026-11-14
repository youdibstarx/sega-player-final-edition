package com.segaiptv.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class LiveFocusRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningFromFullscreenFocusesSelectedChannelInsteadOfHome() {
        ViewerStore.get(compose.activity).clearHistory()
        val channels = (1..5).map { Channel("Channel $it", "http://127.0.0.1:9/$it.ts", "Live", "", "Test") }
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            var fullscreen by remember { mutableStateOf(false) }
            LanguageHost(onExit = {}) {
                if (fullscreen) TvAction("Back from fullscreen") { fullscreen = false }
                else holder.SaveableStateProvider("channels") {
                    val home = remember { FocusRequester() }
                    LaunchedEffect(Unit) { home.requestFocus() }
                    Column {
                        TvAction("Accueil", Modifier.focusRequester(home)) {}
                        LiveTvV3(Modifier.weight(1f), channels, false, emptySet(), "",
                            onPlay = { fullscreen = true }, onToggleFavorite = {}, showCategories = false)
                    }
                }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Channel 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channel 2").performClick()
        compose.onNodeWithText("Channel 2").performClick()
        compose.onNodeWithText("Back from fullscreen").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Channel 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Channel 2").assertIsFocused()
    }
}
