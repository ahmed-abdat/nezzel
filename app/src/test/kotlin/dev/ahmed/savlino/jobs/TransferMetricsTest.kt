package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Size
import org.junit.Assert.*
import org.junit.Test

class TransferMetricsTest {
    private var now = 0L
    private fun estimator() = TransferMetrics { now }

    @Test fun requiresTwoSecondsOfObservedBytesThenComputesExactTransferEta() {
        val m = estimator()
        assertNull(m.observe(0, Size.Exact(10_000)).bytesPerSecond)
        now = 1999
        assertNull(m.observe(1999, Size.Exact(10_000)).bytesPerSecond)
        now = 2000
        val value = m.observe(2000, Size.Exact(10_000))
        assertNotNull("Rate must be available after2s of useful bytes", value.bytesPerSecond)
        assertEquals(1000.0, value.bytesPerSecond!!, 0.001)
        assertEquals(8L, value.remainingTransferSeconds)
        assertEquals(false, value.stalled)
    }

    @Test fun rollingWindowDropsOldRateAndUsesIrregularCumulativeIntervals() {
        val m = estimator()
        m.observe(0, Size.Unknown)
        now = 2000; m.observe(2000, Size.Unknown)
        now = 4000; m.observe(4000, Size.Unknown)
        now = 6000; m.observe(5000, Size.Unknown)
        now = 8000; m.observe(6000, Size.Unknown)
        now = 10_000; m.observe(7000, Size.Unknown)
        now = 12_000
        val value = m.observe(8000, Size.Unknown)
        assertNotNull("Rolling8s window must expose the observed rate", value.bytesPerSecond)
        assertEquals(500.0, value.bytesPerSecond!!, 0.001)
        assertNull(value.remainingTransferSeconds)
    }

    @Test fun unknownAndEstimatedTotalsNeverProduceEtaAndExactRevisionCan() {
        val m = estimator()
        m.observe(100, Size.Unknown)
        now = 2000
        val unknown = m.observe(2100, Size.Unknown)
        assertNotNull("Unknown total must not suppress an observed rate", unknown.bytesPerSecond)
        assertEquals(1000.0, unknown.bytesPerSecond!!, 0.001)
        assertNull(unknown.remainingTransferSeconds)
        now = 3000
        val estimate = m.observe(3100, Size.Estimated(10_000))
        assertNotNull(estimate.bytesPerSecond)
        assertNull(estimate.remainingTransferSeconds)
        now = 4000
        assertEquals(6L, m.observe(4100, Size.Exact(10_000)).remainingTransferSeconds)
    }

    @Test fun silentStallClearsRateEtaAndRecoveryRewarms() {
        val m = estimator()
        m.observe(0, Size.Exact(20_000))
        now = 2000; assertNotNull(m.observe(2000, Size.Exact(20_000)).bytesPerSecond)
        now = 6999; assertEquals(false, m.tick().stalled)
        now = 7000
        val stalled = m.tick()
        assertEquals(true, stalled.stalled)
        assertNull(stalled.bytesPerSecond)
        assertNull(stalled.remainingTransferSeconds)
        now = 8000
        assertNull(m.observe(3000, Size.Exact(20_000)).bytesPerSecond)
        now = 10_000
        val recovered = m.observe(5000, Size.Exact(20_000))
        assertNotNull("Recovery must produce rate after a fresh2s window", recovered.bytesPerSecond)
        assertEquals(1000.0, recovered.bytesPerSecond!!, 0.001)
    }

    @Test fun zeroDeltasDoNotPretendNewBytesArrived() {
        val m = estimator()
        m.observe(0, Size.Unknown)
        now = 2000; m.observe(2000, Size.Unknown)
        now = 4000
        val idle = m.observe(2000, Size.Unknown)
        assertNotNull("A2s idle interval below stall threshold must retain decaying rate", idle.bytesPerSecond)
        assertEquals(500.0, idle.bytesPerSecond!!, 0.001)
        now = 7000
        assertEquals(true, m.observe(2000, Size.Unknown).stalled)
    }

    @Test fun regressionClearsHistoryAndStaleOrEqualTimesCannotPoisonWindow() {
        val m = estimator()
        m.observe(0, Size.Unknown)
        now = 2000; assertNotNull(m.observe(2000, Size.Unknown).bytesPerSecond)
        now = 1000; m.observe(Long.MAX_VALUE, Size.Exact(Long.MAX_VALUE))
        now = 2000; m.observe(9000, Size.Unknown)
        now = 3000
        val ordered = m.observe(3000, Size.Unknown)
        assertNotNull("Stale/equal times must not poison the valid observation window", ordered.bytesPerSecond)
        assertEquals(1000.0, ordered.bytesPerSecond!!, 0.001)
        now = 4000
        assertNull(m.observe(10, Size.Unknown).bytesPerSecond)
        now = 6000
        val reset = m.observe(20, Size.Unknown)
        assertNotNull("Byte regression must allow a fresh2s window", reset.bytesPerSecond)
        assertEquals(5.0, reset.bytesPerSecond!!, 0.001)
    }

