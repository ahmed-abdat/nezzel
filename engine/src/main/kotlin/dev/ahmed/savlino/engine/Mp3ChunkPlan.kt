package dev.ahmed.savlino.engine

import dev.ahmed.savlino.engine.Mp3Frames.SAMPLES

/**
 * A decode of the source's first seconds, read from ffmpeg's `-stats_enc_pre` lines "{pts} {sn} {samp}" (output-rate
 * samples). [firstPts] is P0, [continuous] whether every frame had pts == P0+sn. The chunks check the rest themselves.
 */
internal data class Mp3Probe(val firstPts: Long, val continuous: Boolean) {
    companion object {
        /** Null when the probe wrote nothing or a line isn't three integers. */
        fun parse(lines: Sequence<String>): Mp3Probe? {
            var first: Long? = null
            var continuous = true
            for (line in lines) {
                if (line.isBlank()) continue
                val (pts, sn, samp) = statsLine(line) ?: return null
                val p0 = first ?: pts.also { first = it }
                if (pts != p0 + sn || samp <= 0) continuous = false
            }
            return first?.let { Mp3Probe(it, continuous) }
        }
    }
}

internal fun statsLine(line: String): Triple<Long, Long, Long>? {
    val parts = line.trim().split(' ')
    if (parts.size != 3) return null
    val (pts, sn, samp) = parts.map { it.toLongOrNull() ?: return null }
    return Triple(pts, sn, samp)
}

/**
 * What decides between chunks and one pass. Rates are Hz; the output rate is the encoder's (44.1k for 320k).
 * [durationMicros] is the container's, only for sizing chunks. [constrained]: thermal SEVERE or worse, or low RAM (a charging phone sits at MODERATE).
 */
internal data class Mp3Source(
    val movFamily: Boolean,
    val inputRate: Int,
    val outputRate: Int,
    val probe: Mp3Probe,
    val durationMicros: Long?,
    val constrained: Boolean,
)

/**
 * Chunk c owns frames [a, b) and encodes output samples [from·1152, to·1152) with [PRE] frames of pre-roll and [POST]
 * of post-roll; the last chunk runs to the end of the stream. Every `from` is a multiple of 1152 and LAME's delay is
 * the same in every instance, so a chunk's frame j equals single-pass frame from+j.
 */
internal data class Mp3Chunk(val index: Int, val first: Int, val end: Int?, val from: Int, val to: Int?) {
    val last get() = end == null
}

internal class Mp3ChunkPlan private constructor(
    val sampleRate: Int,
    private val inputRate: Int,
    /** P0: the first decoded pts. Cuts are offset by it so a late audio start (muxed MP4) stays aligned. */
    val firstPts: Long,
    /** From the container duration: sizes the chunks, never trusted as the length. */
    val estimatedSamples: Long,
    val chunks: List<Mp3Chunk>,
) {
    fun startPts(chunk: Mp3Chunk) = firstPts + chunk.from.toLong() * SAMPLES
    fun endPts(chunk: Mp3Chunk) = chunk.to?.let { firstPts + it.toLong() * SAMPLES }

    /**
     * Input-rate start of the atrim before the resampler, or null without resampling. Snapped DOWN to the
     * 1/gcd grid so every chunk's resampler has the phase of a single pass from 0; the output atrim then cuts exactly.
     */
    fun inputTrimStart(chunk: Mp3Chunk): Long? {
        if (inputRate == sampleRate) return null
        val g = gcd(inputRate, sampleRate)
        val outStep = sampleRate / g
        return startPts(chunk) / outStep * (inputRate / g)
    }

    /** Coarse input seek, one second before the cut so the decoder has settled; atrim makes the start exact. */
    fun seekMicros(chunk: Mp3Chunk): Long =
        maxOf(0L, startPts(chunk) * 1_000_000 / sampleRate - SEEK_SLACK_MICROS)

    /** Frames of the chunk's own output that belong to the result: [start, end), end null = to the end. */
    fun keep(chunk: Mp3Chunk): Pair<Int, Int?> = (chunk.first - chunk.from) to chunk.end?.let { it - chunk.from }

    /** A non-last chunk must emit at least its encoded span; LAME's flush adds one more. */
    fun minFrames(chunk: Mp3Chunk): Int = chunk.to?.let { it - chunk.from } ?: (chunk.first - chunk.from + 1)

    /**
     * The chunk's own `-stats_enc_pre` lines prove the seek and trim landed: the first pts is the cut, every frame
     * follows on (pts == cut+sn, sn counting the samples before it) and a non-last chunk fed LAME exactly its span.
     * Returns the samples fed, or null. A late seek or a pts gap anywhere fails here instead of passing the frame count.
     */
    fun chunkSamples(chunk: Mp3Chunk, lines: Sequence<String>): Long? {
        val start = startPts(chunk)
        var total = 0L
        for (line in lines) {
            if (line.isBlank()) continue
            val (pts, sn, samp) = statsLine(line) ?: return null
            if (sn != total || pts != start + sn || samp <= 0) return null
            total += samp
        }
        val span = chunk.to?.let { (it - chunk.from).toLong() * SAMPLES }
        return total.takeIf { if (span != null) it == span else it > (chunk.first - chunk.from).toLong() * SAMPLES }
    }

    /** The exact output length S, from the last chunk's samples. */
    fun samples(lastChunkSamples: Long) = chunks.last().from.toLong() * SAMPLES + lastChunkSamples

    companion object {
        /** Encoders at once, and chunks per file. One pair, tuned on the phone (lead's bench). */
        const val WORKERS = 4
        const val CHUNKS = 8
        const val PRE = 4
        const val POST = 2
        const val MIN_SECONDS = 120
        const val MAX_CHUNK_SECONDS = 300
        private const val SEEK_SLACK_MICROS = 1_000_000L
        private val RATES = setOf(32000, 44100, 48000)

        /** Null means one pass: anything the seam math can't promise is bit-aligned. */
        fun of(source: Mp3Source, chunks: Int = CHUNKS): Mp3ChunkPlan? {
            val probe = source.probe
            val sr = source.outputRate
            // Below 32k LAME writes MPEG-2 frames of 576 samples.
            if (!source.movFamily || !probe.continuous || sr !in RATES || source.inputRate <= 0 || source.constrained) return null
            // No duration, no guess.
            val samples = (source.durationMicros ?: 0L) * sr / 1_000_000
            if (samples < MIN_SECONDS.toLong() * sr || probe.firstPts < 0) return null
            if (source.inputRate != sr && probe.firstPts % (sr / gcd(source.inputRate, sr)) != 0L) return null
            val frames = ceilDiv(samples, SAMPLES.toLong()).toInt()
            val maxPerChunk = (MAX_CHUNK_SECONDS.toLong() * sr / SAMPLES).toInt()
            val count = maxOf(chunks, ceilDiv(frames.toLong(), maxPerChunk.toLong()).toInt())
            val length = ceilDiv(frames.toLong(), count.toLong()).toInt()
            // A non-last chunk's post-roll must be real audio, so a too-short tail joins the chunks before it.
            val starts = (0 until frames step length).toMutableList()
            while (starts.size > 1 && (starts.last() + POST).toLong() * SAMPLES > samples) starts.removeAt(starts.lastIndex)
            if (starts.size < 2) return null
            val list = starts.mapIndexed { index, a ->
                val last = index == starts.lastIndex
                val b = if (last) null else a + length
                Mp3Chunk(index, a, b, maxOf(0, a - PRE), b?.let { it + POST })
            }
            return Mp3ChunkPlan(sr, source.inputRate, probe.firstPts, samples, list)
        }

        private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
