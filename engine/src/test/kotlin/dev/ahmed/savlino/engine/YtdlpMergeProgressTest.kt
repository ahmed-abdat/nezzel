package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.Size
import org.junit.Assert.*
import org.junit.Test

class YtdlpMergeProgressTest {
    @Test fun unseenAudioUsesExactInspectionTotal() {
        val mapper = YtdlpProgressMapper(2, expected = Size.Exact(300))
        assertEquals(EngineEvent.Progress(50, Size.Exact(300)), mapper.map("nezzel:v:50:200:NA"))
    }

    @Test fun unseenAudioNeverUsesEstimatedOrUnknownInspectionTotal() {
        listOf(Size.Estimated(300), Size.Unknown).forEach { expected ->
            val mapper = YtdlpProgressMapper(2, expected = expected)
            assertEquals(EngineEvent.Progress(50, Size.Unknown), mapper.map("nezzel:v:50:200:NA"))
        }
    }

    @Test fun bothStreamsUseTheirOwnTotalsInsteadOfInspectionTotal() {
        val mapper = YtdlpProgressMapper(2, expected = Size.Exact(999))
        mapper.map("nezzel:v:200:200:NA")
        assertEquals(EngineEvent.Progress(225, Size.Exact(300)), mapper.map("nezzel:a:25:100:NA"))
        // A later stream that loses its exact size keeps the inspection's exact total instead of dropping the percent.
        assertEquals(EngineEvent.Progress(250, Size.Exact(999)), mapper.map("nezzel:a:50:NA:120"))
        assertEquals(EngineEvent.Progress(275, Size.Exact(999)), mapper.map("nezzel:a:75:NA:NA"))
    }

    @Test fun mergeWithoutExactInspectionTotalKeepsMeasuredTotals() {
        val mapper = YtdlpProgressMapper(2, expected = Size.Estimated(999))
        mapper.map("nezzel:v:200:200:NA")
        assertEquals(EngineEvent.Progress(250, Size.Estimated(320)), mapper.map("nezzel:a:50:NA:120"))
        assertEquals(EngineEvent.Progress(275, Size.Unknown), mapper.map("nezzel:a:75:NA:NA"))
    }

    @Test fun singleStreamUsesItsOwnTotalRegardlessOfInspectionTotal() {
        listOf(Size.Exact(999), Size.Estimated(999), Size.Unknown).forEach { expected ->
            val mapper = YtdlpProgressMapper(expected = expected)
            assertEquals(EngineEvent.Progress(50, Size.Exact(200)), mapper.map("nezzel:v:50:200:NA"))
            assertEquals(EngineEvent.Progress(75, Size.Estimated(220)), mapper.map("nezzel:v:75:NA:220"))
            assertEquals(EngineEvent.Progress(100, Size.Unknown), mapper.map("nezzel:v:100:NA:NA"))
        }
    }

    @Test fun bytesBeyondInspectionTotalAreNeverClamped() {
        val mapper = YtdlpProgressMapper(2, expected = Size.Exact(40))
        assertEquals(EngineEvent.Progress(50, Size.Exact(40)), mapper.map("nezzel:v:50:200:NA"))
    }
}