    @Test fun invalidCountsAndExplicitClearRemoveAllMetrics() {
        val m = estimator()
        m.observe(0, Size.Exact(10_000)); now = 2000
        assertNotNull(m.observe(2000, Size.Exact(10_000)).bytesPerSecond)
        now = 3000
        val invalid = m.observe(-1, Size.Exact(10_000))
        assertNull(invalid.bytesPerSecond); assertNull(invalid.remainingTransferSeconds)
        m.clear()
        assertNull(m.tick().stalled)
        assertNull(m.tick().bytesPerSecond)
    }

    @Test fun contradictoryExactTotalsAndCompletedTransferHaveNoRemainingTime() {
        val m = estimator()
        m.observe(0, Size.Exact(100)); now = 2000
        val over = m.observe(120, Size.Exact(100))
        assertNotNull(over.bytesPerSecond)
        assertNull(over.remainingTransferSeconds)
        now = 3000
        assertNull(m.observe(130, Size.Exact(130)).remainingTransferSeconds)
    }

    @Test fun hugeValuesStayFinitePositiveAndBounded() {
        val m = estimator()
        m.observe(0, Size.Exact(Long.MAX_VALUE)); now = 2000
        val fast = m.observe(Long.MAX_VALUE - 1, Size.Exact(Long.MAX_VALUE))
        assertNotNull("Huge valid counters must still yield a finite observed rate", fast.bytesPerSecond)
        assertTrue(fast.bytesPerSecond!!.isFinite())
        assertTrue(fast.bytesPerSecond!! > 0 && fast.bytesPerSecond!! <= Long.MAX_VALUE.toDouble())
        assertEquals(1L, fast.remainingTransferSeconds)
        m.clear(); now = 3000; m.observe(0, Size.Exact(Long.MAX_VALUE))
        now = 5000
        assertEquals(Long.MAX_VALUE, m.observe(1, Size.Exact(Long.MAX_VALUE)).remainingTransferSeconds)
    }

    @Test fun clockOverflowCannotInventRate() {
        val m = estimator()
        now = Long.MIN_VALUE; m.observe(0, Size.Unknown)
        now = Long.MAX_VALUE
        assertNull(m.observe(2000, Size.Unknown).bytesPerSecond)
    }

    @Test fun equalTimestampByteRegressionImmediatelyClearsRateAndRewarms() {
        val m = estimator()
        m.observe(0, Size.Exact(10_000)); now = 2000
        assertNotNull(m.observe(2000, Size.Exact(10_000)).bytesPerSecond)
        val reset = m.observe(1, Size.Exact(10_000))
        assertNull("Counter regression at the same clock tick must clear old-stream rate", reset.bytesPerSecond)
        assertNull(reset.remainingTransferSeconds)
        now = 3999
        assertNull(m.observe(2000, Size.Exact(10_000)).bytesPerSecond)
        now = 4000
        val recovered = m.observe(2001, Size.Exact(10_000))
        assertNotNull("Reset must rebuild a fresh2s observation", recovered.bytesPerSecond)
        assertEquals(1000.0, recovered.bytesPerSecond!!, 0.001)
    }

    @Test fun invalidNegativeCounterKeepsTheClearedSnapshotOnLaterTicks() {
        val m = estimator()
        m.observe(0, Size.Exact(10_000)); now = 2000
        assertNotNull(m.observe(2000, Size.Exact(10_000)).bytesPerSecond)
        val invalid = m.observe(-1, Size.Exact(10_000))
        assertEquals(false, invalid.stalled)
        now = 2250
        assertEquals("Ticker must retain the same explicit invalid-counter clear", invalid, m.tick())
    }

    @Test fun hugeExactTotalOnlyReachesHundredWhenActualBytesEqualIt() {
        val event = DownloadProgress("id", "clip.mp4", DownloadPhase.DOWNLOADING,
            Long.MAX_VALUE - 1, Size.Exact(Long.MAX_VALUE))
        assertEquals("Double rounding must not signal a completed transfer one byte early", 99, event.percent)
        assertEquals(100, event.copy(bytes = Long.MAX_VALUE).percent)
        assertNull(event.copy(total = Size.Exact(0)).percent)
    }

    @Test fun percentNeverClampsContradictoryActualBytes() {
        val event = DownloadProgress("id", "clip.mp4", DownloadPhase.DOWNLOADING, 120, Size.Exact(100))
        assertNull(event.percent)
        assertNull(event.copy(bytes = -1).percent)
        assertEquals(100, event.copy(bytes = 100).percent)
        assertNull(event.copy(total = Size.Estimated(100)).percent)
    }
}
