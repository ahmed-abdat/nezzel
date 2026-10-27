package dev.ahmed.savlino.viewer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Duration

/** A1–A4 on a virtual clock: tap/hide, ±10 s, speed and fullscreen, with players that record what they were asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w384dp-h832dp")
class ViewerControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val activity get() = controller.get()
    private val players = mutableListOf<ViewerTestPlayer>()
    private val counts = mutableListOf<String>()
    private var prepare: (ViewerTestPlayer) -> Unit = {}
    private var wrap: (ViewerTestPlayer) -> Player = { it }
    private lateinit var playback: PlaybackEnvironment
    private var open by mutableStateOf(true)
    private val pictures = MediaPictures { _, _, _ -> Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888) }
    private fun video(n: Int) = ViewerItem(Uri.parse("content://media/external/video/$n"), true, "video/mp4")
    private fun image(n: Int) = ViewerItem(Uri.parse("content://media/external/images/$n"), false, "image/jpeg")
    private val middle = listOf(image(1), video(2), image(3))

    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }

    private fun show(items: List<ViewerItem> = middle, start: Int = 1, language: String = "en") {
        RuntimeEnvironment.setQualifiers("+$language")
        // A phone in touch mode: no initial key focus, which would (rightly) hold the controls open.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> wrap(ViewerTestPlayer().also { prepare(it); players += it }) },
            usage = object : UsageEvents { override fun count(key: String) { counts += key } }, sizeOf = { 10L })
        content(items, start, language)
    }
    private fun content(items: List<ViewerItem>, start: Int, language: String) {
        activity.setContent {
            NezzelTheme(ThemePref.Dark, false) {
                CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback) {
                    if (open) MediaViewer(items, start, language, { open = false }, onShare = {}, onOpenWith = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = activity.getString(id, *args)
    private fun node(id: Int) = compose.onNodeWithContentDescription(text(id))
    private fun page(n: Int, of: Int = 3) = compose.onNodeWithContentDescription(text(R.string.viewer_video_n, "$n", "$of"))
    private val player get() = players.last()
    /** The viewer's delays run on the main looper's clock: this is the virtual clock. */
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); compose.mainClock.advanceTimeBy(millis); compose.waitForIdle()
    }
    private fun shown() = compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().isNotEmpty()
    private fun hiddenEverywhere() {
        node(R.string.viewer_pause).assertDoesNotExist()
        node(R.string.viewer_close).assertDoesNotExist()
        node(R.string.viewer_seek_back_10).assertDoesNotExist()
    }
    /** Above the centre buttons, below the top bar: plain canvas. */
    private fun TouchInjectionScope.canvas(x: Float = centerX) = Offset(x, height * .3f)
    private fun tapCanvas() { page(2).performTouchInput { click(canvas()) }; advance(400) }
    private fun canvasActions() = page(2).onChild().fetchSemanticsNode().config[SemanticsActions.CustomActions]

    // A1
    @Test fun controlsHideAfterThreeSecondsOfPlaybackAndAnyTouchStartsTheWaitOver() {
        show()
        assertTrue(player.isPlaying)
        advance(2_000)
        node(R.string.viewer_seek_back_10).performClick()
        advance(2_000)
        assertTrue("A touch restarts the wait", shown())
        advance(1_500)
        hiddenEverywhere()
    }

    @Test fun volumePanelMutesSetsPhoneVolumeAndHoldsTheControls() {
        show()
        val audio = activity.getSystemService(android.media.AudioManager::class.java)
        node(R.string.viewer_volume).performClick()
        advance(2_500)
        assertTrue("Volume panel open keeps controls", shown())
        node(R.string.viewer_mute).performClick()
        compose.waitForIdle()
        assertEquals(0f, player.volume)
        node(R.string.viewer_unmute).assertExists()
        compose.onNode(hasContentDescription(text(R.string.viewer_volume)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.waitForIdle()
        assertEquals(5, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        assertEquals("Raising the volume turns sound back on", 1f, player.volume)
    }

    private fun volumeSlider() = compose.onNode(hasContentDescription(text(R.string.viewer_volume)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    @Test fun volumeStripTakesTheButtonRowAndNeverCoversTheSeekBar() {
        show(language = "ar")
        val seek = compose.onNodeWithContentDescription(text(R.string.viewer_position)).fetchSemanticsNode().boundsInRoot
        node(R.string.viewer_volume).performClick(); advance(400)
        val mute = node(R.string.viewer_mute).fetchSemanticsNode().boundsInRoot
        val slider = volumeSlider().fetchSemanticsNode().boundsInRoot
        assertTrue("Below the seek bar", mute.top >= seek.bottom && slider.top >= seek.bottom)
        assertTrue("Quiet left, loud right in Arabic too: speaker at the left end ($mute, $slider)", mute.center.x < slider.left && mute.center.x < slider.center.x)
        node(R.string.viewer_speed).assertDoesNotExist()
    }

    @Test fun volumeStripClosesAfterThreeQuietSecondsOrATapElsewhere() {
        show()
        node(R.string.viewer_volume).performClick(); advance(400)
        volumeSlider().assertExists()
        advance(ViewerControls.HIDE_AFTER + 200)
        volumeSlider().assertDoesNotExist()
        assertTrue("Closing the strip leaves the controls up", shown())
        node(R.string.viewer_volume).performClick(); advance(400)
        tapCanvas()
        volumeSlider().assertDoesNotExist()
        assertTrue("That tap closed only the strip", shown())
    }

    @Test fun volumeKeysUpdateTheIconWithoutOpeningThePanel() {
        val audio = RuntimeEnvironment.getApplication().getSystemService(android.media.AudioManager::class.java)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 9, 0)
        show()
        val muted = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, text(R.string.viewer_volume_muted))
        node(R.string.viewer_volume).assert(!muted)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        activity.contentResolver.notifyChange(android.provider.Settings.System.CONTENT_URI, null)
        advance(100)
        node(R.string.viewer_volume).assert(muted)
    }

    @Test fun bigCentreControlsOnVideoPauseAndSkip() {
        show()
        compose.runOnIdle { player.seekTo(30_000) }
        node(R.string.viewer_pause).performClick()
        compose.waitForIdle()
        assertFalse(player.playWhenReady)
        node(R.string.viewer_seek_forward_10).performClick()
        compose.waitForIdle()
        assertEquals(40_000, player.currentPosition)
    }

    @Test fun canvasTapTogglesChromeAndNeverPauses() {
        show()
        tapCanvas()
        hiddenEverywhere()
        assertTrue(player.playWhenReady)
        tapCanvas()
        node(R.string.viewer_close).assertExists()
        assertTrue(shown())
        assertTrue(player.playWhenReady)
    }

    @Test fun timerFreezesWhileMenuIsOpenOrBuffering() {
        show()
        node(R.string.viewer_speed).performClick()
        advance(6_000)
        assertTrue("Menu open keeps controls", shown())
        compose.onNodeWithText(text(R.string.viewer_speed_normal)).performClick()
        advance(500)
        compose.runOnIdle { player.buffering = true }
        advance(6_000)
        assertTrue("Buffering is not playback time", shown())
        compose.runOnIdle { player.buffering = false }
        advance(3_500)
        hiddenEverywhere()
    }

    @Test fun pauseFromAnywhereBringsControlsBack() {
        show()
        tapCanvas()
        hiddenEverywhere()
        compose.runOnIdle { player.playWhenReady = false }
        advance(300)
        node(R.string.viewer_play).assertExists()
        advance(6_000)
        node(R.string.viewer_play).assertExists()
    }

    @Test fun touchExplorationNeverHidesOnItsOwn() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        show()
        advance(8_000)
        assertTrue(shown())
    }

    @Test fun hiddenControlsLeaveTheTreeAndSemanticActionsRemain() {
        show()
        advance(3_500)
        hiddenEverywhere()
        assertTrue(compose.onAllNodes(isFocusable()).fetchSemanticsNodes().none { it.config.contains(SemanticsActions.OnClick) })
        val actions = canvasActions().map { it.label }
        assertEquals(listOf(text(R.string.viewer_show_controls), text(R.string.viewer_seek_back_10), text(R.string.viewer_seek_forward_10)), actions)
        compose.runOnIdle { canvasActions().first().action() }
        advance(300)
        assertTrue(shown())
        assertEquals(text(R.string.viewer_hide_controls), canvasActions().first().label)
    }

    // A2
    private fun doubleTapPhysical(forward: Boolean) {
        page(2).performTouchInput { doubleClick(canvas(if (forward) width * .8f else width * .2f)) }
        advance(400)
    }

    private fun skipsByPhysicalSide(language: String) {
        show(language = language)
        compose.runOnIdle { player.inner.at(30_000) }
        doubleTapPhysical(forward = false)
        assertEquals(20_000, player.currentPosition)
        assertTrue("A double tap never also toggles", shown())
        doubleTapPhysical(forward = true)
        doubleTapPhysical(forward = true)
        assertEquals(40_000, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertEquals(3, counts.count { it == UsageKeys.PLAYER_SEEK })
    }
    @Test fun doubleTapLeftGoesBackRightGoesForward() = skipsByPhysicalSide("en")
    @Test fun doubleTapDirectionsAreTheSameInArabic() = skipsByPhysicalSide("ar")

    @Test fun skipClampsToTheTimelineAndKeepsPause() {
        show()
        compose.runOnIdle { player.playWhenReady = false; player.inner.at(4_000) }
        node(R.string.viewer_seek_back_10).performClick()
        assertEquals(0, player.currentPosition)
        compose.runOnIdle { player.inner.at(95_000) }
        node(R.string.viewer_seek_forward_10).performClick()
        assertEquals(100_000, player.currentPosition)
        assertFalse(player.playWhenReady)
    }

    @Test fun nothingSeeksUntilTheTimelineIsSeekable() {
        prepare = { it.seekable = false }
        show()
        advance(300)
        node(R.string.viewer_seek_back_10).assertIsNotEnabled()
        node(R.string.viewer_seek_forward_10).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.viewer_position)).assertIsNotEnabled()
        compose.runOnIdle { player.inner.at(30_000) }
        doubleTapPhysical(forward = true)
        assertEquals(30_000, player.currentPosition)
        compose.onNodeWithText(text(R.string.viewer_seek_unavailable)).assertExists()
        assertEquals(listOf(text(R.string.viewer_hide_controls)), canvasActions().map { it.label })
        assertTrue(counts.none { it == UsageKeys.PLAYER_SEEK })
    }

    // A drag lets go where the finger left, and quick seek buffers pass without a ring.
    private fun seekBar() = compose.onNodeWithContentDescription(text(R.string.viewer_position))
    private fun dragTo(millis: Float) = seekBar().performSemanticsAction(SemanticsActions.SetProgress) { it(millis) }
    private fun thumbAt(millis: Float) = seekBar().assert(SemanticsMatcher("thumb at $millis") {
        it.config[SemanticsProperties.ProgressBarRangeInfo].current == millis })
    private fun rings() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun releasedDragHoldsItsTargetUntilThePlayerGetsThere() {
        show(); advance(300)
        compose.runOnIdle { player.playWhenReady = false; player.slowSeeks = true }
        dragTo(80_000f); advance(500)
        assertEquals(0, player.currentPosition)
        thumbAt(80_000f); compose.onNodeWithText("1:20").assertExists()
        compose.runOnIdle { player.catchUp() }; advance(500)
        assertEquals(80_000, player.currentPosition)
        thumbAt(80_000f); compose.onNodeWithText("1:20").assertExists()
    }

    @Test fun aSeekThePlayerNeverReachesLetsGoAfterAMoment() {
        show(); advance(300)
        compose.runOnIdle { player.playWhenReady = false; player.slowSeeks = true }
        dragTo(80_000f); advance(1_000)
        thumbAt(80_000f)
        advance(1_000)
        thumbAt(0f); compose.onNodeWithText("0:00").assertExists()
    }

    @Test fun skipHoldsItsTargetToo() {
        show(); advance(300)
        compose.runOnIdle { player.playWhenReady = false; player.slowSeeks = true }
        node(R.string.viewer_seek_forward_10).performClick(); advance(600)
        thumbAt(10_000f)
    }

    private fun scrubbed(): ScrubWatch {
        lateinit var watch: ScrubWatch
        wrap = { ScrubWatch(it).also { w -> watch = w }.player }
        show(); advance(300)
        compose.runOnIdle { player.playWhenReady = false }
        seekBar().performTouchInput { down(Offset(width * .2f, centerY)); moveTo(Offset(width * .5f, centerY)) }
        advance(100)
        return watch
    }

    @Test fun draggingTheSeekBarScrubsUntilRelease() {
        val watch = scrubbed()
        assertTrue(watch.scrubbing)
        assertTrue("The picture follows the finger", player.currentPosition > 0)
        seekBar().performTouchInput { moveTo(Offset(width * .7f, centerY)); up() }
        advance(300)
        assertFalse(watch.scrubbing)
    }

    @Test fun leavingMidDragStopsScrubbing() {
        val watch = scrubbed()
        assertTrue(watch.scrubbing)
        open = false; advance(300)
        assertFalse(watch.scrubbing)
    }

    @Test fun shortBuffersShowNoRingLongOnesDo() {
        show(); advance(300)
        compose.runOnIdle { player.playWhenReady = false; player.buffering = true }
        advance(300)
        assertEquals(0, rings())
        compose.runOnIdle { player.buffering = false }; advance(1_000)
        assertEquals(0, rings())
        compose.runOnIdle { player.buffering = true }; advance(700)
        assertEquals(1, rings())
    }

    @Test fun pagerDragCancelsTheTap() {
        show()
        tapCanvas()
        hiddenEverywhere()
        page(2).performTouchInput { down(canvas()); moveBy(Offset(60f * density, 0f)); moveBy(Offset(20f * density, 0f)); up() }
        advance(1_000)
        page(2).assertExists()
        hiddenEverywhere()
    }

    // A3
    private fun pickSpeed(label: String) {
        node(R.string.viewer_speed).performClick()
        compose.onNodeWithText(label, substring = true).performClick()
        compose.waitForIdle()
    }

    @Test fun speedAppliesWithoutRestartAndShowsSelectedState() {
        show()
        pickSpeed("1.5×")
        assertEquals(1.5f, player.playbackParameters.speed)
        assertEquals(1f, player.playbackParameters.pitch)
        assertEquals(1, player.inner.prepared)
        assertTrue(player.playWhenReady)
        node(R.string.viewer_speed).assertTextEquals("1.5×")
        node(R.string.viewer_speed).performClick()
        val selected = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true)
        compose.onAllNodes(selected).assertCountEquals(1)
        compose.onNode(selected).assert(hasAnyDescendant(hasText("1.5×", substring = true)) or hasText("1.5×", substring = true))
        assertEquals(listOf(UsageKeys.PLAYER_SPEED), counts.filter { it == UsageKeys.PLAYER_SPEED })
    }

    @Test fun pausedSpeedChangeStaysPaused() {
        show()
        compose.runOnIdle { player.playWhenReady = false }
        pickSpeed("0.75×")
        assertEquals(.75f, player.playbackParameters.speed)
        assertFalse(player.playWhenReady)
    }

    @Test fun refusedSpeedKeepsTheOldValueAndSaysSo() {
        prepare = { it.refuseSpeed = true }
        show()
        pickSpeed("2×")
        assertEquals(1f, player.playbackParameters.speed)
        node(R.string.viewer_speed).assertTextEquals("1×")
        compose.onNodeWithText(text(R.string.viewer_speed_failed)).assertExists()
        assertTrue(counts.none { it == UsageKeys.PLAYER_SPEED })
    }

    @Test fun speedSurvivesRecreationOnTheSamePlayer() {
        show()
        pickSpeed("1.25×")
        controller.recreate()
        content(middle, 1, "en")
        assertEquals("Rotation must not start another player", 1, players.size)
        assertEquals(1.25f, player.playbackParameters.speed)
        node(R.string.viewer_speed).assertTextEquals("1.25×")
    }

    @Test fun anotherFileStartsAtNormalSpeed() {
        val two = listOf(video(1), video(2))
        show(two, 0)
        pickSpeed("2×")
        page(1, 2).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2, players.size)
        assertEquals(1f, players[1].playbackParameters.speed)
        node(R.string.viewer_speed).assertTextEquals("1×")
        page(2, 2).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1f, player.playbackParameters.speed)
    }

    // A4
    @Test fun fullscreenOnlyForWideVideo() {
        for (size in listOf(VideoSize.UNKNOWN, VideoSize(720, 1280), VideoSize(1080, 1080))) {
            prepare = { it.size = size }
            show()
            advance(300)
            node(R.string.viewer_fullscreen).assertDoesNotExist()
            controller.pause().stop().destroy()
        }
        prepare = { it.size = VideoSize(1280, 720) }
        show()
        advance(300)
        node(R.string.viewer_fullscreen).assertHasClickAction()
        assertEquals("Opening never rotates", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test fun fullscreenTurnsLandscapeAndBackOrExitRestores() {
        prepare = { it.size = VideoSize(1920, 1080) }
        show()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        advance(300)
        node(R.string.viewer_fullscreen).performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(listOf(UsageKeys.PLAYER_FULLSCREEN), counts.filter { it == UsageKeys.PLAYER_FULLSCREEN })
        node(R.string.viewer_exit_fullscreen).performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

        node(R.string.viewer_fullscreen).performClick()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue("Back leaves fullscreen first", open)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(open)
    }

    @Test fun closingInFullscreenRestoresOrientation() {
        prepare = { it.size = VideoSize(1920, 1080) }
        show()
        advance(300)
        node(R.string.viewer_fullscreen).performClick()
        node(R.string.viewer_close).performClick()
        compose.waitForIdle()
        assertFalse(open)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test fun recreatedActivityDropsLeftoverLandscapeAndARestoredFullscreenAsksAgain() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ViewerFullscreen.release(activity)
        assertEquals("No viewer came back: the app is not stuck in landscape", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ViewerFullscreen.release(activity)
        assertEquals("Only our landscape is dropped", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

        val restored = ViewerControls(fullscreen = true, orientationBefore = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        ViewerFullscreen.resume(activity, restored)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        ViewerFullscreen.exit(activity, restored)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        ViewerFullscreen.resume(activity, ViewerControls())
        assertEquals("A viewer outside fullscreen never rotates", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test fun picturesKeepTheirChrome() {
        show(middle, 0)
        advance(5_000)
        node(R.string.viewer_close).assertExists()
    }
}
