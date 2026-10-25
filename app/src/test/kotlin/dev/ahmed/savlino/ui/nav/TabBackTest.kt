package dev.ahmed.savlino.ui.nav

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.NezzelApp
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
class TabBackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun everyPeerReturnsHomeAndHomeLeavesBackToTheActivity() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val links = LinkViewModel(SavedStateHandle())
        var defaultBacks = 0
        activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { defaultBacks++ }
        })
        try {
            activity.setContent {
                NezzelTheme(ThemePref.Light, false) {
                    NezzelApp(links, "en", ThemePref.Light, false, true, {}, {}, {}, {}, {}, {}, {})
                }
            }
            compose.waitForIdle()
            for (route in listOf("Downloads", "Statuses", "Settings")) {
                compose.runOnIdle { links.navigate(route) }
                compose.waitForIdle()
                compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals("Home", links.destination)
                assertEquals(0, defaultBacks)
            }
            // AppUpdate still goes back to its entry tab, rather than always going Home.
            compose.runOnIdle { links.navigate("Settings") }
            compose.waitForIdle()
            compose.runOnIdle { links.navigate("AppUpdate") }
            compose.waitForIdle()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertEquals("Settings", links.destination)
            compose.runOnIdle { links.navigate("Home") }
            compose.waitForIdle()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, defaultBacks)
        } finally { controller.pause().stop().destroy() }
    }
}
