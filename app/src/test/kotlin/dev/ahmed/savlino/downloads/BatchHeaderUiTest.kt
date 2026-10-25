package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
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
import org.robolectric.annotation.GraphicsMode

/** The Downloads header's one batch button: when it shows, its TalkBack name with the count, its target, and what it sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchHeaderUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private fun running(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.DOWNLOADING, bytes = 10, total = Size.Exact(100))
    private fun paused(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.PREPARING, paused = true, hasPartial = true)
    private fun failed(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = true)
    private fun label(action: Int, count: Int) = context.getString(R.string.downloads_batch_accessibility, context.getString(action),
        context.resources.getQuantityString(R.plurals.downloads_count, count, NezzelFormat.count(count, language)))

    private fun render(jobs: List<DownloadProgress>, onBatch: (BatchAction) -> Unit = {}, busy: Boolean = false, check: () -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(jobs, language, {}, {}, {}, {}, {}, null, {}, {}, onBatch = onBatch, batchBusy = busy)
            } }
            compose.waitForIdle(); check()
        } finally { c.pause().stop().destroy() }
    }
    private fun batchButtons() = compose.onAllNodesWithTag("batch_action").fetchSemanticsNodes()

    private fun pauseAllNamesTheCountAndSendsDisplayOrder() {
        var sent: BatchAction? = null
        render(listOf(running("a"), running("b"), running("c")), { sent = it }) {
            val node = compose.onNodeWithContentDescription(label(R.string.downloads_pause_all, 3))
            node.assertHasClickAction()
            val density = context.resources.displayMetrics.density
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("48dp target: $bounds", bounds.height / density >= 47.9f)
            assertTrue("inside 320dp: $bounds", bounds.left >= -.5f && bounds.right <= context.resources.displayMetrics.widthPixels + .5f)
            node.performClick()
            assertEquals(BatchAction(BatchKind.PAUSE, listOf("a", "b", "c")), sent)
        }
    }
    @Test fun pauseAllWithThreeRunning() = pauseAllNamesTheCountAndSendsDisplayOrder()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun pauseAllWithThreeRunningArabic() = pauseAllNamesTheCountAndSendsDisplayOrder()
    @Test @Config(qualifiers = "fr-w320dp-h1600dp-mdpi") fun pauseAllWithThreeRunningFrench() = pauseAllNamesTheCountAndSendsDisplayOrder()
    @Test fun pauseAllAtLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        pauseAllNamesTheCountAndSendsDisplayOrder()
    }

    @Test fun busyBatchDisablesTheButton() {
        var sent = 0
        render(listOf(running("a"), running("b")), { sent++ }, busy = true) {
            compose.onNodeWithContentDescription(label(R.string.downloads_pause_all, 2)).assertIsNotEnabled().performClick()
            assertEquals(0, sent)
        }
    }

    @Test fun oneCandidateShowsNoBatchButton() = render(listOf(running("a"), paused("p"), failed("f"))) {
        assertTrue(batchButtons().isEmpty())
    }

    @Test fun twoPausedShowResumeAll() {
        var sent: BatchAction? = null
        render(listOf(paused("p1"), running("a"), paused("p2")), { sent = it }) {
            compose.onNodeWithContentDescription(label(R.string.downloads_resume_all, 2)).performClick()
            assertEquals(BatchKind.RESUME, sent!!.kind)
            assertEquals(setOf("p1", "p2"), sent!!.ids.toSet())
        }
    }

    @Test fun threeFailedShowRetryAllUnderNotSaved() {
        var sent: BatchAction? = null
        render(listOf(failed("f1"), failed("f2"), failed("f3")), { sent = it }) {
            compose.onNodeWithTag("tab_running").assertIsSelected()
            compose.onNodeWithText(context.getString(R.string.dl_failed_header)).assertExists()
            compose.onNodeWithContentDescription(label(R.string.downloads_retry_all, 3)).performClick()
            // Failures show newest first; Retry all follows the same order.
            assertEquals(BatchAction(BatchKind.RETRY, listOf("f3", "f2", "f1")), sent)
        }
    }
}
