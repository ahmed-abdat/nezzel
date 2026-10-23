package dev.ahmed.savlino.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectionTimingTest {
    @Test fun phasesUseOnlyFixedLabelsAndMonotonicElapsedTimes() = runBlocking {
        var time = 100L
        val messages = mutableListOf<String>()
        val timing = InspectionTiming(enabled = true, now = { time }, log = { messages += it })
        withContext(timing.context()) {
            for (phase in InspectionPhase.entries) { time += 10; InspectionTiming.mark(phase) }
        }
        assertEquals(listOf("intake_accepted 10ms", "engine_gate_passed 20ms", "native_reader_done 30ms",
            "ytdlp_spawned 40ms", "json_parsed 50ms", "options_shown 60ms", "early_title 70ms", "thumbnail_shown 80ms"), messages)
        assertTrue(messages.none { "://" in it })
    }
    @Test fun releaseDisabledTraceRecordsNothing() = runBlocking {
        val messages = mutableListOf<String>()
        val timing = InspectionTiming(enabled = false, now = { error("Clock must not run") }, log = { messages += it })
        withContext(timing.context()) { InspectionTiming.mark(InspectionPhase.INTAKE_ACCEPTED) }
        assertTrue(messages.isEmpty())
    }
}
