package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** «Not saved» comes first in Downloading: its rows, the tab's error dot, Retry all, and the playlist line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class DownloadsFailuresTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private fun failed(id: String, retryable: Boolean = true) =
        DownloadProgress(id, "$id.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = retryable)
    private fun running(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.DOWNLOADING, bytes = 10, total = Size.Exact(100))
    private fun failedList(id: String, state: BatchState = BatchState.COMPLETED, retryable: Boolean = true) = PlaylistBatch(
        BatchRecord(id, "Coastal walks", Preset.BALANCED, state, 0, null),
        listOf(BatchItemRecord(id, 0, "a", "Morning by the sea", BatchItemState.DONE, "$id-a", null, false, false),
            BatchItemRecord(id, 1, "b", "A quiet afternoon", BatchItemState.FAILED, "$id-b", DownloadError.Network, retryable, false),
            BatchItemRecord(id, 2, "c", "Harbour at dusk", BatchItemState.FAILED, "$id-c", DownloadError.Network, retryable, false)))

    private fun render(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch> = emptyList(), onBatch: (BatchAction) -> Unit = {},
        onPlaylist: (String, PlaylistBatchAction) -> Unit = { _, _ -> }, check: () -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(jobs, language, {}, {}, {}, {}, {}, null, {}, {}, onBatch = onBatch, playlists = playlists, onPlaylistAction = onPlaylist)
            } }
            compose.waitForIdle(); check()
        } finally { c.pause().stop().destroy() }
    }

    @Test fun retryAllTakesOnlyRowsThatCanRetry() {
        // A failure that cannot be retried and a stopped playlist are left out; one retryable row alone gets no Retry all.
        assertEquals(FailureRetry(listOf("a", "c"), listOf("l1")),
            failureRetry(listOf(failed("a"), failed("b", retryable = false), failed("c")), listOf(failedList("l1"), failedList("l2", BatchState.STOPPED))))
        assertNull(failureRetry(listOf(failed("a"), failed("b", retryable = false)), listOf(failedList("l2", BatchState.STOPPED))))
        assertEquals(FailureRetry(emptyList(), listOf("l1", "l3")), failureRetry(emptyList(), listOf(failedList("l1"), failedList("l3"))))
    }

    @Test fun failureCountAddsStandaloneJobsAndPlaylistVideos() {
        assertEquals(0, failureCount(listOf(running("r")), emptyList()))
        // A playlist member's failed job counts once, as its playlist's video.
        assertEquals(1 + 2, failureCount(listOf(failed("a"), failed("l1-b")), listOf(failedList("l1"))))
        // A finished playlist that cannot retry its failures has left the list, so it no longer counts.
        assertEquals(0, failureCount(emptyList(), listOf(failedList("l1", retryable = false))))
    }

    @Test fun badgeShowsWhileSomethingDidNotSave() = render(listOf(running("r"), failed("a"), failed("b"))) {
        compose.onNodeWithTag("tab_attention", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tab_running").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.dl_failed_count, NezzelFormat.count(2, language))))
    }

    @Test fun badgeHiddenWithoutFailures() = render(listOf(running("r"), running("s"))) {
        compose.onNodeWithTag("tab_attention", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tab_running").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun notSavedComesBeforeRunningAndRetryAllSendsOnlyRetryable() {
        var batch: BatchAction? = null
        val playlists = mutableListOf<Pair<String, PlaylistBatchAction>>()
        render(listOf(running("r"), failed("a"), failed("b", retryable = false)), listOf(failedList("l1"), failedList("l2", BatchState.STOPPED)),
            { batch = it }, { id, action -> playlists += id to action }) {
            compose.onNodeWithTag("tab_running").assertIsSelected()
            val header = compose.onNodeWithText(context.getString(R.string.dl_failed_header)).fetchSemanticsNode().boundsInRoot
            val runningHeader = compose.onNodeWithText(context.getString(R.string.downloads_running_header, NezzelFormat.count(1, language)))
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Not saved above running: $header / $runningHeader", header.bottom <= runningHeader.top)
            compose.onNodeWithTag("playlist_failed_l1").assertExists()
            compose.onNodeWithTag("playlist_failed_l2").assertExists()
            compose.onNodeWithTag("batch_action").performClick()
            assertEquals(BatchAction(BatchKind.RETRY, listOf("a")), batch)
            assertEquals(listOf("l1" to PlaylistBatchAction.RETRY), playlists)
        }
    }

    @Test fun playlistLineRetriesAndOpensItsSheet() {
        val sent = mutableListOf<Pair<String, PlaylistBatchAction>>()
        render(emptyList(), listOf(failedList("l1")), onPlaylist = { id, action -> sent += id to action }) {
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithText(NezzelFormat.isolate("Coastal walks")).assertExists()
            compose.onNodeWithText(context.getString(R.string.dl_playlist_failed, NezzelFormat.count(2, language), NezzelFormat.count(3, language)),
                substring = true).assertExists()
            compose.onNodeWithTag("playlist_failed_retry").performClick()
            assertEquals(listOf("l1" to PlaylistBatchAction.RETRY), sent)
            compose.onNodeWithTag("playlist_sheet_list").assertDoesNotExist()
            compose.onNodeWithTag("playlist_failed_l1").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("playlist_sheet_list").assertExists()
            // The sheet's failed videos come first, right under its header.
            compose.onNodeWithTag("playlist_item_1").assertExists()
        }
    }

    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicLineMirrorsRetryToTheEnd() =
        render(listOf(failed("a")), listOf(failedList("l1"))) {
            compose.onNodeWithText(context.getString(R.string.dl_failed_header)).assertExists()
            val line = compose.onNodeWithTag("playlist_failed_l1").fetchSemanticsNode().boundsInRoot
            val retry = compose.onNodeWithTag("playlist_failed_retry").fetchSemanticsNode().boundsInRoot
            // RTL: the end side is the left edge, and Retry keeps its 48dp target inside the row.
            assertTrue("retry at the end: $retry in $line", retry.center.x < line.center.x && retry.left >= line.left)
            assertTrue("48dp: $retry", retry.height / context.resources.displayMetrics.density >= 47.9f)
        }
}
