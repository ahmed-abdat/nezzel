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
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
    private var controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private val players = mutableListOf<ViewerTestPlayer>()
    private var audio = CountingAudio(players)
    private var playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
    @After fun close() { controller.pause().stop().destroy() }

    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 100_000, isAudio = true)
    private val clip = ViewerItem(Uri.parse("content://media/external/video/media/6"), true, "video/mp4", 100_000)
    private var host = ViewerHostState()
    /** The host kept in saved state, as the app does, so it survives the process. */
    private var saved = false
    private var items by mutableStateOf(listOf(song))
    private var open by mutableStateOf(false)
    /** The whole library behind [items]; a filter or search shows only part of it. */
    private var library by mutableStateOf<Set<Uri>?>(null)
    private var start by mutableStateOf(0)
    private var mounted by mutableStateOf(true)
    private var taps = 0

    private fun show(reduced: Boolean = false, dark: Boolean = false) {
        activity.setContent {
            NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                CompositionLocalProvider(dev.ahmed.savlino.ui.theme.LocalReducedMotion provides reduced) {
                    val kept = if (saved) rememberViewerHost() else host
                    SideEffect { host = kept }
                    ViewerHost(kept) {
                        NavigationScaffold("Downloads", { emptyList() }, "en", {}) {
                            TabContent("Downloads") {
                                Button({ taps++ }, Modifier.fillMaxSize()) { Text("Below") }
                                CompositionLocalProvider(LocalMediaPictures provides MediaPictures { _, _, _ -> null },
                                    LocalPlaybackEnvironment provides playback, LocalAudioConnection provides { audio }) {
                                    if (open && mounted) MediaViewer(items, start, "en", { open = false }, onShare = {}, onOpenWith = {},
                                        library = library ?: items.mapTo(HashSet()) { it.uri })
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
    private fun playSong(dark: Boolean = false) {
        show(dark = dark); open = true; compose.waitForIdle()
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

    @Test fun theSheetSlidesWithEverythingOnItAndBecomesTheBar() {
        val page = Size(384f, 832f)
        val slot = Rect(16f, 680f, 368f, 744f)
        MiniPlayerLayout.at(0f, page, slot).run {
            assertEquals(Rect(0f, 0f, 384f, 832f), sheet); assertEquals(0.dp, corner)
            assertEquals(1f, content); assertEquals(1f, fade); assertEquals(1f, tabs)
        }
        MiniPlayerLayout.at(.5f, page, slot).run {
            assertEquals("The whole page follows the finger, full width and opaque", Rect(0f, 340f, 384f, 832f), sheet)
            assertEquals("Name and controls are still readable", 1f, content); assertEquals(1f, fade); assertEquals(1f, tabs)
        }
        MiniPlayerLayout.at(.8f, page, slot).run {
            assertTrue("Narrowing into the bar", sheet.left > 0f && sheet.right < 384f && sheet.bottom < 832f && sheet.bottom > slot.bottom)
            assertEquals(0f, content); assertEquals(1f, fade); assertTrue(tabs in 0f..1f)
        }
        MiniPlayerLayout.at(1f, page, slot).run {
            assertEquals(slot, sheet); assertEquals(MiniPlayerLayout.CORNER, corner); assertEquals(0f, fade); assertEquals(0f, tabs)
        }
        // Over a light bar the sheet never fades (no grey): it stays opaque and draws back into the bar's cover.
        val reveal = Rect(24f, 688f, 72f, 736f)
        for (p in listOf(0f, .5f, .85f, .9f, .95f, 1f)) assertEquals(1f, MiniPlayerLayout.at(p, page, slot, reveal).fade)
        assertEquals(MiniPlayerLayout.at(.85f, page, slot).sheet, MiniPlayerLayout.at(.85f, page, slot, reveal).sheet)
        MiniPlayerLayout.at(.95f, page, slot, reveal).sheet.run { assertTrue(left in slot.left..reveal.left && right < slot.right && right > reveal.right) }
        MiniPlayerLayout.at(1f, page, slot, reveal).run { assertEquals(reveal, sheet); assertEquals(MiniPlayerLayout.COVER_CORNER, corner) }
        // Over a dark bar (no reveal) the sheet still fades and ends as the whole bar, never the cover.
        MiniPlayerLayout.at(.85f, page, slot).run { assertEquals(1f, fade); assertTrue(sheet.width > slot.width) }
        MiniPlayerLayout.at(.95f, page, slot).run {
            assertTrue(fade in .01f..(.99f)); assertTrue(sheet.left < slot.left + 1f && sheet.right > slot.right - 1f)
            assertTrue(sheet != MiniPlayerLayout.at(.95f, page, slot, reveal).sheet)
        }
        MiniPlayerLayout.at(1f, page, slot).run { assertEquals(0f, fade); assertEquals(slot, sheet) }
        // The cover rides on the sheet first, then settles exactly onto the bar's cover.
        val from = Rect(24f, 120f, 360f, 456f)
        val to = Rect(24f, 688f, 72f, 736f)
        assertEquals(from, MiniPlayerLayout.cover(0f, from, to, 680f))
        assertEquals(from.translate(0f, 340f), MiniPlayerLayout.cover(.5f, from, to, 680f))
        assertEquals(to, MiniPlayerLayout.cover(1f, from, to, 680f))
        // The bar's cover sits at the start: right in Arabic.
        assertEquals(24f, MiniSlot(slot, false).cover(8f, 48f).left)
        assertEquals(368f - 8f - 48f, MiniSlot(slot, true).cover(8f, 48f).left)
    }

    /** Collapsed over the light bar, the black sheet has drawn back into the bar's cover, on the bar's start side. */
    private fun sheetEndsAsTheBar(rtl: Boolean) {
        playSong()
        collapse()
        assertTrue(host.collapsed)
        val bar = bar().boundsInRoot
        val slot = compose.runOnIdle { host.slot!! }
        assertEquals(bar.left, slot.rect.left, .5f); assertEquals(bar.top, slot.rect.top, .5f)
        assertEquals(bar.right, slot.rect.right, .5f); assertEquals(bar.bottom, slot.rect.bottom, .5f)
        assertEquals(rtl, slot.rtl)
        val cover = slot.cover(8f, 48f)
        if (rtl) assertEquals(bar.right - 8f, cover.right, .5f) else assertEquals(bar.left + 8f, cover.left, .5f)
        val sheet = compose.runOnIdle { host.motion()!! }
        assertEquals(cover, sheet.sheet); assertEquals(MiniPlayerLayout.COVER_CORNER, sheet.corner); assertEquals(1f, sheet.fade)
    }
    @Test fun theSheetEndsAsTheBar() = sheetEndsAsTheBar(false)
    @Config(qualifiers = "ar-w384dp-h832dp")
    @Test fun theSheetEndsAsTheBarInArabic() = sheetEndsAsTheBar(true)

    private fun moveTo(p: Float) {
        compose.runOnIdle { host.size.dispatchRawDelta(p * host.travel - host.size.offset) }
        compose.waitForIdle()
    }
    @Test fun aDarkBarStillFadesTheSheet() {
        playSong(dark = true)
        for (p in listOf(.85f, .95f, 1f)) {
            moveTo(p)
            compose.runOnIdle { assertEquals("p=$p", MiniPlayerLayout.at(host.progress, host.page, host.slot!!.rect), host.motion()) }
        }
        assertTrue(compose.runOnIdle { host.motion()!!.fade } == 0f)
    }

    @Test fun musicThatEndsMidCollapseLeavesNoCoverBehind() {
        playSong()
        moveTo(.95f)
        val reveal = compose.runOnIdle { host.motion()!! }
        assertEquals("Over the light bar the sheet is drawing into the cover", 1f, reveal.fade)
        // The frame it goes, before the bar's slot is gone too: no bar, no cover to draw into, so the sheet fades instead.
        assertTrue(compose.runOnIdle { host.mini = null; host.motion()!!.fade } < 1f)
        advance(600)
        assertTrue(host.open)
        assertEquals("The page went back up", 0f, compose.runOnIdle { host.progress })
        assertFalse(host.collapsed)
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
    }

    @Test fun expandingRetracesCollapsing() {
        playSong()
        val down = listOf(.2f, .5f, .7f, .9f).map { moveTo(it); compose.runOnIdle { host.motion()!! } }
        moveTo(1f)
        assertTrue(host.collapsed)
        val up = listOf(.9f, .7f, .5f, .2f).map { moveTo(it); compose.runOnIdle { host.motion()!! } }.reversed()
        assertEquals(down, up)
        moveTo(0f)
    }

    @Test fun tapsAroundTheCollapsedBarReachTheApp() {
        playSong(); collapse()
        val bar = bar().boundsInRoot
        val slot = compose.runOnIdle { host.slot!!.rect }
        assertEquals("The bar's touch and TalkBack node is only the bar", slot.width, bar.width, 1f)
        assertEquals(slot.height, bar.height, 1f)
        compose.onRoot().performTouchInput { click(Offset(bar.center.x, bar.top - 24f)) }
        advance(400)
        assertEquals("Just above the bar", 1, taps)
        compose.onRoot().performTouchInput { click(Offset(bar.left + 4f, bar.top / 2)) }
        advance(400)
        assertEquals("Far from the bar: ${bar}", 2, taps)
        assertTrue(players.last().playWhenReady)
    }

    @Test fun aPageRestoredInTheBarStillShrinksItsCover() {
        saved = true
        playSong(); collapse()
        val state = android.os.Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create(state).start().postCreate(state).resume().visible()
        players.clear(); audio = CountingAudio(players)
        playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
        show(); advance(600)
        assertFalse(host.moving)
        start(); playback.audioState.value = PlaybackState.Ready; advance(300)
        val still = compose.onNodeWithTag(AUDIO_COVER_TAG).fetchSemanticsNode().boundsInRoot
        moveTo(.75f)
        val shrinking = compose.onNodeWithTag(AUDIO_COVER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("The cover heads for the bar", shrinking.width < still.width * .6f)
        moveTo(0f)
    }

    @Test fun fadedControlsCanNotBeFocusedOrPressed() {
        playSong()
        moveTo(.79f)
        val chevron = shownNode(R.string.viewer_minimize).fetchSemanticsNode().boundsInRoot.center
        moveTo(.85f)
        val placedOnly = SemanticsMatcher("placed") { it.layoutInfo.isPlaced }
        for (id in listOf(R.string.viewer_minimize, R.string.viewer_pause))
            assertTrue("TalkBack can reach a faded control", compose.onAllNodes(hasContentDescription(text(id)) and placedOnly).fetchSemanticsNodes().isEmpty())
        val at = Offset(chevron.x, chevron.y + .06f * compose.runOnIdle { host.travel })
        compose.onRoot().performTouchInput { click(at) }
        advance(600)
        assertEquals("The faded chevron took the press", .85f, compose.runOnIdle { host.progress }, .01f)
        moveTo(0f)
        shownNode(R.string.viewer_minimize).assertExists()
    }

    private fun recompositions() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
    @Test fun aDragRecomposesNothing() {
        playSong()
        val page = compose.onNodeWithTag(VIEWER_TAG)
        page.performTouchInput { down(Offset(centerX, height * .35f)); repeat(6) { moveBy(Offset(0f, 30f)) } }
        compose.waitForIdle()
        assertTrue(host.moving)
        val before = recompositions()
        val start = host.progress
        repeat(24) { page.performTouchInput { moveBy(Offset(0f, 5f)) }; compose.waitForIdle() }
        assertTrue("The page followed the finger", host.progress > start + .05f)
        assertEquals("A drag step recomposed something", before, recompositions())
        page.performTouchInput { up() }
        advance(600)
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

    /** What the Downloads list does on a tap: tells the host, then opens the file. */
    private fun tapInList(index: Int) { compose.runOnIdle { host.reopen(items[index].uri); open = true; start = index }; advance(600) }

    @Test fun tappingThePlayingSongInTheListOpensItsPlayer() {
        playSong()
        val player = players.last()
        collapse()
        tapInList(0)
        assertFalse(host.moving)
        assertTrue(placed(VIEWER_TAG))
        assertEquals("The same session, not a second one", 1, audio.opens)
        assertEquals(0, audio.closes)
        assertSame(player, players.last())
        assertTrue(player.playWhenReady)
        // Twice in a row still works: every tap counts, not only a change of file.
        collapse(); tapInList(0)
        assertTrue(placed(VIEWER_TAG))
        assertEquals(1, audio.opens)
    }

    @Test fun tappingAnotherSongOpensItInsteadOfTheBar() {
        val other = ViewerItem(Uri.parse("content://media/external/audio/media/7"), false, "audio/mp4", 100_000, isAudio = true)
        items = listOf(song, other)
        playSong()
        collapse()
        tapInList(1)
        assertFalse(host.moving)
        assertTrue(placed(VIEWER_TAG))
        assertTrue(compose.onAllNodesWithContentDescription(activity.getString(R.string.viewer_audio_n, "2", "2"))
            .fetchSemanticsNodes().any { it.layoutInfo.isPlaced })
        // The music in the bar stops, and opening the new file releases the previous item once more.
        assertEquals(2, audio.closes)
        assertEquals(2, audio.opens)
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

    /** The app killed while its music sits in the bar: it comes back on the page, full screen and paused. */
    @Test fun anAppKilledWithMusicInTheBarComesBackPausedOnThePage() {
        saved = true
        playSong(); collapse()
        assertTrue(host.collapsed)
        val state = android.os.Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        // A new process: new services and players; only the saved state is left.
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create(state).start().postCreate(state).resume().visible()
        players.clear(); audio = CountingAudio(players)
        playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
        show(); advance(600)
        assertTrue(host.open)
        assertFalse(host.moving)
        assertTrue(placed(VIEWER_TAG))
        assertTrue(compose.onAllNodesWithContentDescription(text(R.string.viewer_play)).fetchSemanticsNodes().any { it.layoutInfo.isPlaced })
        assertTrue(compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().isEmpty())
        assertEquals("Nothing plays until Play", 0, audio.opens)
    }

    @Test fun deletingTheSongInTheBarTakesTheBarAway() {
        playSong(); collapse()
        items = emptyList(); advance(300)
        assertFalse(host.open)
        assertFalse(placed(MINI_PLAYER_TAG))
        assertFalse(open)
        assertEquals(1, audio.closes)
    }

    @Test fun deletingTheSongFromALongerListTakesTheBarAway() {
        val other = ViewerItem(Uri.parse("content://media/external/audio/media/7"), false, "audio/mp4", 100_000, isAudio = true)
        items = listOf(song, other)
        playSong(); collapse()
        items = listOf(other); advance(300)
        assertFalse(host.open)
        assertFalse(placed(MINI_PLAYER_TAG))
        assertEquals(1, audio.closes)
    }

    private fun shownNode(id: Int) = compose.onNode(hasContentDescription(text(id)) and SemanticsMatcher("placed") { it.layoutInfo.isPlaced })
    @Test fun aFilterThatHidesTheSongInTheBarKeepsItPlaying() {
        val other = ViewerItem(Uri.parse("content://media/external/audio/media/7"), false, "audio/mp4", 100_000, isAudio = true)
        items = listOf(song, other); library = setOf(song.uri, other.uri)
        playSong()
        val player = players.last()
        collapse()
        items = listOf(other); advance(300)
        assertTrue(host.collapsed)
        assertTrue(placed(MINI_PLAYER_TAG))
        assertEquals(0, audio.closes)
        items = listOf(song, other); advance(300)
        assertTrue(host.collapsed)
        assertEquals("The same session", 1, audio.opens)
        assertSame(player, players.last())
        assertTrue(player.playWhenReady)
    }

    /** Down, then a slow drag down in steps, checking the page never followed it. */
    private fun steepDrag(node: SemanticsNodeInteraction) {
        node.performTouchInput { down(center) }
        repeat(12) { node.performTouchInput { moveBy(Offset(4f, 24f)) }; assertFalse("The page followed the finger", host.moving) }
        node.performTouchInput { up() }
        advance(600)
        assertFalse(host.moving)
    }

    @Test fun aSteepDragOnTheSeekBarDoesNotShrinkThePage() {
        playSong()
        steepDrag(shownNode(R.string.viewer_position))
    }

    @Config(qualifiers = "en-w832dp-h384dp")
    @Test fun scrollingTheDetailsInLandscapeDoesNotShrinkThePage() {
        playSong()
        steepDrag(shownNode(R.string.viewer_pause))
    }

    @Test fun aDragDownOnTheCoverStillShrinksThePage() {
        playSong()
        compose.onNodeWithTag(VIEWER_TAG).performTouchInput {
            down(Offset(centerX, height * .35f)); repeat(20) { moveBy(Offset(0f, 30f)) }; up()
        }
        advance(600)
        assertTrue(host.collapsed)
        assertTrue(players.last().playWhenReady)
    }
}
