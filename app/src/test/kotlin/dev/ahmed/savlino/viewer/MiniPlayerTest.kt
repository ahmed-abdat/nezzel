package dev.ahmed.savlino.viewer

import android.net.Uri
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.AudioConnection
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlaybackState
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Counts the audio service's opens and closes: expanding the bar must reuse the same session, Close must end it. */
private class CountingAudio(private val players: MutableList<ViewerTestPlayer>) : AudioConnection {
    var opens = 0
    var closes = 0
    override fun open(item: ViewerItem, onPlayer: (Player) -> Unit, onFailure: () -> Unit) {
        opens++
        onPlayer(ViewerTestPlayer().also { it.setMediaItem(MediaItem.fromUri(item.uri)); it.prepare(); players += it })
    }
    override fun close() { closes++ }
}

/** Music shrinks into a bar above the tabs and keeps playing; everything else still only closes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class MiniPlayerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private val players = mutableListOf<ViewerTestPlayer>()
    private val audio = CountingAudio(players)
    private val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
    @After fun close() { controller.pause().stop().destroy() }

    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 100_000, isAudio = true)
    private val clip = ViewerItem(Uri.parse("content://media/external/video/media/6"), true, "video/mp4", 100_000)
    private val host = ViewerHostState()
    private var items by mutableStateOf(listOf(song))
    private var open by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private var taps = 0

    private fun show(reduced: Boolean = false) {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(dev.ahmed.savlino.ui.theme.LocalReducedMotion provides reduced) {
                    ViewerHost(host) {
                        NavigationScaffold("Downloads", emptyList(), "en", {}) {
                            TabContent("Downloads") {
                                Button({ taps++ }, Modifier.fillMaxSize()) { Text("Below") }
                                CompositionLocalProvider(LocalMediaPictures provides MediaPictures { _, _, _ -> null },
                                    LocalPlaybackEnvironment provides playback, LocalAudioConnection provides { audio }) {
                                    if (open && mounted) MediaViewer(items, 0, "en", { open = false }, onShare = {}, onOpenWith = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun text(id: Int) = activity.getString(id)
    private fun node(id: Int) = compose.onNodeWithContentDescription(text(id))
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); compose.mainClock.advanceTimeBy(millis); compose.waitForIdle()
    }
    // Music starts by itself on open; press Play only if it hasn't yet.
    private fun start() {
        if (compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().isEmpty()) node(R.string.viewer_play).performClick()
    }
    /** Opens the song and starts it, so the page can shrink. */
    private fun playSong() {
        show(); open = true; compose.waitForIdle()
        start()
        playback.audioState.value = PlaybackState.Ready
        advance(300)
        assertTrue(players.last().playWhenReady)
        audio.closes = 0 // Settling a page releases the previous item first; count only what follows.
    }
    private fun placed(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).any { it.layoutInfo.isPlaced }
    private fun bar(): SemanticsNode = compose.onNodeWithTag(MINI_PLAYER_TAG).fetchSemanticsNode()
    private fun collapse() { node(R.string.viewer_minimize).performClick(); advance(600) }
    private fun back() { compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }; advance(600) }

    @Test fun layoutHandsOffFromPageToBar() {
        val from = Rect(0f, 100f, 300f, 400f)
        val to = Rect(20f, 700f, 68f, 748f)
        MiniPlayerLayout.at(0f, from, to).run {
            assertEquals(1f, chrome); assertEquals(1f, page); assertEquals(0f, bar); assertEquals(1f, tabs); assertEquals(from, cover)
        }
        MiniPlayerLayout.at(.5f, from, to).run {
            assertEquals("Controls are gone first", 0f, chrome); assertEquals(0f, title)
            assertEquals(1f - .2f / .6f, page, .001f); assertEquals("The bar is not there yet", 0f, bar); assertEquals(.5f, tabs)
        }
        MiniPlayerLayout.at(1f, from, to).run {
            assertEquals(0f, page); assertEquals(1f, bar); assertEquals(0f, tabs); assertEquals(to, cover); assertEquals(8.dp, corner)
        }
        // The bar's cover sits at the start: right in Arabic.
        val slot = Rect(16f, 700f, 368f, 764f)
        assertEquals(24f, MiniSlot(slot, false).cover(8f, 48f).left)
        assertEquals(368f - 8f - 48f, MiniSlot(slot, true).cover(8f, 48f).left)
    }

    @Test fun videoHasNoMinimizeAndNoBar() {
        items = listOf(clip); show(); open = true; compose.waitForIdle()
        node(R.string.viewer_minimize).assertDoesNotExist()
        node(R.string.viewer_close).assertExists()
        assertNull(host.mini)
        back()
        assertFalse("Back on a video closes it", open)
    }

    @Test fun minimizeShrinksMusicIntoTheBarAndTheAppIsUsable() {
        playSong()
        assertFalse("A full-screen page has no bar under the finger", placed(MINI_PLAYER_TAG))
        collapse()
        assertTrue(host.collapsed)
        assertTrue(placed(MINI_PLAYER_TAG))
        assertFalse("The page is no longer placed", placed(VIEWER_TAG))
        assertTrue("Music keeps playing", players.last().playWhenReady)
        assertTrue(open)
        compose.onNodeWithText("Below").performClick()
        assertEquals(1, taps)
        // The bar sits above the tabs, inside the screen.
        val bounds = bar().boundsInRoot
        assertTrue(bounds.bottom < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        assertEquals(64f, bounds.height / activity.resources.displayMetrics.density, 1f)
    }

    @Test fun tappingTheBarOpensTheSamePlayer() {
        playSong()
        val player = players.last()
        collapse()
        compose.onNodeWithTag(MINI_PLAYER_TAG).performClick(); advance(600)
        assertFalse(host.moving)
        assertTrue(placed(VIEWER_TAG))
        assertEquals("No second session", 1, audio.opens)
        assertSame(player, players.last())
        node(R.string.viewer_pause).assertExists()
    }

    @Test fun theBarsCloseStopsTheMusic() {
        playSong()
        collapse()
        val density = activity.resources.displayMetrics.density
        compose.onNodeWithTag(MINI_PLAYER_TAG).performTouchInput { click(centerRight - Offset(32 * density, 0f)) }
        advance(300)
        assertFalse(open)
        assertFalse(host.open)
        assertEquals(1, audio.closes)
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
    }

    @Test fun backOnMusicShrinksItAndBackAgainLeavesItPlaying() {
        playSong()
        back()
        assertTrue(host.collapsed)
        assertTrue(open)
        assertEquals(0, audio.closes)
    }

    @Test fun predictiveBackFollowsTheGestureAndCancelGoesBack() {
        playSong()
        val dispatcher = activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 400f, .5f, BackEventCompat.EDGE_LEFT)) }
        advance(16)
        assertTrue("The page follows the back gesture", host.moving)
        assertFalse(host.collapsed)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }; advance(600)
        assertFalse(host.moving)
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 400f, .3f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnIdle { dispatcher.onBackPressed() }; advance(600)
        assertTrue(host.collapsed)
        assertTrue(players.last().playWhenReady)
    }

    @Test fun reducedMotionJumpsToTheBar() {
        show(reduced = true); open = true; compose.waitForIdle()
        start()
        playback.audioState.value = PlaybackState.Ready
        advance(300)
        compose.mainClock.autoAdvance = false
        node(R.string.viewer_minimize).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(host.collapsed)
    }

    @Test fun theBarIsOneTalkBackStopWithItsActions() {
        DisplayNames.name = "Song.mp3"
        org.robolectric.Robolectric.setupContentProvider(DisplayNames::class.java, "media")
        playSong()
        node(R.string.viewer_minimize).assertHasClickAction()
        collapse()
        val bar = bar()
        assertEquals(Role.Button, bar.config.getOrNull(SemanticsProperties.Role))
        assertEquals(activity.getString(R.string.mini_playing, "Song"), bar.config[SemanticsProperties.ContentDescription].single())
        val actions = bar.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf(text(R.string.viewer_pause), text(R.string.viewer_close), text(R.string.mini_open_player)), actions)
        compose.runOnIdle { bar.config[SemanticsActions.CustomActions][0].action() }; advance(300)
        assertFalse(players.last().playWhenReady)
        assertEquals(activity.getString(R.string.mini_paused, "Song"), bar().config[SemanticsProperties.ContentDescription].single())
    }

    @Test fun aTrackThatEndsInTheBarClearsIt() {
        playSong()
        collapse()
        playback.audioState.value = PlaybackState.Ended; advance(300)
        assertFalse(host.open)
        assertFalse(open)
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
    }

    @Test fun aNewIntentShrinksMusicAndClosesVideo() {
        playSong()
        compose.runOnIdle { host.yieldToApp() }; advance(600)
        assertTrue(host.collapsed)
        compose.runOnIdle { host.close() }; advance(300)
        items = listOf(clip); open = true; compose.waitForIdle()
        compose.runOnIdle { host.yieldToApp() }; advance(300)
        assertFalse(open)
        assertFalse(host.open)
    }

    @Test fun theBarOutlivesTheScreenThatOpenedIt() {
        playSong()
        collapse()
        mounted = false; advance(300)
        assertTrue(host.open)
        assertTrue(placed(MINI_PLAYER_TAG))
        assertTrue(players.last().playWhenReady)
        assertEquals(0, audio.closes)
        val density = activity.resources.displayMetrics.density
        compose.onNodeWithTag(MINI_PLAYER_TAG).performTouchInput { click(centerRight - Offset(32 * density, 0f)) }
        advance(300)
        assertFalse(host.open)
        assertEquals(1, audio.closes)
    }
}
