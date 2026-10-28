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
        // Back online it is still queued over the same partial, so the fraction stays until bytes move.
        assertEquals(.25f, queued.frozenFraction(false)!!, .001f)
        assertNull(queued.copy(total = Size.Unknown).frozenFraction(true))
        assertNull(queued.copy(total = Size.Estimated(100)).frozenFraction(true))
    }

    @Test fun aDropTheCoordinatorSawWaitsBeforeTheOfflineSignalSettles() {
        val queued = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, waiting = true, networkWait = true)
        assertTrue(queued.waitingForNetwork(false))
        assertFalse(queued.copy(paused = true).waitingForNetwork(false))
        assertFalse(queued.copy(waiting = false).waitingForNetwork(false))
    }

    @Test fun aTransferThatStallsOfflineWaitsForConnectionAndKeepsItsPercent() {
        val stalled = DownloadProgress("job", "Clip.mp4", DownloadPhase.DOWNLOADING, stalled = true,
            bytes = 26, total = Size.Exact(100))
        assertTrue(stalled.waitingForNetwork(true))
        assertEquals(.26f, stalled.frozenFraction(true)!!, .001f)
        // Online, a stall is the server's: it still waits for data.
        assertFalse(stalled.waitingForNetwork(false))
        assertFalse(stalled.copy(networkWait = true).waitingForNetwork(false))
        assertFalse(stalled.copy(stalled = false).waitingForNetwork(true))
        assertFalse(stalled.copy(paused = true).waitingForNetwork(true))
    }

    @Test fun queueAndPreparationOverAKeptPartialKeepTheLastSample() {
        val sample = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, waiting = true,
            hasPartial = true, bytes = 25, total = Size.Exact(100))
        assertTrue(sample.keepsSample)
        assertEquals(.25f, sample.frozenFraction(false)!!, .001f)
        assertTrue(sample.copy(waiting = false).keepsSample)
        assertFalse(sample.copy(waiting = false, phase = DownloadPhase.DOWNLOADING).keepsSample)
        assertFalse(sample.copy(hasPartial = false).keepsSample)
        assertFalse(sample.copy(bytes = null).keepsSample)
        assertFalse(sample.copy(paused = true).keepsSample)
        assertFalse(sample.copy(phase = DownloadPhase.FAILED, waiting = false).keepsSample)
    }
}
