package dev.ahmed.savlino.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.AudioConnection
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlaybackState
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.theme.NezzelTheme
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
import java.time.Duration

/** The provider's display name, the only text the viewer reads about a file. */
class DisplayNames : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    companion object { var name: String? = null }
}

/** Stands in for the audio service: hands the viewer a ready test player, or fails like a refused connection. */
internal class TestAudio(private val players: MutableList<ViewerTestPlayer> = mutableListOf()) : AudioConnection {
    var opens = 0
    var fail = false
    override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) {
        opens++
        if (fail) onFailure()
        else onPlayer(ViewerTestPlayer().also { it.setMediaItem(MediaItem.fromUri(item.uri)); it.prepare(); players += it })
    }
    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w384dp-h832dp")
class ViewerAudioTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val activity get() = controller.get()
    private val players = mutableListOf<ViewerTestPlayer>()
    private val audio = TestAudio(players)
    private lateinit var playback: PlaybackEnvironment
    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 100_000, isAudio = true)

    @After fun close() { if (::controller.isInitialized) controller.pause().stop().destroy() }

    private fun show(language: String = "en", qualifiers: String = "") {
        RuntimeEnvironment.setQualifiers("+$language$qualifiers")
        Robolectric.setupContentProvider(DisplayNames::class.java, "media")
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
        activity.setContent {
            NezzelTheme(ThemePref.Dark, false) {
                CompositionLocalProvider(LocalMediaPictures provides MediaPictures { _, _, _ -> null }, LocalPlaybackEnvironment provides playback,
                    LocalAudioConnection provides { audio }) {
                    MediaViewer(listOf(song), 0, language, {}, onShare = {}, onOpenWith = {})
                }
            }
        }
        compose.waitForIdle()
    }
    private fun text(id: Int) = activity.getString(id)
    private fun page() = compose.onNodeWithContentDescription(activity.getString(R.string.viewer_audio_n, "1", "1"))
    private fun node(id: Int) = compose.onNodeWithContentDescription(text(id))
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); compose.mainClock.advanceTimeBy(millis); compose.waitForIdle()
    }
    private fun playing() {
        if (compose.onAllNodesWithContentDescription(text(R.string.viewer_pause)).fetchSemanticsNodes().isEmpty()) node(R.string.viewer_play).performClick()
        playback.audioState.value = PlaybackState.Ready
        advance(300)
    }
    private fun title(name: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }

    @Test fun titleIsTheFileNameWithoutExtensionAndControlsStayPut() {
        DisplayNames.name = "Morning walk.final.m4a"
        show()
        title("Morning walk.final")
        compose.onNodeWithText("Morning walk.final.m4a").assertDoesNotExist()
        playing()
        assertTrue(players.last().isPlaying)
        advance(ViewerControls.HIDE_AFTER * 3)
        // A tap on the page doesn't hide them either: there is no picture to uncover.
        page().performTouchInput { click(center) }; advance(400)
        listOf(R.string.viewer_pause, R.string.viewer_seek_back_10, R.string.viewer_seek_forward_10, R.string.viewer_speed,
            R.string.viewer_volume, R.string.viewer_close).forEach { node(it).assertIsDisplayed() }
        node(R.string.viewer_seek_forward_10).performClick(); advance(300)
        assertEquals(10_000, players.last().currentPosition)
        node(R.string.viewer_pause).performClick(); advance(300)
        assertFalse(players.last().playWhenReady)
    }

    @Test fun unnamedFileUsesThePlainAudioTitle() {
        DisplayNames.name = null
        show()
        title(text(R.string.playback_audio))
    }

    private fun landscape(language: String) {
        DisplayNames.name = "Song.mp3"
        show(language, "-w832dp-h384dp-land")
        title("Song"); playing()
        val play = node(R.string.viewer_pause).fetchSemanticsNode().boundsInRoot
        val width = page().fetchSemanticsNode().boundsInRoot.width
        // The cover takes the start half, so the controls sit in the end half: right in English, left in Arabic.
        if (language == "en") assertTrue(play.left > width / 2) else assertTrue(play.right < width / 2)
    }
    @Test fun landscapePutsTheCoverAtStartAndControlsAtEnd() = landscape("en")
    @Test fun landscapeMirrorsInArabic() = landscape("ar")

    @Test fun failureIsAReadableCardWithFullSizeActions() {
        DisplayNames.name = "Song.mp3"
        audio.fail = true
        show()
        if (compose.onAllNodesWithText(text(R.string.viewer_retry)).fetchSemanticsNodes().isEmpty()) node(R.string.viewer_play).performClick()
        advance(300)
        compose.onNodeWithText(text(R.string.viewer_playback_failed)).assertIsDisplayed()
        for (id in listOf(R.string.viewer_retry, R.string.saved_open_with, R.string.viewer_close))
            compose.onNodeWithText(text(id)).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        node(R.string.viewer_pause).assertDoesNotExist()
        val before = audio.opens
        audio.fail = false
        compose.onNodeWithText(text(R.string.viewer_retry)).performClick(); advance(300)
        assertEquals(before + 1, audio.opens)
        playback.audioState.value = PlaybackState.Ready; advance(300)
        compose.onNodeWithText(text(R.string.viewer_retry)).assertDoesNotExist()
    }
}
