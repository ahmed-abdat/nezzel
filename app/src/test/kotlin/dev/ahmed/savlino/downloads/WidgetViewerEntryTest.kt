package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h832dp-mdpi")
class WidgetViewerEntryTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val item = SavedMedia(Uri.parse("content://media/external/downloads/19"), "Photo.jpg", "Photo", "image/jpeg",
        100, 100, null, null, null)

    @Test fun waitsForLibraryThenOpensTheRequestedFileOnce() {
        var load by mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading())
        var request by mutableStateOf<String?>(item.uri.toString())
        var consumed = 0
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = load,
                    openSavedUri = request, onSavedOpened = { consumed++; request = null })
            } }
            compose.waitForIdle()
            assertEquals(0, consumed)
            load = LibraryLoadState.Ready(listOf(item)); compose.waitForIdle()
            assertEquals(1, consumed)
            compose.onNodeWithContentDescription(c.get().getString(R.string.viewer_close)).assertExists()
            load = LibraryLoadState.Ready(listOf(item.copy(size = 101))); compose.waitForIdle()
            assertEquals(1, consumed)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun deletedUriLeavesDownloadsOpenWithoutOpeningAnotherFile() {
        var consumed = 0
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
                    libraryLoad = LibraryLoadState.Ready(listOf(item)), openSavedUri = "content://media/external/downloads/20",
                    onSavedOpened = { consumed++ })
            } }
            compose.waitForIdle()
            assertEquals(1, consumed)
            compose.onNodeWithContentDescription(c.get().getString(R.string.viewer_close)).assertDoesNotExist()
        } finally { c.pause().stop().destroy() }
    }
}
