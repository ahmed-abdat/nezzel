package dev.ahmed.savlino.engine

import org.junit.Test
import kotlin.test.*

class Mp3ChunkPlanTest {
    /** [samples] is what the container duration says, rounded down to whole microseconds like ffmpeg's. */
    private fun source(samples: Long, rate: Int = 44100, input: Int = rate, p0: Long = 0, mov: Boolean = true,
        continuous: Boolean = true, constrained: Boolean = false, micros: Long? = (samples * 1_000_000 + rate - 1) / rate) =
        Mp3Source(mov, input, rate, Mp3Probe(p0, continuous), micros, constrained)
    private fun plan(source: Mp3Source, chunks: Int = Mp3ChunkPlan.CHUNKS) = assertNotNull(Mp3ChunkPlan.of(source, chunks))

    /** Kept frames of all chunks, in order, must be exactly 0 until frames with no gap or overlap. */
    private fun assertCovers(plan: Mp3ChunkPlan) {
        val frames = ((plan.estimatedSamples + 1151) / 1152).toInt()
        var next = 0
        for (chunk in plan.chunks) {
            val (start, end) = plan.keep(chunk)
            assertEquals(next, chunk.from + start)
            assertTrue(start >= 0 && (end == null || end > start))
            if (!chunk.last) {
                assertEquals(minOf(chunk.first, Mp3ChunkPlan.PRE), start, "pre-roll")
                assertEquals(chunk.to!! - Mp3ChunkPlan.POST, chunk.end, "post-roll")
                assertTrue(chunk.to.toLong() * 1152 <= plan.estimatedSamples, "post-roll is real audio")
            }
            next = chunk.from + (end ?: (frames - chunk.from))
        }
        assertEquals(frames, next)
        assertTrue(plan.chunks.last().last && plan.chunks.dropLast(1).none { it.last })
    }

    @Test fun probeStatsGiveFirstPtsExactLengthAndContinuity() {
        assertEquals(Mp3Probe(1147, true), Mp3Probe.parse(sequenceOf("1147 0 1024", "2171 1024 1024", "", "3195 2048 952")))
        assertEquals(false, Mp3Probe.parse(sequenceOf("0 0 1024", "1025 1024 1024"))?.continuous)
        assertNull(Mp3Probe.parse(sequenceOf("0 0")))
        assertNull(Mp3Probe.parse(sequenceOf("a b c")))
        assertNull(Mp3Probe.parse(emptySequence()))
    }

    @Test fun lengthNotAMultipleOf1152SplitsIntoEightChunksCoveringEveryFrame() {
        val plan = plan(source(600L * 44100 + 500))
        assertEquals(8, plan.chunks.size)
        assertCovers(plan)
        val first = plan.chunks.first()
        assertEquals(0, first.from)
        assertEquals(0L, plan.seekMicros(first))
        assertEquals(0 to first.end, plan.keep(first))
        val last = plan.chunks.last()
        assertNull(last.to)
        assertNull(plan.endPts(last))
        assertEquals(Mp3ChunkPlan.PRE to null, plan.keep(last))
        assertEquals(Mp3ChunkPlan.PRE + 1, plan.minFrames(last))
        val middle = plan.chunks[5]
        assertEquals(middle.to!! - middle.from, plan.minFrames(middle))
        assertEquals(middle.from.toLong() * 1152, plan.startPts(middle))
        assertEquals(middle.from.toLong() * 1152 * 1_000_000 / 44100 - 1_000_000, plan.seekMicros(middle))
    }

    @Test fun underTwoMinutesIsOnePass() {
        assertNull(Mp3ChunkPlan.of(source(120L * 44100 - 1)))
        assertNotNull(Mp3ChunkPlan.of(source(120L * 44100)))
    }

    @Test fun moreChunksThanFramesStillCoversOnce() {
        val samples = 120L * 32000
        val plan = plan(source(samples, rate = 32000), chunks = 100_000)
        assertCovers(plan)
        assertTrue(plan.chunks.all { (it.end ?: it.first + 1) - it.first <= 3 })
    }

    @Test fun shortTailJoinsTheChunksBeforeItWhenTheirPostRollWouldPassTheEnd() {
        // One-frame chunks: the tail is shorter than the post-roll the chunk before it needs.
        val samples = 120L * 32000
        val plan = plan(source(samples, rate = 32000), chunks = 100_000)
        assertCovers(plan)
        val last = plan.chunks.last()
        assertEquals(samples / 1152 - Mp3ChunkPlan.POST, last.first.toLong())
    }

