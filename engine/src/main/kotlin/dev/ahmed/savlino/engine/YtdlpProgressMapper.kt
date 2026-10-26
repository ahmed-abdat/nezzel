package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.Size

/** Numeric yt-dlp template fields; percentages and human-readable rounded units are ignored. */
class YtdlpProgressMapper(private val streamCount: Int = 1, private val expected: Size = Size.Unknown) {
    private data class Stream(val bytes: Long, val total: Size, val fragmentIndex: Long?, val fragmentCount: Long?)
    private val streams = linkedMapOf<String, Stream>()
    private var lastBytes = 0L
    // The percent's basis, fixed by the first event that has one: exact bytes or fragments. Switching later could
    // turn 66% of the bytes into 50% of the fragments, so the percent would fall back.
    private var byFragments: Boolean? = null
    @Synchronized fun map(line: String): EngineEvent? {
        if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]")) return EngineEvent.Merging
        val numericFields = when {
            line.startsWith("nezzel-v2:") -> 5
            line.startsWith("nezzel:") -> 3 // Older templates have no fragment measurements.
            else -> return null
        }
        val fields = line.substringAfter(':').split(':')
        if (fields.size <= numericFields) return null
        // Read numeric fields from the end; format IDs may themselves contain colons.
        val id = fields.dropLast(numericFields).joinToString(":")
        val values = fields.takeLast(numericFields)
        // A stream yt-dlp skips as already downloaded (a resumed merge) reports only its size: it counts in full.
        val bytes = values[0].toLongOrNull()?.takeIf { it >= 0 }
            ?: byteSize(values[1])?.takeIf { values[0] == "NA" } ?: return null
        val total = byteSize(values[1])?.let(Size::Exact)
            ?: byteSize(values[2])?.let(Size::Estimated) ?: Size.Unknown
        val count = values.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0 }
        val index = values.getOrNull(3)?.toLongOrNull()?.takeIf { count != null && it in 0..count }
        val previous = streams[id]
        // Byte-only/final hooks omit fragments, including when some fragments were skipped.
        // Keep the last reported pair without claiming that skipped fragments completed.
        val retained = previous?.takeIf { it.fragmentIndex != null && it.fragmentCount != null &&
            values.getOrNull(3) in listOf(null, "NA") && values.getOrNull(4) in listOf(null, "NA") }
        val advancedIndex = index?.let { maxOf(it, previous?.fragmentIndex ?: 0) }
            ?.takeIf { count != null && it <= count }
        streams[id] = Stream(maxOf(bytes, previous?.bytes ?: 0), total,
            advancedIndex ?: retained?.fragmentIndex, count.takeIf { advancedIndex != null } ?: retained?.fragmentCount)
        val cumulative = streams.values.fold(0L) { acc, s -> add(acc, s.bytes) }
        lastBytes = maxOf(cumulative, lastBytes)
        val measured = if (streams.size < streamCount) Size.Unknown else {
            val totals = streams.values.map { it.total }
            if (totals.any { it == Size.Unknown }) Size.Unknown else {
                val sum = totals.fold(0L) { acc, size -> add(acc, when (size) { is Size.Exact -> size.bytes; is Size.Estimated -> size.bytes; else -> 0 }) }
                if (totals.all { it is Size.Exact }) Size.Exact(sum) else Size.Estimated(sum)
            }
        }
        // The inspection's exact merge total holds until every stream reports its own, so a merge's percent
        // never falls back to a spinner when a later stream's size is missing or only estimated.
        val aggregate = if (streamCount > 1 && measured !is Size.Exact) (expected as? Size.Exact) ?: measured else measured
        // No invented denominator for an unseen stream (e.g. the audio half of a merge).
        val fragments = if (streams.size == streamCount && streams.values.all { it.fragmentIndex != null && it.fragmentCount != null }) {
            try {
                streams.values.fold(0L to 0L) { (index, count), stream ->
                    Math.addExact(index, stream.fragmentIndex!!) to Math.addExact(count, stream.fragmentCount!!)
                }
            } catch (_: ArithmeticException) { null }
        } else null
        if (byFragments == null) byFragments = when {
            aggregate is Size.Exact && aggregate.bytes > 0 -> false
            fragments != null -> true
            else -> null
        }
        val counted = fragments?.takeIf { byFragments == true }
        return EngineEvent.Progress(lastBytes, aggregate, counted?.first, counted?.second)
    }
    private fun byteSize(value: String): Long? = value.toLongOrNull()?.takeIf { it >= 0 }
        ?: value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    private fun add(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
