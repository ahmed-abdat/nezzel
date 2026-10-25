package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.shortcuts.WidgetUiState
import org.junit.Assert.*
import org.junit.Test

class LiveLeaderTest {
    private fun job(id: String, bytes: Long = 20, phase: DownloadPhase = DownloadPhase.DOWNLOADING) =
        DownloadProgress(id, "$id.mp4", phase, bytes, Size.Exact(100))

    @Test fun newestRunningJobLeadsAndTheWidgetShowsTheSameOne() {
        val jobs = listOf(job("one"), job("two", 60), job("three", 80), job("queued").copy(waiting = true),
            job("paused").copy(paused = true), job("done", phase = DownloadPhase.COMPLETED))
        assertEquals("three", LiveLeader.pick(jobs)?.jobId)
        val widget = WidgetUiState.from(jobs)
        assertEquals(listOf("three", "two"), widget.jobs.map { it.id })
        assertEquals("three", widget.jobs.first().title)
        assertEquals(80, widget.jobs.first().percent)
        // Two rows shown; the other running job, queue and paused job remain reachable in Downloads.
        assertEquals(3, widget.more)
    }

    @Test fun noRunningJobMeansNoLeader() {
        assertNull(LiveLeader.pick(emptyList()))
        assertNull(LiveLeader.pick(listOf(job("q").copy(waiting = true), job("p").copy(paused = true),
            job("f", phase = DownloadPhase.FAILED), job("s").copy(awaitingSchedule = true))))
        // Preparing holds a slot, so it leads (indeterminate bar, no chip number).
        assertEquals("prep", LiveLeader.pick(listOf(job("prep", phase = DownloadPhase.PREPARING)))?.jobId)
    }
}
