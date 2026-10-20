package dev.ahmed.savlino.jobs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProgressDeliveryTest {
    private fun event(bytes: Long = 0, phase: DownloadPhase = DownloadPhase.DOWNLOADING) =
        DownloadProgress("id", "clip.mp4", phase, bytes)

    @Test fun tenThousandPendingSamplesReplaceBeforeQueueAndUiAdmission() = runBlocking {
        var now = 0L
        val ui = mutableListOf<DownloadProgress>()
        val delivery = ProgressDelivery({ now }, { ui += it })
        delivery.offer(event(phase = DownloadPhase.PREPARING))
        repeat(10_000) { delivery.offer(event(it + 1L)) }
        assertEquals(1, delivery.pendingTransferSamples)
        assertEquals(1, ui.count { it.phase == DownloadPhase.DOWNLOADING })
        now = 250; delivery.refresh { it }
        assertFalse("Ticker must publish the latest coalesced sample", ui.isEmpty())
        assertEquals(10_000L, ui.last().bytes)
        delivery.offer(event(phase = DownloadPhase.JOINING))
        delivery.offer(event(phase = DownloadPhase.SAVING))
        delivery.close()
        val reported = mutableListOf<DownloadProgress>()
        delivery.consume { reported += it }
        assertEquals(listOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING,
            DownloadPhase.JOINING, DownloadPhase.SAVING), reported.map { it.phase })
        assertEquals(10_000L, reported[1].bytes)
        println("MEASURE delivery callbacks=10000 reports=${reported.size} ui=${ui.size} pendingTransfer=1")
    }

    @Test fun phaseFlushesLatestBeforeControlWithoutDiscardingRepeatedControlBoundaries() = runBlocking {
        val ui = mutableListOf<DownloadProgress>()
        val delivery = ProgressDelivery({ 0L }, { ui += it })
        delivery.offer(event(1)); delivery.offer(event(2))
        delivery.offer(event(phase = DownloadPhase.JOINING))
        delivery.offer(event(3)); delivery.offer(event(4))
        delivery.offer(event(phase = DownloadPhase.CONVERTING))
        delivery.close()
        val reports = mutableListOf<DownloadProgress>()
        delivery.consume { reports += it }
        assertEquals(listOf(2L, 0L, 4L, 0L), reports.map { it.bytes })
        assertEquals(listOf(DownloadPhase.DOWNLOADING, DownloadPhase.JOINING,
            DownloadPhase.DOWNLOADING, DownloadPhase.CONVERTING), reports.map { it.phase })
        assertEquals(2L, ui[1].bytes)
        assertEquals(DownloadPhase.JOINING, ui[2].phase)
    }

    @Test fun tickerSharesUiBudgetAndCannotResurrectAfterClose() {
        var now = 0L
        val ui = mutableListOf<DownloadProgress>()
        val delivery = ProgressDelivery({ now }, { ui += it })
        delivery.offer(event(1))
        for (t in 1L..1000L) {
            now = t
            delivery.offer(event(t))
            delivery.refresh { it.copy(stalled = false) }
        }
        assertFalse("Transfer admission must publish at least one UI sample", ui.isEmpty())
        assertTrue("UI must stay within4Hz plus initial sample", ui.size <= 5)
        assertEquals(1000L, ui.last().bytes)
        delivery.close()
        val count = ui.size
        delivery.refresh { it.copy(stalled = true) }
        delivery.offer(event(2000))
        assertEquals(count, ui.size)
    }

    @Test fun closeClearsLatestVisibleAndPendingTransferMetricsBeforeFinalFlush() = runBlocking {
        val ui = mutableListOf<DownloadProgress>()
        val reports = mutableListOf<DownloadProgress>()
        val delivery = ProgressDelivery({ 2000L }, { ui += it })
        delivery.offer(event(2000).copy(bytesPerSecond = 1000.0, remainingTransferSeconds = 8, stalled = false))
        assertNotNull(ui.single().bytesPerSecond)
        delivery.close()
        delivery.consume { reports += it }
        assertFalse(ui.isEmpty()); assertFalse(reports.isEmpty())
        for (sample in listOf(ui.last(), reports.last())) {
            assertEquals(2000L, sample.bytes)
            assertNull("Closing transfer must flush cleared rate, not the previous estimator snapshot", sample.bytesPerSecond)
            assertNull(sample.remainingTransferSeconds); assertNull(sample.stalled)
        }
    }

    @Test fun persistenceWritesAtMostOncePerSecondAndFlushesBytesBeforePhase() = runBlocking {
        var now = 0L
        val writes = mutableListOf<DownloadProgress>()
        val persistence = ProgressPersistence({ now }) { writes += it }
        persistence.report(event(phase = DownloadPhase.PREPARING))
        for (n in 0..8) {
            now = n * 250L
            persistence.report(event(n.toLong()))
        }
        persistence.report(event(99))
        persistence.report(event(phase = DownloadPhase.JOINING))
        persistence.report(event(phase = DownloadPhase.SAVING))
        persistence.report(event(phase = DownloadPhase.COMPLETED))
        assertEquals(listOf(0L, 4L, 8L, 99L), writes.filter { it.phase == DownloadPhase.DOWNLOADING }.map { it.bytes })
        assertEquals(listOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING, DownloadPhase.DOWNLOADING,
            DownloadPhase.DOWNLOADING, DownloadPhase.DOWNLOADING, DownloadPhase.JOINING,
            DownloadPhase.SAVING, DownloadPhase.COMPLETED), writes.map { it.phase })
    }
}
