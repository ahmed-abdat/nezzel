package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

/** [n] videos, item i failed when [failedAt] says so; the rest saved or waiting. */
internal fun longPlaylist(n: Int, state: BatchState = BatchState.ACTIVE, title: String = "Coastal walks",
    failedAt: (Int) -> Boolean = { false }) = PlaylistBatch(BatchRecord("long", title, Preset.BALANCED, state, 0, null),
    (0 until n).map { i -> when {
        failedAt(i) -> BatchItemRecord("long", i, "i$i", "Video number $i", BatchItemState.FAILED, "job-$i", DownloadError.Network, true, false)
        i < n / 3 -> BatchItemRecord("long", i, "i$i", "Video number $i", BatchItemState.DONE, "job-$i", null, false, false)
        else -> BatchItemRecord("long", i, "i$i", "Video number $i", BatchItemState.WAITING, "job-$i", null, false, false)
    } })

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class PlaylistBatchCardTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun host(rtl: Boolean = false, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit, check: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, fontScale)) {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content() }
                }
            }
            compose.waitForIdle(); check()
        } finally { controller.pause().stop().destroy() }
    }
    private fun row(batch: PlaylistBatch, action: (PlaylistBatchAction) -> Unit = {}, onOpen: () -> Unit = {}, language: String = "en",
        check: () -> Unit) = host(rtl = language == "ar", content = { PlaylistBatchRow(batch, language, action, onOpen) }, check = check)
    private fun sheet(batch: PlaylistBatch, action: (PlaylistBatchAction) -> Unit = {}, language: String = "en", jobs: List<DownloadProgress> = emptyList(),
        focus: String? = null, dark: Boolean = false, fontScale: Float = 1f, onRetryVideo: (Int) -> Unit = {}, check: () -> Unit) =
        host(language == "ar", dark, fontScale, { Box(Modifier.fillMaxSize()) {
            PlaylistVideosContent(batch, jobs, language, action, focusJob = focus, onRetryVideo = onRetryVideo) } }, check)
    private fun word(position: Int, text: String) =
        compose.onNodeWithTag("playlist_item_$position").assert(hasText(text, substring = true))
    private val density get() = context.resources.displayMetrics.density
    private fun SemanticsNodeInteraction.heightDp() = fetchSemanticsNode().boundsInRoot.height / density

    @Test fun englishQueuedPlaylistAndSingleRowsShareConnectivityCopy() = queuedConnectivityCopy("en")
    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi")
    fun arabicQueuedPlaylistAndSingleRowsShareConnectivityCopy() = queuedConnectivityCopy("ar")

    private fun queuedConnectivityCopy(language: String) {
        val offline = mutableStateOf(true)
        val jobs = listOf(false, true).mapIndexed { i, partial ->
            DownloadProgress("queued-$i", "Queued $i.mp4", DownloadPhase.PREPARING, waiting = true, hasPartial = partial)
        }
        val batch = PlaylistBatch(playlistFixture().record, jobs.mapIndexed { i, job ->
            BatchItemRecord("list", i, "queued-$i", job.filename, BatchItemState.QUEUED, job.jobId, null, false, false)
        })
        host(rtl = language == "ar", content = {
            CompositionLocalProvider(LocalOffline provides offline.value) {
                Column {
                    jobs.forEachIndexed { i, job ->
                        JobRow(job, {}, {}, {}, Modifier.testTag("single_queued_$i"))
                    }
                    PlaylistVideosContent(batch, jobs, language, {})
                }
            }
        }) {
            fun check(offlineNow: Boolean) {
                jobs.forEachIndexed { i, job ->
                    val expected = context.getString(if (offlineNow && job.hasPartial)
                        R.string.download_waiting_network else R.string.download_waiting)
                    compose.onNodeWithTag("single_queued_$i").assert(hasAnyDescendant(hasText(expected, substring = true)))
                    word(i, expected)
                }
            }
            check(true)
            compose.runOnIdle { offline.value = false }
            check(false)
        }
    }

    // The row: flat, opens the sheet, keeps only Pause/Resume.

    @Test fun rowPausesAndOpensButHasNoMenuOrInlineList() {
        val sent = mutableListOf<PlaylistBatchAction>()
        var opened = 0
        row(playlistMixedFixture(), sent::add, { opened++ }) {
            compose.onNodeWithTag("playlist_pause").performClick()
            assertEquals(listOf(PlaylistBatchAction.PAUSE), sent); assertEquals(0, opened)
            compose.onNodeWithTag("playlist_more").assertDoesNotExist()
            compose.onNodeWithTag("playlist_stop").assertDoesNotExist()
            compose.onNodeWithText("Morning by the sea", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("playlist_header").assert(SemanticsMatcher("opens the videos") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == context.getString(R.string.playlist_show_videos)
            }).performClick()
            assertEquals(1, opened)
            assertTrue(compose.onNodeWithTag("playlist_pause").heightDp() >= 48f)
        }
    }
    @Test fun pausedRowCanResume() {
        var sent: PlaylistBatchAction? = null
        row(playlistFixture(BatchState.PAUSED), { sent = it }) {
            compose.onNodeWithText(context.getString(R.string.download_paused), substring = true).assertExists()
            compose.onNodeWithTag("playlist_resume").performClick(); assertEquals(PlaylistBatchAction.RESUME, sent)
        }
    }
    @Test fun waitingNetworkIsExplicit() = row(playlistFixture(BatchState.WAITING_NETWORK)) {
        compose.onNodeWithText(context.getString(R.string.playlist_waiting_network), substring = true).assertExists()
        compose.onNodeWithTag("playlist_resume").assertExists()
    }
    @Test fun slowedDownIsExplicit() = row(playlistFixture(BatchState.PAUSED, DownloadError.SourceRefused)) {
        compose.onNodeWithText(context.getString(R.string.playlist_slowed), substring = true).assertExists()
    }
    @Test fun finishedRowShowsOnlyTheResult() = row(playlistFixture(BatchState.COMPLETED)) {
        compose.onNodeWithText("Saved 2").assertExists()
        compose.onNodeWithTag("playlist_pause").assertDoesNotExist()
        compose.onNodeWithTag("playlist_retry").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi") fun arabicRowPointsItsChevronToTheEnd() = row(playlistMixedFixture(), language = "ar") {
        compose.onNodeWithText(context.getString(R.string.dl_batch_saved_of, "2", "8"), substring = true).assertExists()
        // RTL: the end side is the left, so the chevron sits left of the title.
        val chevron = compose.onNodeWithTag("playlist_chevron", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Coastal walks", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(chevron.right <= title.left)
    }

    // The sheet: one lazy list, failed first, actions in the header.

    @Test fun sheetListsFailedFirstThenTheRestInOrder() = sheet(playlistMixedFixture(), jobs = playlistMixedJobs) {
        compose.onNodeWithText(context.getString(R.string.playlist_failed_heading)).assertExists()
        compose.onNodeWithText(context.getString(R.string.playlist_other_videos)).assertExists()
        val top = { p: Int -> compose.onNodeWithTag("playlist_item_$p").fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top(6) < top(0)); assertTrue(top(0) < top(2)); assertTrue(top(2) < top(5))
        word(0, context.getString(R.string.download_saved)); word(0, "Morning by the sea")
        word(2, context.getString(R.string.download_transferring))
        word(3, context.getString(R.string.download_waiting))
        word(4, context.getString(R.string.playlist_item_waiting))
        word(6, context.getString(R.string.network_title)); word(6, "Fishing boats at dawn")
        // A blank title falls back to its real place in the list; the skip says why.
        compose.onNodeWithTag("playlist_sheet_list").performScrollToKey("video-7")
        word(7, context.getString(R.string.viewer_video_n, "8", "8")); word(7, context.getString(R.string.video_gone_title))
        assertTrue(compose.onNodeWithTag("playlist_item_0").heightDp() >= 56f)
    }
    @Test fun withoutJobInfoAQueuedVideoSaysQueuedNotDownloading() = sheet(playlistMixedFixture()) {
        word(2, context.getString(R.string.download_waiting))
        compose.onNodeWithText(context.getString(R.string.download_transferring)).assertDoesNotExist()
    }
    @Test fun noFailuresNoHeadings() = sheet(playlistFixture()) {
        compose.onNodeWithText(context.getString(R.string.playlist_failed_heading)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playlist_other_videos)).assertDoesNotExist()
        compose.onNodeWithTag("playlist_retry").assertDoesNotExist()
    }
    @Test fun sheetPauseAndStopRequireTheRightActions() {
        val sent = mutableListOf<PlaylistBatchAction>()
        sheet(playlistFixture(), sent::add) {
            compose.onNodeWithTag("playlist_pause").performClick()
            assertEquals(listOf(PlaylistBatchAction.PAUSE), sent)
            compose.onNodeWithTag("playlist_stop").performClick()
            assertEquals(1, sent.size)
            compose.onNodeWithText(context.getString(R.string.playlist_stop_confirm)).assertExists()
            compose.onNodeWithTag("playlist_stop_confirm").performClick()
            assertEquals(PlaylistBatchAction.STOP, sent.last())
        }
    }
    @Test fun terminalSheetOffersOnlyRetry() {
        var sent: PlaylistBatchAction? = null
        sheet(playlistFixture(BatchState.COMPLETED, failed = true), { sent = it }) {
            compose.onNodeWithTag("playlist_pause").assertDoesNotExist()
            compose.onNodeWithTag("playlist_resume").assertDoesNotExist()
            compose.onNodeWithTag("playlist_stop").assertDoesNotExist()
            word(1, context.getString(R.string.network_title))
            compose.onNodeWithTag("playlist_retry").assert(hasText(context.getString(R.string.playlist_retry, "1"))).performClick()
            assertEquals(PlaylistBatchAction.RETRY, sent)
        }
    }
    /** beta24: a finished playlist whose videos failed FormatExpired before the automatic flag existed; links still stored. */
    private fun staleFinished(hasSource: Boolean) = PlaylistBatch(BatchRecord("old", "Coastal walks", Preset.BALANCED, BatchState.COMPLETED, 0, null),
        listOf(BatchItemRecord("old", 0, "a", "Morning by the sea", BatchItemState.DONE, "job-a", null, false, false),
            BatchItemRecord("old", 1, "b", "A quiet afternoon", BatchItemState.FAILED, "job-b", DownloadError.FormatExpired, false, false, hasSource),
            BatchItemRecord("old", 2, "c", "Harbour at dusk", BatchItemState.FAILED, "job-c", DownloadError.FormatExpired, false, false, hasSource)))
    @Test fun everyFailedVideoWithItsLinkHasItsOwnRetryAndTheCountIsRight() {
        val sent = mutableListOf<Int>()
        var batch: PlaylistBatchAction? = null
        sheet(staleFinished(true), { batch = it }, onRetryVideo = { sent += it }) {
            compose.onNodeWithTag("playlist_item_retry_2", useUnmergedTree = true).assertHasClickAction().performClick()
            compose.onNodeWithTag("playlist_item_retry_1", useUnmergedTree = true).performClick()
            assertEquals(listOf(2, 1), sent)
            compose.onNodeWithTag("playlist_retry").assert(hasText(context.getString(R.string.playlist_retry, "2"))).performClick()
            assertEquals(PlaylistBatchAction.RETRY, batch)
            compose.onNodeWithText(context.getString(R.string.download_retry_hint), substring = true).assertDoesNotExist()
        }
    }
    @Test fun aFailedVideoWithoutItsLinkSaysWhyAndOffersNothing() = sheet(staleFinished(false)) {
        compose.onNodeWithTag("playlist_item_retry_1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("playlist_retry").assertDoesNotExist()
        word(1, context.getString(R.string.download_retry_hint))
    }
    @Test fun stoppedSheetHasNoButtons() = sheet(playlistFixture(BatchState.STOPPED, failed = true)) {
        listOf("playlist_pause", "playlist_resume", "playlist_stop", "playlist_retry").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }
    @Test fun allFailedHasNoOtherVideosHeading() = sheet(longPlaylist(5, BatchState.COMPLETED) { true }) {
        compose.onNodeWithText(context.getString(R.string.playlist_failed_heading)).assertExists()
        compose.onNodeWithText(context.getString(R.string.playlist_other_videos)).assertDoesNotExist()
        compose.onNodeWithTag("playlist_retry").assert(hasText(context.getString(R.string.playlist_retry, "5")))
        (0 until 5).forEach { word(it, context.getString(R.string.network_title)) }
    }
    @Test fun fiveHundredVideosComposeLazily() = sheet(longPlaylist(500) { it == 250 }) {
        compose.onNodeWithTag("playlist_item_250").assertExists()
        compose.onNodeWithTag("playlist_item_499").assertDoesNotExist()
        compose.onNodeWithTag("playlist_sheet_list").performScrollToKey("video-499")
        word(499, "Video number 499")
        compose.onNodeWithTag("playlist_item_0").assertDoesNotExist()
    }
    @Test fun focusedVideoScrollsIntoView() = sheet(longPlaylist(500), focus = "job-420") {
        compose.onNodeWithTag("playlist_item_420").assertIsDisplayed()
        compose.onNodeWithTag("playlist_item_0").assertDoesNotExist()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) fun veryLongTitleStopsAtTwoLines() = sheet(longPlaylist(3, title = "A very long playlist title ".repeat(12))) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("playlist_sheet_title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
    }
    @Test fun largeTextKeepsEveryActionAtFullSize() = sheet(playlistMixedFixture(), fontScale = 1.5f, dark = true) {
        listOf("playlist_pause", "playlist_stop", "playlist_retry").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed(); assertTrue(compose.onNodeWithTag(it).heightDp() >= 48f)
        }
        word(6, "Fishing boats at dawn")
    }
    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi") fun arabicSheetUsesArabicWords() =
        sheet(playlistMixedFixture(), language = "ar", jobs = playlistMixedJobs) {
            assertEquals("في الانتظار", context.getString(R.string.playlist_item_waiting))
            compose.onNodeWithText("لم تُحفظ").assertExists()
            compose.onNodeWithText("باقي الفيديوهات").assertExists()
            word(0, context.getString(R.string.download_saved))
            word(4, "في الانتظار")
            word(6, context.getString(R.string.network_title))
            compose.onNodeWithTag("playlist_sheet_list").performScrollToKey("video-7")
            word(7, context.getString(R.string.viewer_video_n, "8", "8"))
        }

    // Downloads: the row opens the real sheet; members still show; a Home card lands on its video.

    private fun downloads(batches: List<PlaylistBatch>, jobs: List<DownloadProgress>, openJob: String? = null, opened: () -> Unit = {}, check: () -> Unit) =
        host(content = { DownloadsScreen(jobs, "en", {}, {}, {}, {}, {}, null, {}, {}, playlists = batches,
            openPlaylistJob = openJob, onPlaylistJobOpened = opened) }, check = check)

    @Test fun runningMembersShowUnderTheRowAndQueuedOnesDoNot() = downloads(listOf(playlistFixture()),
        listOf(DownloadProgress("job-b", "Running member.mp4", DownloadPhase.DOWNLOADING),
            DownloadProgress("job-c", "Other member.mp4", DownloadPhase.PREPARING, waiting = true))) {
        compose.onNodeWithTag("tab_running").assertExists()
        compose.onNodeWithTag("playlist_batch_list").assertExists()
        // JobRow isolates the confirmed filename for RTL; the semantic text includes those marks.
        compose.onNodeWithText("Running member.mp4", substring = true).assertExists()
        compose.onNodeWithTag("playlist_sheet_list").assertDoesNotExist()
    }
    @Test fun tappingTheRowOpensTheSheet() = downloads(listOf(playlistMixedFixture()), playlistMixedJobs) {
        compose.onNodeWithTag("playlist_header").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("playlist_sheet_list").assertExists()
        word(6, context.getString(R.string.network_title))
    }
    @Test fun aHomeCardOfAFailedPlaylistVideoOpensItsSheetAtThatVideo() {
        var consumed = 0
        downloads(listOf(playlistMixedFixture(BatchState.COMPLETED)), emptyList(), openJob = "job-g", opened = { consumed++ }) {
            compose.waitForIdle()
            assertEquals(1, consumed)
            compose.onNodeWithTag("playlist_sheet_list").assertExists()
            word(6, "Fishing boats at dawn"); word(6, context.getString(R.string.network_title))
        }
    }
    @Test fun aHomeCardOfAStandaloneJobOpensNoSheet() {
        var consumed = 0
        downloads(listOf(playlistMixedFixture()), emptyList(), openJob = "elsewhere", opened = { consumed++ }) {
            assertEquals(1, consumed)
            compose.onNodeWithTag("playlist_sheet_list").assertDoesNotExist()
        }
    }
    @Test fun homeJobCardHandsItsJobIdOver() {
        val tapped = mutableListOf<String>()
        host(content = { HomeRecents(emptyList(), "en", {}, { listOf(DownloadProgress("job-g", "Fishing boats.mp4", DownloadPhase.FAILED,
            error = DownloadError.Network)) }, { tapped += it }) {} }) {
            compose.onNodeWithText("Fishing boats", substring = true).performClick()
            assertEquals(listOf("job-g"), tapped)
        }
    }
}