    @Test fun longSourcesRaiseTheChunkCountToKeepChunksUnderFiveMinutes() {
        // 65 min: 149297 frames, at most 11484 (300 s) per chunk, so 14 chunks instead of 8.
        val plan = plan(source(3900L * 44100))
        assertEquals(14, plan.chunks.size)
        assertCovers(plan)
        assertTrue(plan.chunks.dropLast(1).all { (it.end!! - it.first).toLong() * 1152 <= 300L * 44100 })
    }

    @Test fun resamplingCutsOnTheGcdGridAtOrBeforeTheOutputCut() {
        val plan = plan(source(600L * 44100, rate = 44100, input = 48000, p0 = 147 * 3))
        assertCovers(plan)
        for (chunk in plan.chunks) {
            val start = assertNotNull(plan.inputTrimStart(chunk))
            assertEquals(0L, start % 160)
            val exact = plan.startPts(chunk) * 48000 / 44100
            assertTrue(start <= exact && exact - start < 160, "chunk ${chunk.index}")
            assertEquals(147L * 3 + chunk.from * 1152L, plan.startPts(chunk))
        }
        assertNull(plan(source(600L * 44100)).inputTrimStart(plan(source(600L * 44100)).chunks[3]))
    }

    @Test fun onlyCleanMovSourcesAtMpegOneRatesOnACoolRoomyPhoneChunk() {
        val long = 600L * 44100
        assertNull(Mp3ChunkPlan.of(source(long, mov = false)), "webm/HLS")
        assertNull(Mp3ChunkPlan.of(source(long, continuous = false)), "pts jitter")
        assertNull(Mp3ChunkPlan.of(source(600L * 22050, rate = 22050)), "MPEG-2 frames")
        assertNull(Mp3ChunkPlan.of(source(long, input = 48000, p0 = 100)), "P0 off the resample grid")
        assertNull(Mp3ChunkPlan.of(source(long, constrained = true)), "thermal or low RAM")
        assertNull(Mp3ChunkPlan.of(source(long, micros = null)), "no container duration")
        assertNull(Mp3ChunkPlan.of(source(long, micros = 0)), "zero container duration")
        assertNull(Mp3ChunkPlan.of(source(long, input = 0)))
        assertNotNull(Mp3ChunkPlan.of(source(long, p0 = 1147)), "a late audio start is fine without resampling")
    }

    @Test fun chunkStatsMustStartOnTheCutFollowOnAndFeedExactlyTheSpan() {
        val plan = plan(source(600L * 44100 + 500, p0 = 1147))
        val chunk = plan.chunks[4]
        val span = (chunk.to!! - chunk.from).toLong() * 1152
        fun lines(first: Long, total: Long) = generateSequence(0L) { it + 1152 }.takeWhile { it < total }
            .map { "${first + it} $it ${minOf(1152, total - it)}" }
        assertEquals(span, plan.chunkSamples(chunk, lines(plan.startPts(chunk), span)))
        assertNull(plan.chunkSamples(chunk, lines(plan.startPts(chunk) + 1, span)), "late seek")
        assertNull(plan.chunkSamples(chunk, lines(plan.startPts(chunk), span - 1)), "short")
        val gap = lines(plan.startPts(chunk), span).toMutableList().apply { this[7] = this[7].replaceBefore(' ', "${plan.startPts(chunk) + 7 * 1152 + 1}") }
        assertNull(plan.chunkSamples(chunk, gap.asSequence()), "a pts gap mid-chunk")
        assertNull(plan.chunkSamples(chunk, emptySequence()))
        assertNull(plan.chunkSamples(chunk, sequenceOf("x")))
        // The last chunk's length is whatever the source holds past its cut; it gives the exact S.
        val last = plan.chunks.last()
        val tail = 600L * 44100 + 777 - last.from * 1152L
        assertEquals(tail, plan.chunkSamples(last, lines(plan.startPts(last), tail)))
        assertEquals(600L * 44100 + 777, plan.samples(tail))
        assertNull(plan.chunkSamples(last, lines(plan.startPts(last), Mp3ChunkPlan.PRE * 1152L)), "nothing past the kept start")
    }
}
