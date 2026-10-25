package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Downloading · Saved: when the tabs show, which one opens, when the screen moves, and how batches leave. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class DownloadsTabsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun job(id: String, phase: DownloadPhase = DownloadPhase.DOWNLOADING, waiting: Boolean = false, paused: Boolean = false,
        awaitingSchedule: Boolean = false, retryable: Boolean = false) =
        DownloadProgress(id, "$id.mp4", phase, waiting = waiting, paused = paused, awaitingSchedule = awaitingSchedule, retryable = retryable)

    @Test fun entryOpensDownloadingWhileSomethingIsActiveOrNotSaved() {
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a")), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", waiting = true)), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", paused = true)), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", DownloadPhase.FAILED, awaitingSchedule = true)), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(emptyList(), listOf(playlistFixture())))
        // Something that did not save opens Downloading, where its reason and Retry are; finished work alone opens Saved.
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", DownloadPhase.FAILED, retryable = true)), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", DownloadPhase.FAILED)), emptyList()))
        assertEquals(DownloadsTab.RUNNING, entryTab(listOf(job("a", DownloadPhase.COMPLETED)), listOf(playlistFixture(BatchState.COMPLETED, failed = true))))
        assertEquals(DownloadsTab.SAVED, entryTab(listOf(job("a", DownloadPhase.COMPLETED)), listOf(playlistFixture(BatchState.COMPLETED))))
        assertEquals(DownloadsTab.SAVED, entryTab(emptyList(), emptyList()))
    }

    @Test fun switchRules() {
        assertEquals(DownloadsTab.RUNNING, nextDownloadsTab(DownloadsTab.RUNNING, newActive = false))
        assertEquals(DownloadsTab.RUNNING, nextDownloadsTab(DownloadsTab.RUNNING, newActive = true))
        assertEquals(DownloadsTab.RUNNING, nextDownloadsTab(DownloadsTab.SAVED, newActive = true))
        assertEquals(DownloadsTab.SAVED, nextDownloadsTab(DownloadsTab.SAVED, newActive = false))
        assertEquals(DownloadsTab.RUNNING, nextDownloadsTab(DownloadsTab.RUNNING, newActive = false))
    }

    @Test fun batchRowsCountsAndMembers() {
        assertTrue(playlistFixture().keepsRow())
        assertFalse(playlistFixture(BatchState.COMPLETED).keepsRow())
        assertTrue(playlistFixture(BatchState.COMPLETED, failed = true).keepsRow())
        assertFalse(playlistFixture(BatchState.STOPPED, failed = true).let { b -> PlaylistBatch(b.record, b.items.map { i ->
            BatchItemRecord(i.batchId, i.position, i.itemId, i.title, i.state, i.jobId, i.error, false, i.fallback) }) }.keepsRow())
        val batch = playlistFixture()
        assertEquals(1 + 1, downloadingCount(listOf(job("x")), listOf(batch)))
        assertEquals(listOf("job-b"), batchMemberRows(batch, listOf(job("job-b"), job("job-a", DownloadPhase.COMPLETED), job("x"))).map { it.jobId })
        assertTrue(batchMemberRows(batch, listOf(job("job-b", waiting = true))).isEmpty())
        assertEquals(listOf("job-b"), batchMemberRows(batch, listOf(job("job-b", paused = true))).map { it.jobId })
    }

    private fun render(content: @Composable () -> Unit, capture: Boolean = false, check: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { content() } }
            compose.waitForIdle()
            if (capture) System.getenv("NEZZEL_TABS_RENDER_DIR")?.let { directory ->
                compose.runOnIdle {
                    val root = controller.get().window.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(directory).mkdirs()
                    java.io.File(directory, "${context.resources.configuration.locales[0].language}.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            check()
        } finally { controller.pause().stop().destroy() }
    }
    private fun renamed(b: PlaylistBatch) = PlaylistBatch(b.record.let { BatchRecord("bad", "Bad list", it.preset, it.state, it.createdAt, it.error) },
        b.items.map { BatchItemRecord("bad", it.position, it.itemId, it.title, it.state, "bad-${it.jobId}", it.error, it.retryable, it.fallback) })
    /** Effects run on the rule's test clock: a dwell passes only when the clock moves. */
    private fun pass(millis: Long) {
        compose.mainClock.advanceTimeBy(millis); compose.waitForIdle()
    }
    private val savedTab get() = compose.onNodeWithTag("tab_saved").assertIsSelected()

    @Test fun lastJobFinishingFoldsTheTabsAwayAndShowsSaved() = finishingLayout()

    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi", fontScale = 2f)
    fun lastJobFinishingFoldsTheTabsAwayInArabicAtLargeText() = finishingLayout()

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xxhdpi")
    fun downloadingLabelAndCountFitAtDoubleFont() = largeTabs("en")

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "ar-w360dp-h800dp-xxhdpi")
    fun arabicDownloadingLabelAndCountFitAtDoubleFont() = largeTabs("ar")

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [35], qualifiers = "fr-w360dp-h800dp-xxhdpi")
    fun frenchDownloadingLabelAndCountFitAtDoubleFont() = largeTabs("fr")

    private fun largeTabs(language: String) {
        val count = mutableIntStateOf(12)
        render({
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, 2f),
                androidx.compose.ui.platform.LocalLayoutDirection provides if (language == "ar")
                    androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) { DownloadsTabRow(DownloadsTab.RUNNING, count.intValue, true, language, failed = 1) {} }
        }, capture = true) {
            for (value in listOf(12, 0)) {
                count.intValue = value
                compose.waitForIdle()
                val tab = compose.onNodeWithTag("tab_running").fetchSemanticsNode().boundsInRoot
                val label = compose.onNodeWithTag("tab_running_label", useUnmergedTree = true).fetchSemanticsNode()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                label.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
                val layout = layouts.single()
                assertFalse("Full Downloading label must fit: size=${layout.size}, lines=${layout.lineCount}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}", layout.didOverflowHeight)
                for (offset in layout.layoutInput.text.indices) {
                    val glyph = layout.getBoundingBox(offset).translate(label.positionInRoot)
                    assertTrue("Downloading glyph must stay inside its tab: $glyph / $tab",
                        glyph.left >= tab.left - 1f && glyph.right <= tab.right + 1f && glyph.top >= tab.top - 1f && glyph.bottom <= tab.bottom + 1f)
                }
                if (value > 0) {
                    val badge = compose.onNodeWithText("12", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    badge.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(results)
                    val countLayout = results.single()
                    assertFalse("Count must not clip: size=${countLayout.size}, width=${countLayout.didOverflowWidth}, height=${countLayout.didOverflowHeight}", countLayout.didOverflowHeight)
                    for (offset in countLayout.layoutInput.text.indices) {
                        val glyph = countLayout.getBoundingBox(offset).translate(badge.positionInRoot)
                        assertTrue("Count glyph must stay inside its tab: $glyph / $tab",
                            glyph.left >= tab.left - 1f && glyph.right <= tab.right + 1f && glyph.top >= tab.top - 1f && glyph.bottom <= tab.bottom + 1f)
                    }
                    val labelBounds = label.boundsInRoot
                    val badgeBounds = badge.boundsInRoot
                    assertTrue("Label and count must not overlap", labelBounds.right <= badgeBounds.left || badgeBounds.right <= labelBounds.left)
                }
            }
        }
    }

    private fun finishingLayout() {
        val jobs = mutableStateOf(listOf(job("a")))
        render({ DownloadsScreen(jobs.value, "en", {}, {}, {}, {}, {}, null, {}, {}) }) {
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithTag("running_list").assertExists()
            jobs.value = listOf(job("a", DownloadPhase.COMPLETED))
            compose.waitForIdle()
            // The "Saved" moment shows in Downloading first.
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithText("a.mp4", substring = true).assertExists()
            pass(Motion.SAVED_DWELL + 1000)
            // Nothing left to download: no tabs, the saved files are the screen.
            compose.onNodeWithTag("tab_running").assertDoesNotExist()
            compose.onNodeWithTag("saved_list").assertExists()
            // A new job brings the tabs back on Downloading.
            jobs.value = listOf(job("a", DownloadPhase.COMPLETED), job("b"))
            compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithTag("running_list").assertExists()
        }
    }

    @Test fun noJobOpensTheSavedFilesWithoutTabs() = render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}) }) {
        compose.onNodeWithTag("tab_running").assertDoesNotExist()
        compose.onNodeWithTag("tab_saved").assertDoesNotExist()
        compose.onNodeWithTag("saved_list").assertExists()
    }

    @Test fun aFailedJobOpensDownloadingUnderNotSaved() = render({
        DownloadsScreen(listOf(job("a", DownloadPhase.FAILED, retryable = true)), "en", {}, {}, {}, {}, {}, null, {}, {})
    }) {
        compose.onNodeWithTag("tab_running").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.dl_failed_header)).assertExists()
    }

    @Test fun oneDownloadAloneHasNoHeaderButTwoDo() {
        render({ DownloadsScreen(listOf(job("a")), "en", {}, {}, {}, {}, {}, null, {}, {}) }) {
            compose.onNodeWithText("In progress", substring = true).assertDoesNotExist()
            compose.onAllNodesWithText("a.mp4", substring = true, useUnmergedTree = true).onFirst().assertExists()
        }
        render({ DownloadsScreen(listOf(job("a"), job("b")), "en", {}, {}, {}, {}, {}, null, {}, {}) }) {
            compose.onNodeWithText("In progress", substring = true).assertExists()
        }
    }

    @Test fun aJobStartingWhileOnSavedMovesToDownloading() {
        val jobs = mutableStateOf(listOf(job("a", DownloadPhase.FAILED, retryable = true)))
        render({ DownloadsScreen(jobs.value, "en", {}, {}, {}, {}, {}, null, {}, {}) }) {
            compose.onNodeWithTag("tab_saved").performClick()
            compose.onNodeWithTag("tab_saved").assertIsSelected()
            jobs.value = jobs.value + job("b", waiting = true)
            compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithTag("running_list").assertExists()
            // A manual choice holds while nothing new starts.
            compose.onNodeWithTag("tab_saved").performClick()
            jobs.value = jobs.value.map { if (it.jobId == "b") it.copy(waiting = false) else it }
            compose.waitForIdle()
            compose.onNodeWithTag("tab_saved").assertIsSelected()
        }
    }

    @Test fun aPlaylistStartingWithNoJobYetOpensDownloading() {
        val batches = mutableStateOf(emptyList<PlaylistBatch>())
        render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, playlists = batches.value) }) {
            compose.onNodeWithTag("tab_saved").assertDoesNotExist()
            batches.value = listOf(playlistFixture())
            compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithTag("playlist_batch_list").assertExists()
        }
    }

    @Test fun cleanBatchSaysSavedThenLeavesButOneWithFailuresStays() {
        val batches = mutableStateOf(listOf(playlistFixture(), renamed(playlistFixture())))
        render({ DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, playlists = batches.value) }) {
            compose.onNodeWithTag("playlist_batch_list").assertExists()
            batches.value = listOf(playlistFixture(BatchState.COMPLETED),
                renamed(playlistFixture(BatchState.COMPLETED, failed = true)))
            compose.waitForIdle()
            compose.onNodeWithText("Saved 2").assertExists()
            pass(BATCH_DWELL + 1000)
            compose.onNodeWithTag("playlist_batch_list").assertDoesNotExist()
            // A finished playlist with failures stays as its «Not saved» line only; the line opens its sheet.
            compose.onNodeWithTag("playlist_batch_bad").assertDoesNotExist()
            compose.onNodeWithTag("playlist_failed_bad").assertExists()
            compose.onNodeWithTag("playlist_retry").assertDoesNotExist()
            compose.onNodeWithTag("playlist_failed_bad").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("playlist_retry").assertExists()
        }
    }

    @Test fun cleanBatchAlreadyFinishedOnEntryIsNotShown() = render({
        DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, playlists = listOf(playlistFixture(BatchState.COMPLETED)))
    }) { compose.onNodeWithTag("tab_saved").assertDoesNotExist(); compose.onNodeWithTag("playlist_batch_list").assertDoesNotExist() }

    @Test fun twoOrMoreWaitingRowsFoldIntoOneLine() = render({
        DownloadsScreen(listOf(job("run"), job("w1", waiting = true), job("w2", waiting = true)), "en", {}, {}, {}, {}, {}, null, {}, {})
    }) {
        compose.onNodeWithText("run.mp4", substring = true).assertExists()
        compose.onNodeWithText("w1.mp4", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.dl_waiting_count, "2")).assertExists()
        compose.onNodeWithTag("waiting_line").performClick()
        compose.onNodeWithText("w1.mp4", substring = true).assertExists()
        compose.onNodeWithText("w2.mp4", substring = true).assertExists()
    }
}
