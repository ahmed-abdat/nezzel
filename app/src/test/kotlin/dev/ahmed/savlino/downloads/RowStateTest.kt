package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import org.junit.Assert.*
import org.junit.Test

class RowStateTest {
    @Test fun offlineQueueWaitsForConnectionEvenBeforeTheFirstByte() {
        val queued = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, waiting = true)
        assertTrue(queued.waitingForNetwork(true))
        assertFalse(queued.waitingForNetwork(false))
        assertNull(queued.frozenFraction(true))
        assertFalse(queued.copy(paused = true).waitingForNetwork(true))
        assertFalse(queued.copy(phase = DownloadPhase.FAILED).waitingForNetwork(true))
        assertFalse(queued.copy(waiting = false).waitingForNetwork(true))
    }

    @Test fun offlinePartialKeepsOnlyItsMeasuredFraction() {
        val queued = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, waiting = true,
            hasPartial = true, bytes = 25, total = Size.Exact(100))
        assertTrue(queued.waitingForNetwork(true))
        assertEquals(.25f, queued.frozenFraction(true)!!, .001f)
        assertNull(queued.frozenFraction(false))
        assertNull(queued.copy(total = Size.Unknown).frozenFraction(true))
        assertNull(queued.copy(total = Size.Estimated(100)).frozenFraction(true))
    }
}
