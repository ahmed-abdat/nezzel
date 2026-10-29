package dev.ahmed.savlino.viewer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.VideoSize
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/** Swipe down to close a video or photo, out of fullscreen in fullscreen; the first picture; hold for 2×. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class ViewerPullTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val activity get() = controller.get()
    private val players = mutableListOf<ViewerTestPlayer>()
    private var prepare: (ViewerTestPlayer) -> Unit = {}
    private var open by mutableStateOf(true)
    private val host = ViewerHostState()
    private val pictures = MediaPictures { _, _, _ -> Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888) }
    private fun video(n: Int) = ViewerItem(Uri.parse("content://media/external/video/$n"), true, "video/mp4")
    private fun image(n: Int) = ViewerItem(Uri.parse("content://media/external/images/$n"), false, "image/jpeg")
    private fun audio(n: Int) = ViewerItem(Uri.parse("content://media/external/audio/$n"), false, "audio/mpeg", isAudio = true)
    private val player get() = players.last()

    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }

    private fun show(items: List<ViewerItem>, start: Int = 0, reduced: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer().also { prepare(it); players += it } }, sizeOf = { 10L })
        activity.setContent {
            NezzelTheme(ThemePref.Dark, false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    ViewerHost(host) {
                        CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback,
                            LocalAudioConnection provides { TestAudio() }) {
                            if (open) MediaViewer(items, start, "en", { open = false }, onShare = {}, onOpenWith = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun text(id: Int, vararg args: Any) = activity.getString(id, *args)
    private fun node(id: Int) = compose.onNodeWithContentDescription(text(id))
    private fun viewer() = compose.onNodeWithTag(VIEWER_TAG)
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); compose.mainClock.advanceTimeBy(millis); compose.waitForIdle()
    }
    /** Above the centre buttons, below the top bar: plain canvas. */
    private fun TouchInjectionScope.canvas() = Offset(centerX, height * .3f)
    /** A slow pull of [by] pixels that stops before letting go: no fling, only the distance counts. */
    private fun TouchInjectionScope.slowPull(by: Float, from: Offset = canvas()) {
        down(from); repeat(20) { moveBy(Offset(0f, by / 20)) }; advanceEventTime(300); moveBy(Offset(0f, .01f)); advanceEventTime(300); up()
    }
    private fun poster() = compose.onAllNodesWithTag(POSTER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun aPhotoPulledPastAFifthOfTheScreenCloses() {
        show(listOf(image(1), image(2)))
        viewer().performTouchInput { down(center); repeat(10) { moveBy(Offset(0f, 30f)) } }
        assertTrue("The page follows the finger", host.pull > 200f)
        assertTrue("The app shows behind it", host.displaced)
        viewer().performTouchInput { advanceEventTime(300); moveBy(Offset(0f, .01f)); advanceEventTime(300); up() }
        advance(400)
        assertFalse(open)
        viewer().assertDoesNotExist()
        // The pull stays until the next show(): a reset here would flash the page back for a frame.
        assertTrue("The page stays pulled out", host.pull > 200f)
        compose.runOnIdle { open = true }; advance(100)
        assertEquals(0f, host.pull)
    }

    @Test fun aShortPullSpringsBack() {
        show(listOf(image(1), image(2)))
        viewer().performTouchInput { slowPull(100f) }
        advance(600)
        assertTrue(open)
        assertEquals(0f, host.pull)
        assertFalse(host.displaced)
    }

    @Test fun aShortFlingDownCloses() {
        show(listOf(image(1), image(2)))
        viewer().performTouchInput { swipe(center, center + Offset(0f, 120f), durationMillis = 50) }
        advance(400)
        assertFalse(open)
    }

    @Test fun anUpwardOrSidewaysDragNeverPulls() {
        show(listOf(image(1), image(2)))
        viewer().performTouchInput { down(center); repeat(10) { moveBy(Offset(0f, -30f)) }; up() }
        assertEquals(0f, host.pull)
        viewer().performTouchInput { swipeLeft() }
        advance(600)
        assertTrue(open)
        compose.onNodeWithContentDescription(text(R.string.viewer_image_n, "2", "2")).assertExists()
    }

    @Test fun aZoomedPhotoPansInsteadOfClosing() {
        show(listOf(image(1)))
        viewer().performTouchInput { doubleClick(center) }
        advance(400)
        viewer().performTouchInput { slowPull(400f) }
        advance(600)
        assertTrue(open)
        assertEquals(0f, host.pull)
    }

    @Test fun reducedMotionDoesNotFollowButStillCloses() {
        show(listOf(image(1)), reduced = true)
        viewer().performTouchInput { down(center); repeat(10) { moveBy(Offset(0f, 30f)) } }
        assertEquals(0f, host.pull)
        viewer().performTouchInput { advanceEventTime(300); moveBy(Offset(0f, .01f)); advanceEventTime(300); up() }
        compose.waitForIdle()
        assertFalse(open)
    }

    @Test fun musicShrinksAndIsNeverPulledClosed() {
        show(listOf(audio(1)))
        viewer().performTouchInput { slowPull(400f) }
        advance(600)
        assertTrue(open)
        assertEquals(0f, host.pull)
    }

    @Test fun aVideoPullsClosedButNotFromTheSeekBar() {
        show(listOf(video(1)))
        advance(300)
        node(R.string.viewer_position).performTouchInput { slowPull(400f, center) }
        advance(600)
        assertTrue("A drag that starts on the seek bar is the seek bar's", open)
        viewer().performTouchInput { slowPull(400f) }
        advance(600)
        assertFalse(open)
    }

    @Test fun anOpenVolumeStripOrSpeedMenuStopsThePull() {
        show(listOf(video(1)))
        advance(300)
        node(R.string.viewer_volume).performClick()
        viewer().performTouchInput { slowPull(400f) }
        advance(300)
        assertTrue(open)
        assertEquals(0f, host.pull)
    }

    @Test fun inFullscreenThePullLeavesFullscreenOnly() {
        prepare = { it.size = VideoSize(1920, 1080) }
        show(listOf(video(1)))
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        advance(300)
        node(R.string.viewer_fullscreen).performClick()
        viewer().performTouchInput { down(canvas()); repeat(10) { moveBy(Offset(0f, 40f)) } }
        assertTrue("It follows, at half the speed", host.pull in 100f..220f)
        assertFalse("The app stays hidden behind black", host.displaced)
        viewer().performTouchInput { advanceEventTime(300); moveBy(Offset(0f, .01f)); advanceEventTime(300); up() }
        advance(600)
        assertTrue(open)
        assertEquals(0f, host.pull)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        node(R.string.viewer_fullscreen).assertExists()
    }

    @Test fun predictiveBackPullsTheVideoAndCancelPutsItBack() {
        show(listOf(video(1)))
        advance(300)
        val dispatcher = activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 400f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue(host.pull > 0f)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advance(600)
        assertEquals(0f, host.pull)
        assertTrue(open)
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        advance(400)
        assertFalse(open)
    }

    @Test fun thePosterStaysUntilTheFirstPictureThenGoes() {
        show(listOf(video(1)))
        advance(300)
        assertEquals("Ready but nothing drawn yet: the poster, not black", 1, poster())
        compose.runOnIdle { player.inner.renderFirstFrame() }
        advance(200)
        assertEquals(0, poster())
        // A seek's buffering keeps the last picture instead of the poster.
        compose.runOnIdle { player.buffering = true }
        advance(300)
        assertEquals(0, poster())
    }

    @Test fun aFastFileThatDrawsBeforeThePageListensLosesItsPosterToo() {
        prepare = { it.frameOnPrepare = true }
        show(listOf(video(1)))
        advance(300)
        assertEquals(0, poster())
    }

    @Test fun holdingAPlayingVideoPlaysAt2xAndLettingGoRestoresTheChosenSpeed() {
        show(listOf(video(1)))
        advance(300)
        node(R.string.viewer_speed).performClick()
        compose.onNodeWithText("1.5", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(1.5f, player.playbackParameters.speed)
        val shown = compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().size
        viewer().performTouchInput { down(canvas()) }
        advance(700)
        assertEquals(2f, player.playbackParameters.speed)
        compose.onNodeWithTag(BOOST_TAG).assertExists()
        // Only the pill while held: no centre buttons, top bar or bottom bar.
        node(R.string.viewer_pause).assertDoesNotExist()
        node(R.string.viewer_close).assertDoesNotExist()
        node(R.string.viewer_speed).assertDoesNotExist()
        // A held finger that then moves down never starts a pull.
        viewer().performTouchInput { repeat(10) { moveBy(Offset(0f, 40f)) } }
        assertEquals(0f, host.pull)
        viewer().performTouchInput { up() }
        advance(400)
        assertEquals(1.5f, player.playbackParameters.speed)
        assertTrue(open)
        compose.onNodeWithTag(BOOST_TAG).assertDoesNotExist()
        assertEquals("A long press doesn't toggle the controls", shown,
            compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().size)
    }

    @Test fun hiddenControlsStayHiddenAfterAHold() {
        show(listOf(video(1)))
        advance(300)
        viewer().performTouchInput { click(canvas()) }
        advance(400)
        node(R.string.viewer_pause).assertDoesNotExist()
        viewer().performTouchInput { down(canvas()) }
        advance(700)
        compose.onNodeWithTag(BOOST_TAG).assertExists()
        viewer().performTouchInput { up() }
        advance(400)
        assertEquals(1f, player.playbackParameters.speed)
        node(R.string.viewer_pause).assertDoesNotExist()
        node(R.string.viewer_close).assertDoesNotExist()
    }

    @Test fun holdingAPausedVideoDoesNothing() {
        show(listOf(video(1)))
        advance(300)
        node(R.string.viewer_pause).performClick()
        viewer().performTouchInput { down(canvas()) }
        advance(700)
        viewer().performTouchInput { up() }
        assertEquals(1f, player.playbackParameters.speed)
        compose.onNodeWithTag(BOOST_TAG).assertDoesNotExist()
    }

    @Test fun tapsStillToggleTheControlsAndDoubleTapsStillSkip() {
        show(listOf(video(1)))
        advance(300)
        viewer().performTouchInput { click(canvas()) }
        advance(400)
        node(R.string.viewer_pause).assertDoesNotExist()
        viewer().performTouchInput { click(canvas()) }
        advance(400)
        node(R.string.viewer_pause).assertExists()
        viewer().performTouchInput { doubleClick(Offset(width * .8f, height * .3f)) }
        advance(400)
        assertEquals(10_000L, player.currentPosition)
        assertEquals(0f, host.pull)
    }
}
