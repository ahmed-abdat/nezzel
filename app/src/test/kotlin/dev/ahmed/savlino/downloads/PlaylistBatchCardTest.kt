package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal fun playlistFixture(state: BatchState = BatchState.ACTIVE, error: DownloadError? = null, failed: Boolean = false) = PlaylistBatch(
    BatchRecord("list", "Coastal walks", Preset.BALANCED, state, 0, error),
    listOf(BatchItemRecord("list", 0, "a", "Morning by the sea", BatchItemState.DONE, "job-a", null, false, false),
        BatchItemRecord("list", 1, "b", "A quiet afternoon", if (failed) BatchItemState.FAILED else if (state == BatchState.COMPLETED) BatchItemState.DONE else BatchItemState.QUEUED,
            "job-b", if (failed) DownloadError.Network else null, failed, false)))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class PlaylistBatchCardTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun render(batch: PlaylistBatch, action: (PlaylistBatchAction) -> Unit = {}, check: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { PlaylistBatchCard(batch, "en", false, {}, action) } }
            compose.waitForIdle(); check()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun runningPauseAndStopRequireTheRightActions() {
        val sent = mutableListOf<PlaylistBatchAction>()
        render(playlistFixture(), sent::add) {
            compose.onNodeWithTag("playlist_pause").performClick()
            assertEquals(listOf(PlaylistBatchAction.PAUSE), sent)
            compose.onNodeWithTag("playlist_stop").performClick()
            assertEquals(1, sent.size)
            compose.onNodeWithText(context.getString(R.string.playlist_stop_confirm)).assertExists()
            compose.onNodeWithTag("playlist_stop_confirm").performClick()
            assertEquals(PlaylistBatchAction.STOP, sent.last())
        }
    }
    @Test fun pausedCanResume() {
        var sent: PlaylistBatchAction? = null
        render(playlistFixture(BatchState.PAUSED), { sent = it }) {
            compose.onNodeWithText(context.getString(R.string.download_paused)).assertExists()
            compose.onNodeWithTag("playlist_resume").performClick(); assertEquals(PlaylistBatchAction.RESUME, sent)
        }
    }
    @Test fun waitingNetworkIsExplicit() = render(playlistFixture(BatchState.WAITING_NETWORK)) {
        compose.onNodeWithText(context.getString(R.string.playlist_waiting_network)).assertExists()
        compose.onNodeWithTag("playlist_resume").assertExists()
    }
    @Test fun slowedDownIsExplicit() = render(playlistFixture(BatchState.PAUSED, DownloadError.SourceRefused)) {
        compose.onNodeWithText(context.getString(R.string.playlist_slowed)).assertExists()
    }
    @Test fun doneListsFailuresAndRetryWiresThrough() {
        var sent: PlaylistBatchAction? = null
        render(playlistFixture(BatchState.COMPLETED, failed = true), { sent = it }) {
            compose.onNodeWithTag("playlist_pause").assertDoesNotExist()
            compose.onNodeWithTag("playlist_why").performClick()
            compose.onNodeWithText("A quiet afternoon", substring = true).assertExists()
            compose.onNodeWithTag("playlist_retry").performClick(); assertEquals(PlaylistBatchAction.RETRY, sent)
        }
    }
    @Test fun cleanFinishShowsOnlyTheSavedCount() = render(playlistFixture(BatchState.COMPLETED)) {
        compose.onNodeWithText("Done: 2 saved").assertExists()
        compose.onNodeWithTag("playlist_why").assertDoesNotExist()
    }
    @Test fun memberJobsStayCollapsedUntilShowVideos() {
        val batch = playlistFixture()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(listOf(DownloadProgress("job-b", "Hidden member.mp4", DownloadPhase.DOWNLOADING)), "en",
                    {}, {}, {}, {}, {}, null, {}, {}, playlists = listOf(batch))
            } }
            compose.waitForIdle()
            // JobRow isolates the confirmed filename for RTL; the semantic text includes those marks.
            compose.onNodeWithText("Hidden member.mp4", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("playlist_expand").performClick()
            compose.onNodeWithText("Hidden member.mp4", substring = true).assertExists()
            compose.onNodeWithTag("playlist_expand").performClick()
            compose.onNodeWithText("Hidden member.mp4", substring = true).assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
}
