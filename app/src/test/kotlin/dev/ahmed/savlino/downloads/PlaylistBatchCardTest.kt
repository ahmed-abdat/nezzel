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

/** Every item state, in playlist order; item 2 has a running job, item 3 a queued one. */
internal fun playlistMixedFixture(state: BatchState = BatchState.ACTIVE) = PlaylistBatch(
    BatchRecord("mixed", "Coastal walks", Preset.BALANCED, state, 0, null),
    listOf(BatchItemRecord("mixed", 0, "a", "Morning by the sea", BatchItemState.DONE, "job-a", null, false, false),
        BatchItemRecord("mixed", 1, "b", "A quiet afternoon · مساء هادئ", BatchItemState.DONE, "job-b", null, false, false),
        BatchItemRecord("mixed", 2, "c", "Walking the old harbour wall", BatchItemState.QUEUED, "job-c", null, false, false),
        BatchItemRecord("mixed", 3, "d", "Harbour at dusk", BatchItemState.QUEUED, "job-d", null, false, false),
        BatchItemRecord("mixed", 4, "e", "طريق الكورنيش عند الغروب", BatchItemState.WAITING, "job-e", null, false, false),
        BatchItemRecord("mixed", 5, "f", "Night market by the lighthouse, full walk with the long title", BatchItemState.WAITING, "job-f", null, false, false),
        BatchItemRecord("mixed", 6, "g", "Fishing boats at dawn", BatchItemState.FAILED, "job-g", DownloadError.Network, true, false),
        BatchItemRecord("mixed", 7, "h", "", BatchItemState.SKIPPED, "job-h", DownloadError.Unavailable, false, false)))

