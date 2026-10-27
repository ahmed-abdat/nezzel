package dev.ahmed.savlino.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
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
class SharedAxisTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var activity: ComponentActivity
    private var deep by mutableStateOf(false)
    private val shown = mutableStateOf(true)

    @Test fun aDeeperPageComesInFromTheEndAndTheOldOneLeavesTowardTheStartMirroredInRtl() {
        for (rtl in listOf(false, true)) {
            deep = false
            pages(rtl).use {
                val end = if (rtl) -1 else 1
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread { deep = true; Snapshot.sendApplyNotifications() }
                repeat(6) { compose.mainClock.advanceTimeByFrame() }
                val incoming = left("page-true") * end
                val outgoing = left("page-false") * end
                assertTrue("rtl=$rtl incoming at $incoming", incoming > 0f && incoming < 30f)
                assertTrue("rtl=$rtl outgoing at $outgoing", outgoing < 0f && outgoing > -30f)
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.onNodeWithTag("page-false").assertDoesNotExist()
                assertEquals(0f, left("page-true"))

                // Back reverses the axis: the upper page returns from the start.
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                repeat(6) { compose.mainClock.advanceTimeByFrame() }
                assertFalse(deep)
                assertTrue("rtl=$rtl back", left("page-false") * end < 0f && left("page-true") * end > 0f)
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.onNodeWithTag("page-true").assertDoesNotExist()
            }
        }
    }

    @Test fun reducedMotionSwapsPagesAtOnce() {
        pages(reduced = true).use {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { deep = true; Snapshot.sendApplyNotifications() }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("page-false").assertDoesNotExist()
            assertEquals(0f, left("page-true"))
        }
    }

    @Test fun predictiveBackFollowsTheFingerAndACancelledGestureReturns() {
        deep = true
        pages().use {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            // Half way under the finger: the page below shows, coming in from the start; nothing is committed yet.
            assertTrue(deep)
            assertTrue("below at ${left("page-false")}", left("page-false") < 0f && left("page-false") > -30f)
            assertTrue("leaving at ${left("page-true")}", left("page-true") > 0f)
            compose.runOnUiThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue(deep)
            compose.onNodeWithTag("page-false").assertDoesNotExist()
            assertEquals(0f, left("page-true"))

            compose.runOnUiThread {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, .5f, BackEventCompat.EDGE_LEFT))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitForIdle()
            assertFalse(deep)
            compose.onNodeWithTag("page-true").assertDoesNotExist()
            assertEquals(0f, left("page-false"))
        }
    }

    @Test fun aHiddenTabTakesNoBackAndTheTopPageNone() {
        deep = true
        shown.value = false
        pages().use {
            compose.runOnIdle { assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
            compose.runOnIdle { shown.value = true }
            compose.runOnIdle { assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
            compose.runOnIdle { deep = false }
            compose.runOnIdle { assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        }
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value

    private fun pages(rtl: Boolean = false, reduced: Boolean = false): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        activity = controller.get()
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced, LocalTabShown provides shown,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    rememberSharedAxis(deep, backTo = false, back = if (deep) ({ deep = false }) else null, "test")
                        .SharedAxisContent({ if (it) 1 else 0 }, Modifier.fillMaxSize()) { page ->
                            Box(Modifier.fillMaxSize().testTag("page-$page"))
                        }
                }
            }
        }
        compose.waitForIdle()
        return AutoCloseable { controller.pause().stop().destroy() }
    }
}
