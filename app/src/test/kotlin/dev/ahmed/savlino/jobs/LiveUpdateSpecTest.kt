package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.ringFraction
import org.junit.Assert.*
import org.junit.Test

class LiveUpdateSpecTest {
    private fun job(phase: DownloadPhase = DownloadPhase.DOWNLOADING, bytes: Long? = 45, total: Size = Size.Exact(100)) =
        DownloadProgress("a", "clip.mp4", phase, bytes, total)
    private fun spec(job: DownloadProgress, leader: Boolean = true, canPost: Boolean = true, language: String = "en") =
        LiveUpdateSpec.from(job, leader, canPost, language)

    @Test fun realPercentGivesABarAndABareChip() {
        assertEquals(LiveSpec(45, false, "45%"), spec(job()))
        // Latin digits and no isolate marks in every language (NezzelFormat.percent).
        assertEquals("45%", spec(job(), language = "ar")?.chip)
        assertEquals("45%", spec(job(), language = "fr")?.chip)
    }

    @Test fun phasesWithoutAPercentMoveWithoutANumber() {
        val indeterminate = LiveSpec(null, true, null)
        for (phase in listOf(DownloadPhase.PREPARING, DownloadPhase.JOINING, DownloadPhase.CONVERTING, DownloadPhase.SAVING))
            assertEquals(phase.name, indeterminate, spec(job(phase)))
        assertEquals(indeterminate, spec(job(total = Size.Unknown)))
        assertEquals(indeterminate, spec(job(total = Size.Estimated(100))))
        assertEquals(indeterminate, spec(job().copy(stalled = true)))
    }

    // F4: a conversion after a full download has no percent anywhere: the notification bar (setProgress
    // indeterminate when visiblePercent is null), the Live Update and the Downloads ring all spin.
    @Test fun postProcessingAfterAFullTransferShowsNoPercentAnywhere() {
        for (phase in listOf(DownloadPhase.JOINING, DownloadPhase.CONVERTING, DownloadPhase.SAVING)) {
            val done = job(phase, bytes = 100)
            assertNull(phase.name, done.visiblePercent)
            assertNull(phase.name, done.ringFraction())
            assertEquals(phase.name, LiveSpec(null, true, null), spec(done))
        }
    }

    @Test fun anythingNotRunningOrNotLeadingKeepsTodaysNotification() {
        for (stopped in listOf(job().copy(paused = true), job().copy(waiting = true), job().copy(awaitingSchedule = true),
            job(DownloadPhase.COMPLETED), job(DownloadPhase.FAILED), job(DownloadPhase.CANCELLED)))
            assertNull(spec(stopped))
        assertNull(spec(job(), leader = false))
        assertNull(spec(job(), canPost = false))
    }
}
