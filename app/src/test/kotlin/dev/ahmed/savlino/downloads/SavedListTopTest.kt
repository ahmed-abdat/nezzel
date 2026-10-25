package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saved list: items appearing above the first row (old-downloads card, load status) never leave it cut off at the top. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h800dp-mdpi")
class SavedListTopTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private fun file(n: Int) = SavedMedia(Uri.parse("content://media/external_primary/downloads/$n"), "File $n.${if (n % 2 == 0) "m4a" else "mp4"}", "File $n", if (n % 2 == 0) "audio/mp4" else "video/mp4",
        1_000_000, 1_761_300_000L - n * 100, if (n % 2 == 0) null else 1080, null, 31_000, width = if (n % 2 == 0) null else 1920, relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private fun offer(card: Boolean) = OldDownloadsOffer(card = card, canGrant = card, show = {}, dismiss = {})
    private fun running(id: String, phase: DownloadPhase = DownloadPhase.DOWNLOADING) = DownloadProgress(id, "$id.mp4", phase)

    private fun render(content: @Composable () -> Unit, check: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { content() } }
            compose.waitForIdle(); check()
        } finally { controller.pause().stop().destroy() }
    }
    /** The card sits fully inside the list's top edge (not hidden above it, not cut off). */
    private fun assertCardAtTop() {
        compose.onNodeWithTag("old_downloads_card").assertIsDisplayed()
        val list = compose.onNodeWithTag("saved_list").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("old_downloads_card").fetchSemanticsNode().boundsInRoot
        assertTrue("card ${card.top} must start at/below list top ${list.top}", card.top >= list.top)
        assertTrue("card is cut off: ${card.height}px tall", card.height >= 60f)
    }

    @Test fun cardAppearingOverAListAtItsTopShowsInFull() {
        val card = mutableStateOf(false)
        render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = LibraryLoadState.Ready((1..30).map(::file)), oldDownloads = offer(card.value)) }) {
            compose.onNodeWithTag("old_downloads_card").assertDoesNotExist()
            card.value = true
            compose.waitForIdle()
            assertCardAtTop()
        }
    }

    @Test fun cardAppearingWhileDownloadingShowsInFullWhenTheTabsFoldAway() {
        val card = mutableStateOf(false)
        val jobs = mutableStateOf(listOf(running("a")))
        val rows = mutableStateOf((1..30).map(::file))
        render({ DownloadsScreen(jobs.value, "en", {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = LibraryLoadState.Ready(rows.value), oldDownloads = offer(card.value)) }) {
            compose.onNodeWithTag("tab_saved").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("saved_list").assertExists()
            compose.onNodeWithTag("tab_running").performClick()
            card.value = true
            rows.value = listOf(file(0)) + rows.value
            jobs.value = listOf(running("a", DownloadPhase.COMPLETED))
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(dev.ahmed.savlino.ui.theme.Motion.SAVED_DWELL + 1000); compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertDoesNotExist()
            assertCardAtTop()
        }
    }

    @Test fun loadStatusFlickeringAboveTheCardLeavesItInFull() {
        val load = mutableStateOf<LibraryLoadState>(LibraryLoadState.Ready((1..30).map(::file)))
        render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = load.value, oldDownloads = offer(true)) }) {
            assertCardAtTop()
            load.value = LibraryLoadState.Loading((1..30).map(::file)); compose.waitForIdle()
            load.value = LibraryLoadState.Ready((0..30).map(::file)); compose.waitForIdle()
            assertCardAtTop()
        }
    }

    @Test fun downloadFinishingWithLibraryRefreshShowsTheCardInFull() {
        val load = mutableStateOf<LibraryLoadState>(LibraryLoadState.Ready((1..30).map(::file)))
        val jobs = mutableStateOf(listOf(running("a")))
        render({ DownloadsScreen(jobs.value, "en", {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = load.value, oldDownloads = offer(true)) }) {
            compose.onNodeWithTag("tab_running").assertIsSelected()
            jobs.value = listOf(running("a", DownloadPhase.COMPLETED)); compose.waitForIdle()
            load.value = LibraryLoadState.Loading((1..30).map(::file)); compose.waitForIdle()
            compose.mainClock.advanceTimeBy(dev.ahmed.savlino.ui.theme.Motion.SAVED_DWELL + 1000); compose.waitForIdle()
            load.value = LibraryLoadState.Ready((0..30).map(::file)); compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertDoesNotExist()
            assertCardAtTop()
        }
    }

    @Test fun aListScrolledDownByThePersonStaysWhereItIs() {
        val card = mutableStateOf(false)
        render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = LibraryLoadState.Ready((1..30).map(::file)), oldDownloads = offer(card.value)) }) {
            compose.onNodeWithTag("saved_list").performScrollToIndex(8)
            compose.waitForIdle()
            card.value = true
            compose.waitForIdle()
            compose.onNodeWithTag("old_downloads_card").assertIsNotDisplayed()
            compose.onNodeWithText("File 9", substring = true).assertIsDisplayed()
        }
    }
}
