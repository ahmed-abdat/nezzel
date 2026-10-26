package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Pause/Resume row states: which button shows, its TalkBack name, the word on the meta line, and what it never claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PauseRowTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "Holiday clip.mp4"
    private val base = DownloadProgress("a", name, DownloadPhase.DOWNLOADING, bytes = 25_000_000, total = Size.Exact(100_000_000),
        bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false, qualityHeight = 720)
    private val paused = base.copy(phase = DownloadPhase.PREPARING, paused = true, hasPartial = true,
        bytesPerSecond = null, remainingTransferSeconds = null, stalled = null)
    private fun label(res: Int) = context.getString(res, NezzelFormat.isolate(name))

    private fun render(job: DownloadProgress, offline: Boolean = false, onPause: () -> Unit = {}, onResume: () -> Unit = {},
                       onCancel: () -> Unit = {}, check: () -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalOffline provides offline) { JobRow(job, onCancel, {}, {}, onPause = onPause, onResume = onResume) }
            } }
            compose.waitForIdle(); check()
        } finally { c.pause().stop().destroy() }
    }
    private fun has(desc: String) = compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun hasText(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pulses() = compose.onAllNodesWithTag("job_pulse", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun indeterminate() = compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
        androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun stateSaysPaused() = compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
        context.getString(R.string.download_paused)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun pausedWithoutAFractionIsStillAndSaysPaused() {
        render(paused.copy(bytes = null)) {
            assertFalse("a paused row never pulses", pulses())
            assertFalse("a paused row is not in progress", indeterminate())
            assertTrue(stateSaysPaused())
        }
    }

    @Test fun pausedWithAFractionSaysPausedNotProgress() {
        render(paused) { assertFalse(pulses()); assertFalse(indeterminate()); assertTrue(stateSaysPaused()) }
    }

    @Test fun preparingWithoutAFractionStillBreathes() {
        render(base.copy(phase = DownloadPhase.PREPARING, bytes = null, bytesPerSecond = null)) {
            assertTrue(pulses()); assertTrue(indeterminate())
        }
    }

    @Test fun runningRowOffersPauseNamedAfterTheFileAndCancel() {
        var pauses = 0
        render(base, onPause = { pauses++ }) {
            compose.onNodeWithContentDescription(label(R.string.download_pause_accessibility), useUnmergedTree = true).performClick()
            assertEquals(1, pauses)
            assertTrue(has(label(R.string.download_cancel_accessibility)))
            assertFalse(has(label(R.string.download_resume_accessibility)))
        }
    }

    @Test fun pausedRowShowsLastRealPercentAndBytesAndOffersResume() {
        var resumes = 0
        render(paused, onResume = { resumes++ }) {
            compose.onNodeWithContentDescription(label(R.string.download_resume_accessibility), useUnmergedTree = true).performClick()
            assertEquals(1, resumes)
            assertTrue(hasText(context.getString(R.string.download_paused)))
            assertTrue(hasText("25"))
            assertFalse("no speed or time left while paused", hasText("left") || hasText("/s"))
            assertFalse(has(label(R.string.download_pause_accessibility)))
            assertTrue(has(label(R.string.download_cancel_accessibility)))
        }
    }

    @Test fun pausedAfterRestartWithoutBytesShowsOnlyPaused() {
        render(paused.copy(bytes = null)) {
            assertTrue(hasText(context.getString(R.string.download_paused)))
            assertFalse(hasText("%"))
            assertTrue(has(label(R.string.download_resume_accessibility)))
        }
    }

    @Test fun queuedWithPartialIsWaitingForNetworkOnlyWhileOfflineAndHasNoPauseOrResume() {
        val queued = DownloadProgress("a", name, DownloadPhase.PREPARING, waiting = true, hasPartial = true, total = Size.Exact(100))
        render(queued, offline = true) {
            assertTrue(hasText(context.getString(R.string.download_waiting_network)))
            assertFalse(has(label(R.string.download_pause_accessibility)) || has(label(R.string.download_resume_accessibility)))
            assertTrue("Cancel stays", has(label(R.string.download_cancel_accessibility)))
            assertFalse(hasText("%"))
        }
        render(queued, offline = false) {
            assertTrue(hasText(context.getString(R.string.download_waiting)))
            assertFalse(hasText(context.getString(R.string.download_waiting_network)))
        }
    }

    @Test fun newEpochBeforeItsFirstSampleIsStartingAgainWithNoPercent() {
        val restarting = DownloadProgress("a", name, DownloadPhase.PREPARING, total = Size.Exact(100), restartEpoch = 2)
        render(restarting) {
            assertTrue(hasText(context.getString(R.string.download_restarting)))
            assertFalse(hasText("%"))
            assertFalse(has(label(R.string.download_pause_accessibility)))
        }
    }

    @Test fun joiningFailedAndPlainQueuedRowsHaveNoPauseButton() {
        val joining = base.copy(phase = DownloadPhase.JOINING)
        val queued = DownloadProgress("a", name, DownloadPhase.PREPARING, waiting = true)
        val failed = DownloadProgress("a", name, DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.Network)
        for (job in listOf(joining, queued, failed)) render(job) {
            assertFalse(job.phase.toString(), has(label(R.string.download_pause_accessibility)) || has(label(R.string.download_resume_accessibility)))
        }
    }

    @Test fun rowStateRules() {
        assertTrue(base.canPause)
        assertFalse(paused.canPause)
        assertFalse(base.copy(stalled = null, waiting = true).canPause)
        assertEquals(.04f, paused.copy(bytes = 4_000_000).frozenFraction(false)!!, .001f)
        assertNull("running rows use the live gate, not the frozen one", base.frozenFraction(false))
        assertNull(paused.copy(bytes = null).frozenFraction(false))
        assertFalse(paused.restarting)
    }
}
