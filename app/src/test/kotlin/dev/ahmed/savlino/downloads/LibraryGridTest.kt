package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class LibraryGridTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private fun s(id: Int) = activity.getString(id)
    @After fun close() { controller.pause().stop().destroy() }

    private fun media(n: Int, name: String, mime: String) = SavedMedia(Uri.parse("content://media/external/downloads/$n"), name,
        name.substringBeforeLast('.'), mime, 1000L * n, 1000L - n, null, null, if (mime.startsWith("video")) 21_000L else null)
    private val photos = (1..4).map { media(it, "Photo $it.jpg", "image/jpeg") }
    private val clip = media(5, "Clip.mp4", "video/mp4")
    private val song = media(6, "Song.m4a", "audio/mp4")

    @Test fun gridKeepsOrderInRowsOfThreeWithAudioAsTilesToo() {
        val lines = gridLines(listOf(photos[0], photos[1], song, photos[2], photos[3], clip, photos[0]))
        assertEquals(listOf(3, 3, 1), lines.map { it.items.size })
        assertEquals(listOf(photos[0], photos[1], song), lines[0].items)
        assertEquals(listOf(photos[0]), lines[2].items)
    }

    @Test fun viewIsRememberedPerFilterWithGridForImagesAndStatuses() {
        val prefs = AppPreferences(activity)
        assertTrue(prefs.libraryGrid(MediaFilter.IMAGE.name))
        assertTrue(prefs.libraryGrid(MediaFilter.STATUSES.name))
        assertFalse(prefs.libraryGrid(MediaFilter.ALL.name))
        assertFalse(prefs.libraryGrid(MediaFilter.VIDEO.name))
        LibraryView(prefs, MediaFilter.VIDEO).toggle()
        LibraryView(prefs, MediaFilter.IMAGE).toggle()
        assertTrue(AppPreferences(activity).libraryGrid(MediaFilter.VIDEO.name))
        assertFalse(AppPreferences(activity).libraryGrid(MediaFilter.IMAGE.name))
        assertFalse(AppPreferences(activity).libraryGrid(MediaFilter.ALL.name))
        assertTrue(LibraryView(prefs, MediaFilter.VIDEO).grid)
    }

    private var opened = mutableListOf<SavedMedia>()
    private fun show(items: List<SavedMedia>) {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalMediaPictures provides MediaPictures { _, _, _ -> Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }) {
                    DownloadsScreen(emptyList(), "en", {}, {}, { opened += it }, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun toggleSwitchesAllToGridAndTapOpensTheViewerOverTheFilter() {
        show(photos + clip + song)
        compose.onNodeWithContentDescription(s(R.string.library_show_grid)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(s(R.string.library_show_list)).assertExists()
        assertTrue(AppPreferences(activity).libraryGrid(MediaFilter.ALL.name))
        compose.onNode(hasContentDescription("Photo 2", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_image_n, "2", "6")).assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    @Test fun audioPlaysInTheViewerNotThePhonePlayer() {
        show(listOf(photos[0], song))
        compose.onAllNodes(hasText("Song", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_audio_n, "2", "2")).assertIsDisplayed()
        compose.onNodeWithContentDescription(s(R.string.viewer_play)).assertExists()
    }
}
