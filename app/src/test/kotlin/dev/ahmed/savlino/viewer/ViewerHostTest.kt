package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The viewer left its Dialog for the app window: it must still cover, isolate and dismiss exactly as the Dialog did. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class ViewerHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private val playback = dev.ahmed.savlino.playback.PlaybackEnvironment(activity,
        dev.ahmed.savlino.playback.PlayerFactory { _, _ -> dev.ahmed.savlino.playback.RecordingPlayer() }, sizeOf = { 10L })
    @After fun close() { controller.pause().stop().destroy() }

    private val items = (1..3).map { ViewerItem(Uri.parse("content://media/external/images/$it"), false, "image/jpeg") }
    private val pictures = MediaPictures { _, _, _ -> Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888) }
    private var open by mutableStateOf(false)
    private var route by mutableStateOf("Downloads")
    private var taps = 0
    private var belowFocused = false
    private var fieldFocused = false

    /** A tabbed app like Nezzel's: the viewer is opened from inside the Downloads tab. */
    private fun show() {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                ViewerHost {
                    TabContent(route) { tab ->
                        if (tab == "Downloads") {
                            Column(Modifier.fillMaxSize()) {
                                TextField("", {}, Modifier.onFocusChanged { fieldFocused = it.isFocused }.testTag("field"))
                                Button({ taps++ }, Modifier.fillMaxSize().onFocusChanged { belowFocused = it.isFocused }) { Text("Below") }
                            }
                            CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback) {
                                if (open) MediaViewer(items, 1, "en", { open = false }, onShare = {}, onOpenWith = {})
                            }
                        } else Text(tab)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun viewer() = compose.onNodeWithTag(VIEWER_TAG)
    private fun back() { compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun theViewerCoversTheAppWhichTakesNoTouchesOrTalkBackUntilItCloses() {
        show()
        compose.onNodeWithText("Below").assertExists()
        open = true; compose.waitForIdle()
        viewer().assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_image_n, "2", "3")).assertExists()
        compose.onNodeWithText("Below").assertDoesNotExist()
        viewer().performTouchInput { click(center); click(Offset(1f, 1f)); click(bottomLeft + Offset(1f, -1f)); click(centerRight - Offset(1f, 0f)) }
        assertEquals("A touch on the viewer, edges included, never reaches the screen below", 0, taps)
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_close)).performClick()
        compose.waitForIdle()
        assertFalse(open)
        viewer().assertDoesNotExist()
        compose.onNodeWithText("Below").performClick()
        assertEquals(1, taps)
    }

    /** P2-1: a hardware keyboard or D-pad stays inside the viewer, and a keyboard that was up for the page below goes. */
    @Test fun keysNeverMoveFocusIntoTheCoveredApp() {
        show()
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        assertTrue(fieldFocused)
        open = true; compose.waitForIdle()
        assertFalse("Opening the viewer takes focus (and the keyboard) away from the page below", fieldFocused)
        repeat(12) {
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
            assertFalse("Focus never reaches the field below", fieldFocused)
            assertFalse("Focus never reaches the button below", belowFocused)
        }
        back()
        compose.onNodeWithText("Below").assertExists()
    }

    /** P2-2: one viewer at a time. A second screen's viewer closes the first through that screen's own close. */
    @Test fun aViewerOpenedElsewhereClosesTheFirstSoNoScreenStaysOpenBehindIt() {
        var home by mutableStateOf(false)
        var downloads by mutableStateOf(false)
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                ViewerHost {
                    TabContent(route) { tab ->
                        CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback) {
                            Text(tab)
                            if (tab == "Home" && home) MediaViewer(items, 0, "en", { home = false }, onShare = {}, onOpenWith = {})
                            if (tab == "Downloads" && downloads) MediaViewer(items, 2, "en", { downloads = false }, onShare = {}, onOpenWith = {})
                        }
                    }
                }
            }
        }
        route = "Home"; compose.waitForIdle()
        home = true; compose.waitForIdle()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_image_n, "1", "3")).assertExists()
        // An OPEN_SAVED intent: switch to Downloads, which opens its own viewer.
        route = "Downloads"; compose.waitForIdle()
        downloads = true; compose.waitForIdle()
        assertFalse("The superseded Home viewer was closed by its own screen", home)
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_image_n, "3", "3")).assertExists()
        back()
        assertFalse(downloads)
        viewer().assertDoesNotExist()
        route = "Home"; compose.waitForIdle()
        viewer().assertDoesNotExist()
        compose.onNodeWithText("Home").assertExists()
    }

    @Test fun backClosesTheViewerOnly() {
        show()
        open = true; compose.waitForIdle()
        back()
        assertFalse(open)
        viewer().assertDoesNotExist()
        compose.onNodeWithText("Below").assertExists()
    }

    /** Parity with the Dialog: a new intent that switches tabs keeps the viewer up, and Back still reaches it. */
    @Test fun aTabSwitchUnderneathKeepsTheViewerAndBackStillClosesIt() {
        show()
        open = true; compose.waitForIdle()
        route = "Home"; compose.waitForIdle()
        viewer().assertIsDisplayed()
        back()
        assertFalse("The hidden tab's detached Back must not swallow it", open)
        viewer().assertDoesNotExist()
        compose.onNodeWithText("Home").assertExists()
    }

    @Test fun aScreenThatLeavesTakesItsViewerAlong() {
        show()
        open = true; compose.waitForIdle()
        activity.setContent { NezzelTheme(ThemePref.Light, false) { ViewerHost { Text("Elsewhere") } } }
        compose.waitForIdle()
        viewer().assertDoesNotExist()
        compose.onNodeWithText("Elsewhere").assertExists()
    }

    @Test fun darkPageGivesLightBarIconsAndClosingPutsTheAppsBack() {
        show()
        val bars = { WindowCompat.getInsetsController(activity.window, activity.window.decorView) }
        compose.runOnIdle { bars().isAppearanceLightStatusBars = true; bars().isAppearanceLightNavigationBars = true }
        open = true; compose.waitForIdle()
        assertFalse(bars().isAppearanceLightStatusBars)
        assertFalse(bars().isAppearanceLightNavigationBars)
        back()
        assertTrue(bars().isAppearanceLightStatusBars)
        assertTrue(bars().isAppearanceLightNavigationBars)
    }

    @Suppress("DEPRECATION")
    @Config(sdk = [29])
    @Test fun aClosedViewerNeverLeavesTheAppsBarsHidden() {
        show()
        open = true; compose.waitForIdle()
        // Fullscreen hides the bars on the shared window; a close from there must show them again.
        compose.runOnIdle { WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        assertNotEquals(0, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
        back()
        assertEquals(0, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
    }
}
