package dev.ahmed.savlino.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aFingerRestingAnywhereInABackSwipeStillSeesAPage() {
        deep = true
        // Opaque pages on white: the green channel is what neither page covers.
        pages(paint = true).use {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeByFrame()
            for (progress in listOf(.1f, .25f, .4f, .6f, .9f)) {
                compose.runOnUiThread {
                    activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(400f * progress, 400f, progress, BackEventCompat.EDGE_LEFT))
                }
                repeat(6) { compose.mainClock.advanceTimeByFrame() }
                val view = activity.window.decorView
                val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(frame))
                val uncovered = Color.green(frame.getPixel(view.width / 2, view.height / 2)) / 255f
                assertTrue("at $progress ${(uncovered * 100).toInt()}% of the screen shows neither page", uncovered < .4f)
            }
            compose.runOnUiThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue(deep)
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

    @Test fun aTallerPageResizesWithTheAxisAndEndsWithIt() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        activity = controller.get()
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                rememberSharedAxis(deep, backTo = false, back = null, "test")
                    .SharedAxisContent({ if (it) 1 else 0 }, Modifier.testTag("slot")) { page ->
                        Box(Modifier.size(100.dp, if (page) 400.dp else 100.dp))
                    }
            }
        }
        compose.waitForIdle()
        try {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { deep = true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(150)
            val mid = compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().let { it.bottom - it.top }.value
            assertTrue("mid $mid", mid > 100f && mid < 400f)
            compose.mainClock.advanceTimeBy(166)
            assertEquals(400f, compose.onNodeWithTag("slot").getUnclippedBoundsInRoot().let { it.bottom - it.top }.value)
        } finally { compose.mainClock.autoAdvance = true; controller.pause().stop().destroy() }
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value

    /** The platform's own back callback (Android 14+), as the system reaches it: the top one in the window's dispatcher. */
    private fun systemBack(): OnBackAnimationCallback {
        // The window hands out a proxy; the view root's real dispatcher behind it ranks the callbacks.
        val proxy = activity.window.onBackInvokedDispatcher
        val dispatcher = proxy.javaClass.getDeclaredField("mActualDispatcher").apply { isAccessible = true }.get(proxy)
        val top = checkNotNull(dispatcher) { "no window dispatcher behind the proxy" }
            .let { it.javaClass.getMethod("getTopCallback").invoke(it) }
        return checkNotNull(top) { "no back callback registered with the window" } as OnBackAnimationCallback
    }

    private fun swipe(progress: Float) = BackEvent(400f * progress, 400f, progress, BackEvent.EDGE_LEFT)

    @Config(sdk = [35])
    @Test fun theSystemBackGestureFollowsTheFingerCancelsAndCommits() {
        deep = true
        pages().use {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { systemBack().run { onBackStarted(swipe(0f)); onBackProgressed(swipe(.5f)) } }
            repeat(6) { compose.mainClock.advanceTimeByFrame() }
            assertTrue(deep)
            assertTrue("below at ${left("page-false")}", left("page-false") < 0f && left("page-false") > -30f)
            compose.runOnUiThread { systemBack().onBackCancelled() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue(deep)
            compose.onNodeWithTag("page-false").assertDoesNotExist()
            assertEquals(0f, left("page-true"))

            compose.runOnUiThread { systemBack().run { onBackStarted(swipe(0f)); onBackProgressed(swipe(.5f)); onBackInvoked() } }
            compose.waitForIdle()
            assertFalse(deep)
            compose.onNodeWithTag("page-true").assertDoesNotExist()
            assertEquals(0f, left("page-false"))
        }
    }

    @Config(sdk = [35])
    @Test fun somethingOpenOverThePageTakesTheSystemBackFirst() {
        deep = true
        val overlay = mutableStateOf(true)
        pages(overlay = overlay).use {
            compose.runOnIdle { systemBack().onBackInvoked() }
            compose.waitForIdle()
            assertFalse(overlay.value)
            assertTrue(deep)
            compose.runOnIdle { systemBack().onBackInvoked() }
            compose.waitForIdle()
            assertFalse(deep)
        }
    }

    private fun pages(rtl: Boolean = false, reduced: Boolean = false, paint: Boolean = false,
                      overlay: MutableState<Boolean>? = null): AutoCloseable {
        // Robolectric doesn't carry the manifest's enableOnBackInvokedCallback into ApplicationInfo; the device does.
        if (Build.VERSION.SDK_INT >= 33) ApplicationInfo::class.java.getMethod("setEnableOnBackInvokedCallback", Boolean::class.java)
            .invoke(RuntimeEnvironment.getApplication().applicationInfo, true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        activity = controller.get()
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced, LocalTabShown provides shown,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxSize().then(if (paint) Modifier.background(ComposeColor.White) else Modifier)) {
                        rememberSharedAxis(deep, backTo = false, back = if (deep) ({ deep = false }) else null, "test")
                            .SharedAxisContent({ if (it) 1 else 0 }, Modifier.fillMaxSize()) { page ->
                                Box(Modifier.fillMaxSize().testTag("page-$page")
                                    .then(if (paint) Modifier.background(if (page) ComposeColor.Red else ComposeColor.Blue) else Modifier))
                            }
                        // Composed after the page, like a sheet or search open over it.
                        if (overlay != null) BackHandler(overlay.value) { overlay.value = false }
                    }
                }
            }
        }
        compose.waitForIdle()
        return AutoCloseable { controller.pause().stop().destroy() }
    }
}
