package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.waitWhile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "View files" after a batch opens Saved even while a paused row keeps Downloading as the remembered tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewSavedTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Movie.mp4", title = "Movie", mimeType = "video/mp4", size = null, addedSeconds = 100, height = 360,
        bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")

    @Test fun viewFilesShowsSavedWithAPausedJob() {
        val paused = DownloadProgress("p1", "clip.mp4", DownloadPhase.DOWNLOADING, paused = true, bytes = 18, total = Size.Exact(100), hasPartial = true)
        val open = mutableStateOf(false)
        var shown = 0
        renderThemed({ compose.waitForIdle(); semanticsNodes(it, rounds = 2, idleMillis = 50) }, {
            DownloadsScreen(listOf(paused), "en", {}, {}, {}, {}, {}, null, {}, {},
                libraryLoad = LibraryLoadState.Ready(listOf(video)), openSaved = open.value, onSavedShown = { shown++; open.value = false })
        }) { activity ->
            fun all() = texts(semanticsNodes(activity, rounds = 2, idleMillis = 50).also { compose.waitForIdle() })
            waitWhile({ }) { all().none { "clip" in it } }
            // The title is wrapped in bidi isolates.
            assertFalse("starts on Downloading", all().any { "Movie" in it })
            open.value = true
            waitWhile({ }) { all().none { "Movie" in it } }
            assertTrue(all().none { "clip" in it })
            assertTrue(shown == 1)
        }
    }
}
