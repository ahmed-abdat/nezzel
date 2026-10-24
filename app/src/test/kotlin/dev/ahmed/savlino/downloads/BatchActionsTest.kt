package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import org.junit.Assert.*
import org.junit.Test

/** Which single header action shows, and on which job IDs. */
class BatchActionsTest {
    private fun running(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.DOWNLOADING, bytes = 10)
    private fun queued(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.PREPARING, waiting = true)
    private fun paused(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.PREPARING, paused = true, hasPartial = true)
    private fun failed(id: String, retryable: Boolean = true) =
        DownloadProgress(id, "$id.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = retryable)

    @Test fun zeroOrOnePausableShowsNothing() {
        assertNull(batchAction(emptyList()))
        assertNull(batchAction(listOf(running("a"))))
        assertNull(batchAction(listOf(queued("a"), failed("f", retryable = false))))
    }

    @Test fun twoPausableShowPauseAllOverRunningAndQueuedInDisplayOrder() {
        assertEquals(BatchAction(BatchKind.PAUSE, listOf("a", "q")), batchAction(listOf(running("a"), queued("q"))))
        assertEquals(BatchAction(BatchKind.PAUSE, listOf("a", "b")), batchAction(listOf(running("a"), running("b"))))
    }

    @Test fun joiningSavingAndScheduleFailuresAreNotPausable() {
        val joining = running("j").copy(phase = DownloadPhase.JOINING)
        val saving = running("s").copy(phase = DownloadPhase.SAVING)
        val unscheduled = queued("u").copy(phase = DownloadPhase.FAILED, awaitingSchedule = true)
        assertNull(batchAction(listOf(running("a"), joining, saving)))
        assertFalse(unscheduled.batchPausable)
    }

    @Test fun pauseWinsOverResumeWhenBothApply() {
        val mixed = listOf(running("a"), paused("p1"), queued("q"), paused("p2"))
        assertEquals(BatchAction(BatchKind.PAUSE, listOf("a", "q")), batchAction(mixed))
    }

    @Test fun resumeAllNeedsTwoPausedAndFewerThanTwoPausable() {
        assertEquals(BatchAction(BatchKind.RESUME, listOf("p1", "p2")), batchAction(listOf(running("a"), paused("p1"), paused("p2"))))
        assertNull(batchAction(listOf(running("a"), paused("p1"))))
    }

    @Test fun retryAllCountsOnlyRetryableFailures() {
        val unscheduled = DownloadProgress("u", "u.mp4", DownloadPhase.FAILED, awaitingSchedule = true, error = DownloadError.ExecutionRestricted)
        assertEquals(BatchAction(BatchKind.RETRY, listOf("f1", "u")),
            batchAction(listOf(failed("f1"), failed("gone", retryable = false), unscheduled)))
        assertNull(batchAction(listOf(failed("f1"), failed("gone", retryable = false))))
        val expired = failed("e", retryable = false).copy(error = DownloadError.FormatExpired)
        assertNull(batchAction(listOf(failed("f1"), expired)))
    }

    @Test fun resumeWinsOverRetry() {
        assertEquals(BatchKind.RESUME, batchAction(listOf(paused("p1"), paused("p2"), failed("f1"), failed("f2")))!!.kind)
    }
}