internal val playlistMixedJobs = listOf(DownloadProgress("job-c", "harbour.mp4", DownloadPhase.DOWNLOADING),
    DownloadProgress("job-d", "dusk.mp4", DownloadPhase.PREPARING, waiting = true))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class PlaylistBatchCardTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun render(batch: PlaylistBatch, action: (PlaylistBatchAction) -> Unit = {}, language: String = "en",
        jobs: List<DownloadProgress> = emptyList(), check: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { PlaylistBatchCard(batch, language, action, jobs = jobs) } }
            compose.waitForIdle(); check()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun runningPauseAndStopRequireTheRightActions() {
        val sent = mutableListOf<PlaylistBatchAction>()
        render(playlistFixture(), sent::add) {
            compose.onNodeWithTag("playlist_pause").performClick()
            assertEquals(listOf(PlaylistBatchAction.PAUSE), sent)
            compose.onNodeWithTag("playlist_more").performClick()
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
            compose.onNodeWithText(context.getString(R.string.download_paused), substring = true).assertExists()
            compose.onNodeWithTag("playlist_resume").performClick(); assertEquals(PlaylistBatchAction.RESUME, sent)
        }
    }
    @Test fun waitingNetworkIsExplicit() = render(playlistFixture(BatchState.WAITING_NETWORK)) {
        compose.onNodeWithText(context.getString(R.string.playlist_waiting_network), substring = true).assertExists()
        compose.onNodeWithTag("playlist_resume").assertExists()
    }
    @Test fun slowedDownIsExplicit() = render(playlistFixture(BatchState.PAUSED, DownloadError.SourceRefused)) {
        compose.onNodeWithText(context.getString(R.string.playlist_slowed), substring = true).assertExists()
    }
    @Test fun doneListsFailuresAndRetryWiresThrough() {
        var sent: PlaylistBatchAction? = null
        render(playlistFixture(BatchState.COMPLETED, failed = true), { sent = it }) {
            compose.onNodeWithTag("playlist_pause").assertDoesNotExist()
            compose.onNodeWithTag("playlist_more").assertDoesNotExist()
            compose.onNodeWithText("A quiet afternoon", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("playlist_header").performClick()
            compose.onNodeWithText("A quiet afternoon", substring = true).assertExists()
            compose.onNodeWithText(context.getString(R.string.download_network_error)).assertExists()
            compose.onNodeWithTag("playlist_retry").performClick(); assertEquals(PlaylistBatchAction.RETRY, sent)
        }
    }
    @Test fun cleanFinishShowsOnlyTheSavedCount() = render(playlistFixture(BatchState.COMPLETED)) {
        compose.onNodeWithText("Saved 2").assertExists()
        compose.onNodeWithTag("playlist_more").assertDoesNotExist()
    }
    private fun word(position: Int, text: String) =
        compose.onNodeWithTag("playlist_item_$position").assert(hasText(text, substring = true))

    @Test fun tappingTheHeaderListsEveryVideoWithItsStateAndTappingAgainHidesThem() =
        render(playlistMixedFixture(), jobs = playlistMixedJobs) {
            compose.onNodeWithTag("playlist_items").assertDoesNotExist()
            compose.onNodeWithTag("playlist_expand").assertContentDescriptionEquals(context.getString(R.string.playlist_show_videos))
            compose.onNodeWithTag("playlist_header").performClick()
            compose.onNodeWithTag("playlist_items").assertExists()
            word(0, context.getString(R.string.download_saved)); word(0, "Morning by the sea")
            word(2, context.getString(R.string.download_transferring))
            word(3, context.getString(R.string.download_waiting))
            word(4, context.getString(R.string.playlist_item_waiting))
            word(6, context.getString(R.string.download_network_error)); word(6, "Fishing boats at dawn")
            // A blank title falls back to its real place in the list; the skip says why.
            word(7, context.getString(R.string.viewer_video_n, "8", "8")); word(7, context.getString(R.string.video_gone_title))
            compose.onNodeWithTag("playlist_expand").assertContentDescriptionEquals(context.getString(R.string.playlist_hide_videos))
            compose.onNodeWithTag("playlist_expand").performClick()
            compose.onNodeWithTag("playlist_items").assertDoesNotExist()
        }

    @Test fun withoutJobInfoAQueuedVideoSaysQueuedNotDownloading() = render(playlistMixedFixture()) {
        compose.onNodeWithTag("playlist_header").performClick()
        word(2, context.getString(R.string.download_waiting))
        compose.onNodeWithText(context.getString(R.string.download_transferring)).assertDoesNotExist()
    }

    @Test fun headerButtonsStillActWithoutOpeningTheList() {
        val sent = mutableListOf<PlaylistBatchAction>()
        render(playlistMixedFixture(), sent::add) {
            compose.onNodeWithTag("playlist_pause").performClick()
            assertEquals(listOf(PlaylistBatchAction.PAUSE), sent)
            compose.onNodeWithTag("playlist_items").assertDoesNotExist()
        }
    }

    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi") fun arabicListUsesArabicWords() =
        render(playlistMixedFixture(), language = "ar", jobs = playlistMixedJobs) {
            assertEquals("في الانتظار", context.getString(R.string.playlist_item_waiting))
            compose.onNodeWithContentDescription(context.getString(R.string.playlist_show_videos)).performClick()
            word(0, context.getString(R.string.download_saved))
            word(4, "في الانتظار")
            word(6, context.getString(R.string.download_network_error))
            word(7, context.getString(R.string.viewer_video_n, "8", "8"))
        }

    @Test fun runningMembersShowUnderTheHeaderAndQueuedOnesDoNot() {
        val batch = playlistFixture()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(listOf(DownloadProgress("job-b", "Running member.mp4", DownloadPhase.DOWNLOADING),
                    DownloadProgress("job-c", "Other member.mp4", DownloadPhase.PREPARING, waiting = true)), "en",
                    {}, {}, {}, {}, {}, null, {}, {}, playlists = listOf(batch))
            } }
            compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertExists()
            compose.onNodeWithTag("playlist_batch_list").assertExists()
            // JobRow isolates the confirmed filename for RTL; the semantic text includes those marks.
            compose.onNodeWithText("Running member.mp4", substring = true).assertExists()
            compose.onNodeWithText("Show videos").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
}
