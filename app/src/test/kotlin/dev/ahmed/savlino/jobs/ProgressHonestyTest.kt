package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.engine.EngineEvent
import dev.ahmed.savlino.engine.YtdlpProgressMapper
import org.junit.Assert.*
import org.junit.Test

class ProgressHonestyTest {
    private val downloading = DownloadProgress("a", "Actual.mp4", DownloadPhase.DOWNLOADING,
        bytes = 160_900_000, total = Size.Estimated(156_700_000))

    @Test fun preparingTinyBytesNeverSupplyVisibleProgressOrByteDenominator() {
        val preparing = downloading.copy(phase = DownloadPhase.PREPARING, bytes = 1, total = Size.Exact(1024))
        assertNull(preparing.visiblePercent); assertNull(preparing.exactTransferTotal)
    }
    @Test fun byteOverrunNeverSuppliesPercentOrSmallerDenominator() {
        listOf(Size.Estimated(156_700_000), Size.Exact(156_700_000), Size.Unknown).forEach { total ->
            val event = downloading.copy(total = total)
            assertEquals(160_900_000L, event.bytes)
            assertNull(event.percent); assertNull(event.exactTransferTotal)
        }
    }
    @Test fun reportedFragmentsGivePercentEvenWithUnknownOrExceededByteEstimate() {
        listOf(Size.Unknown, Size.Estimated(156_700_000)).forEach { total ->
            val event = downloading.copy(total = total, fragmentIndex = 25, fragmentCount = 100)
            assertEquals(25, event.visiblePercent); assertNull(event.exactTransferTotal)
            assertNull(event.copy(stalled = true).visiblePercent)
            assertNull(event.copy(waiting = true).visiblePercent)
            DownloadPhase.entries.filter { it != DownloadPhase.DOWNLOADING }.forEach { phase ->
                assertNull(event.copy(phase = phase).visiblePercent)
            }
        }
    }
    @Test fun unreportedInvalidOrPartialFragmentsStayIndeterminate() {
        listOf(null to null, 1L to null, null to 10L, -1L to 10L, 11L to 10L, 0L to 0L).forEach { (index, count) ->
            assertNull(downloading.copy(fragmentIndex = index, fragmentCount = count).visiblePercent)
        }
    }
    @Test fun completedFragmentsAloneCanReachOneHundredPercentWithoutRoundingUp() {
        val event = downloading.copy(fragmentIndex = Long.MAX_VALUE - 1, fragmentCount = Long.MAX_VALUE)
        assertEquals(99, event.visiblePercent)
        assertEquals(100, event.copy(fragmentIndex = Long.MAX_VALUE).visiblePercent)
    }

    private fun mapped(mapper: YtdlpProgressMapper, line: String): DownloadProgress {
        val event = mapper.map(line) as EngineEvent.Progress
        return downloading.copy(bytes = event.bytesDownloaded, total = event.total,
            fragmentIndex = event.fragmentIndex, fragmentCount = event.fragmentCount)
    }

    @Test fun oneFragmentMapsToZeroThenOneHundredPercent() {
        val mapper = YtdlpProgressMapper()
        assertEquals(0, mapped(mapper, "nezzel-v2:hls:0:NA:NA:0:1").visiblePercent)
        assertEquals(100, mapped(mapper, "nezzel-v2:hls:100:NA:NA:1:1").visiblePercent)
    }

    @Test fun skippedVideoFragmentsAndAudioProgressNeverDecreasePercentAtFinalHooks() {
        val mapper = YtdlpProgressMapper(2)
        val samples = listOf("v:80:NA:NA:8:10", "v:80:80:NA:NA:NA", "a:20:NA:NA:2:10",
            "a:21:NA:NA:1:10", "a:21:21:NA:NA:NA").map { mapped(mapper, "nezzel-v2:$it") }
        assertEquals(listOf(null, null, 50, 50, 50), samples.map { it.visiblePercent })
    }

    @Test fun mixedHlsAndProgressiveMergeUsesOnlyReportedByteTotalsWithoutPercentRegression() {
        val mapper = YtdlpProgressMapper(2)
        val samples = listOf("v:80:NA:NA:8:10", "v:100:100:NA:NA:NA", "a:0:100:NA:NA:NA",
            "a:50:100:NA:NA:NA", "a:100:100:NA:NA:NA").map { mapped(mapper, "nezzel-v2:$it") }
        assertEquals(listOf(null, null, 50, 75, 100), samples.map { it.visiblePercent })
        samples.forEach { assertNull(it.fragmentIndex); assertNull(it.fragmentCount) }
        assertEquals(Size.Exact(200), samples.last().total)
    }

    @Test fun byteOnlySamplesMidStreamDoNotReplaceTheLastMeasuredFragmentFraction() {
        val mapper = YtdlpProgressMapper()
        val samples = listOf("hls:50:NA:100:5:10", "hls:60:100:NA:NA:NA", "hls:70:100:NA:NA:NA")
            .map { mapped(mapper, "nezzel-v2:$it") }
        assertEquals(listOf(50, 50, 50), samples.map { it.visiblePercent })
        assertEquals(listOf(50L, 60L, 70L), samples.map { it.bytes })
    }

    @Test fun measuringTwinCopyKeepsCompletedFragmentsAndFinishingPercent() {
        val complete = downloading.copy(bytes = 100_000_000, total = Size.Exact(100_000_000),
            fragmentIndex = 10, fragmentCount = 10)
        val twin = complete.copy(bytes = 100_000_000L * 99 / 100,
            remainingTransferSeconds = complete.remainingTransferSeconds?.coerceAtLeast(3599))
        assertEquals(complete.fragmentIndex, twin.fragmentIndex)
        assertEquals(complete.fragmentCount, twin.fragmentCount)
        assertEquals(100, twin.visiblePercent)
    }
}
