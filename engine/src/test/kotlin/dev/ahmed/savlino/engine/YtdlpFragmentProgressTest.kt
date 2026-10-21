package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.Size
import org.junit.Assert.*
import org.junit.Test

class YtdlpFragmentProgressTest {
    @Test fun numericFragmentTemplateKeepsRealBytesAndEstimatedSize() {
        val event = YtdlpProgressMapper().map("nezzel-v2:hls:video:160900000:NA:156700000:25:100")
        assertNotNull("Reported fragment progress must reach the app", event)
        event as EngineEvent.Progress
        assertEquals(160_900_000L, event.bytesDownloaded)
        assertEquals(Size.Estimated(156_700_000), event.total)
        assertEquals(25L, event.fragmentIndex)
        assertEquals(100L, event.fragmentCount)
    }

    @Test fun missingAndMalformedFragmentsNeverBecomeMadeUpProgress() {
        listOf("NA:NA", "0:0", "1:NA", "-1:10", "11:10", "2.5:10", "1:Infinity").forEach { fragments ->
            val event = YtdlpProgressMapper().map("nezzel-v2:hls:10:NA:NA:$fragments") as EngineEvent.Progress
            assertNull(fragments, event.fragmentIndex); assertNull(fragments, event.fragmentCount)
            assertEquals(Size.Unknown, event.total)
            assertEquals(10L, event.bytesDownloaded)
        }
    }

    @Test fun separateStreamsRequireBothReportedFragmentDenominators() {
        val mapper = YtdlpProgressMapper(2)
        val video = mapper.map("nezzel-v2:v:200:NA:NA:10:10") as EngineEvent.Progress
        assertNull(video.fragmentIndex); assertNull(video.fragmentCount)
        // The actual final hook replaces the transfer estimate with the file size and omits fragments.
        mapper.map("nezzel-v2:v:190:190:NA:NA:NA")
        val both = mapper.map("nezzel-v2:a:50:NA:NA:5:10") as EngineEvent.Progress
        assertEquals(250L, both.bytesDownloaded)
        assertEquals(15L, both.fragmentIndex); assertEquals(20L, both.fragmentCount)
        val missing = mapper.map("nezzel-v2:a:60:NA:NA:NA:NA") as EngineEvent.Progress
        assertEquals(15L, missing.fragmentIndex); assertEquals(20L, missing.fragmentCount)
    }

    @Test fun skippedAndRepeatedFragmentsRetainTheirMeasuredPairAcrossFinalHooks() {
        val mapper = YtdlpProgressMapper(2)
        mapper.map("nezzel-v2:v:80:NA:NA:8:10")
        mapper.map("nezzel-v2:v:80:80:NA:NA:NA")
        val audio = mapper.map("nezzel-v2:a:20:NA:NA:2:10") as EngineEvent.Progress
        assertEquals(10L, audio.fragmentIndex); assertEquals(20L, audio.fragmentCount)
        val repeated = mapper.map("nezzel-v2:a:21:NA:NA:1:10") as EngineEvent.Progress
        assertEquals(10L, repeated.fragmentIndex); assertEquals(20L, repeated.fragmentCount)
        val final = mapper.map("nezzel-v2:a:21:21:NA:NA:NA") as EngineEvent.Progress
        assertEquals(10L, final.fragmentIndex); assertEquals(20L, final.fragmentCount)
    }

    @Test fun exactByteBasisIsNeverReplacedByALowerFragmentFraction() {
        // Review F1: 200 of an exact 300 bytes is 66%; the audio's first 0/10 fragments must not turn it into 10/20 = 50%.
        val mapper = YtdlpProgressMapper(2, Size.Exact(300))
        val video = mapper.map("nezzel-v2:v:200:NA:NA:10:10") as EngineEvent.Progress
        assertEquals(Size.Exact(300), video.total); assertNull(video.fragmentIndex)
        val audio = mapper.map("nezzel-v2:a:0:NA:NA:0:10") as EngineEvent.Progress
        assertNull(audio.fragmentIndex); assertNull(audio.fragmentCount)
        assertEquals(200L, audio.bytesDownloaded); assertEquals(Size.Exact(300), audio.total)
    }

    @Test fun singleFragmentKeepsZeroAndOneAsReported() {
        val mapper = YtdlpProgressMapper()
        val start = mapper.map("nezzel-v2:hls:0:NA:NA:0:1") as EngineEvent.Progress
        assertEquals(0L, start.fragmentIndex); assertEquals(1L, start.fragmentCount)
        val end = mapper.map("nezzel-v2:hls:100:NA:NA:1:1") as EngineEvent.Progress
        assertEquals(1L, end.fragmentIndex); assertEquals(1L, end.fragmentCount)
    }

    @Test fun colonFormatIdsRemainDistinctInFragmentTemplate() {
        val mapper = YtdlpProgressMapper(2)
        mapper.map("nezzel-v2:hls:video:40:NA:NA:4:10")
        val audio = mapper.map("nezzel-v2:hls:audio:10:NA:NA:1:10") as EngineEvent.Progress
        assertEquals(50L, audio.bytesDownloaded)
        assertEquals(5L, audio.fragmentIndex); assertEquals(20L, audio.fragmentCount)
    }

    @Test fun byteOnlyLinesMidStreamRetainLastKnownFragmentsWithoutPromotingThem() {
        val mapper = YtdlpProgressMapper()
        mapper.map("nezzel-v2:hls:50:NA:100:5:10")
        listOf("nezzel-v2:hls:60:100:NA:NA:NA", "nezzel:hls:70:100:NA").forEach { line ->
            val bytesOnly = mapper.map(line) as EngineEvent.Progress
            assertEquals(5L, bytesOnly.fragmentIndex); assertEquals(10L, bytesOnly.fragmentCount)
        }
    }

    @Test fun fragmentSumOverflowDoesNotCreateSaturatedFakeDenominator() {
        val mapper = YtdlpProgressMapper(2)
        mapper.map("nezzel-v2:v:1:NA:NA:1:${Long.MAX_VALUE}")
        val event = mapper.map("nezzel-v2:a:1:NA:NA:1:1") as EngineEvent.Progress
        assertNull(event.fragmentIndex); assertNull(event.fragmentCount)
    }

    @Test fun commandRequestsNumericFragmentsWithoutParsingHumanPercentOrEta() {
        assertTrue(YtdlpCommand.PROGRESS_TEMPLATE.contains("%(progress.fragment_index)s:%(progress.fragment_count)s"))
        assertNull(YtdlpProgressMapper().map("[download] 25% of ~1 MiB (frag 25/100) ETA 00:30"))
    }
}
