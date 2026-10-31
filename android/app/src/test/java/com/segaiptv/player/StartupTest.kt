package com.segaiptv.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w800dp-h360dp-land")
class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun synopsisDialogKeepsActivityOwnerAndDisplaysText() {
        compose.setContent {
            LanguageHost(onExit = {}) {
                SynopsisDialog("Movie", "A provider synopsis", {})
            }
        }
        compose.onNodeWithText("A provider synopsis").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun xmltvParserWorksOnAndroid() {
        val xml = """<tv><programme channel="news" start="20261021120000 +0000" stop="20261021130000 +0000"><title>News</title></programme></tv>"""
        org.junit.Assert.assertEquals("News", XmltvGuide.parse(xml.reader(), "news", 0).single().title)
    }

    @Before fun chooseEnglish() {
        compose.activity.getSharedPreferences("segaiptv_ui", Context.MODE_PRIVATE)
            .edit().putString("language", "en").commit()
    }

    @Test fun offlineStartupShowsIdentifiersQrAndLanguageDialogWithoutCrashing() {
        compose.setContent {
            LanguageHost(onExit = {}) {
                assertSame(compose.activity, LocalContext.current)
                SegaIptvApp(loadConfig = { throw java.io.IOException("Offline test") })
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Unable to connect"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unable to connect").assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code containing the virtual MAC and device key").assertIsDisplayed()
        compose.onNodeWithText("↻ Check activation").assertIsDisplayed()
        compose.onNodeWithText("Language: English").performClick()
        compose.onNodeWithText("Français").performClick()
        compose.onNodeWithText("Connexion impossible").assertIsDisplayed()
        compose.onNodeWithText("Langue: Français").assertIsDisplayed()
    }

    @Test fun viewingOptionsAndHistoryOpenWithoutCrashing() {
        compose.setContent {
            LanguageHost(onExit = {}) {
                androidx.compose.foundation.layout.Column {
                    ViewerSettings(emptyList(), "", "", false)
                    WatchHistory({}, emptyList())
                }
            }
        }
        compose.onNodeWithText("Viewing options").performClick()
        compose.onNodeWithText("Interface size").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Continue watching").performClick()
        compose.onNodeWithText("No viewing history yet").assertIsDisplayed()
    }

    @Test fun lockedPreviewDoesNotCreateAPlayer() {
        val store = ViewerStore.get(compose.activity)
        val channel = Channel("Locked", "https://invalid.test/live/1.ts", "Private", "", "Test")
        if (store.pinEnabled) { store.verify("1234"); store.clearLocks() }
        store.setPin("1234")
        store.verify("1234")
        store.lock(channel, true)
        var created = false
        compose.setContent {
            LanguageHost(onExit = {}) {
                MediaSurface(channel, onPlayer = { if (it != null) created = true })
            }
        }
        compose.onNodeWithText("Unlock with PIN").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(created) }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        store.verify("1234")
        store.clearLocks()
    }

    @Test fun activatedStartupReachesHomeAndExitCallback() {
        var exited = false
        compose.setContent {
            LanguageHost(onExit = { exited = true }) {
                assertSame(compose.activity, LocalContext.current)
                SegaIptvApp(loadConfig = { AppConfig(active = true) })
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("⏻ Exit"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("↻ Refresh").assertIsDisplayed()
        compose.onNodeWithText("⏻ Exit").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(exited) }
    }

    @Test fun explicitServerDenialReturnsActivatedAppToActivationScreen() {
        var calls = 0
        compose.setContent {
            LanguageHost(onExit = {}) {
                SegaIptvApp(loadConfig = {
                    if (calls++ == 0) AppConfig(active = true) else throw BackendAccessDenied()
                })
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("↻ Refresh"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("↻ Refresh").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("↻ Check activation"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("↻ Check activation").assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code containing the virtual MAC and device key").assertIsDisplayed()
    }
}

