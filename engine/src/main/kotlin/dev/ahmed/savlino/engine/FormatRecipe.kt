package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.domain.VideoCodecs

/**
 * Whether a fresh extraction still offers what the user confirmed, compared on the confirmed row's own
 * streams (not on a re-paired row). Only what the user picked counts: kind, dimensions, container, codec
 * families and an exact size. Protocol, fps rounding, notes and bitrates vary between extractions of the
 * same video. A fact the confirmed row lacked is not compared.
 */
internal object FormatRecipe {
    fun same(confirmed: Format, streams: Map<String, Format>): Boolean {
        val source = streams[confirmed.sourceFormatId] ?: return false
        val audio = confirmed.audioFormatId?.let { streams[it] ?: return false }
        if (audio != null && (audio.hasVideo || !audio.hasAudio)) return false
        // Extracted M4A and MP3 rows are derived from a returned source: it only has to carry the audio.
        val derived = confirmed.conversion != null || (!confirmed.hasVideo && confirmed.id != confirmed.sourceFormatId)
        val sound = audio ?: source
        if (derived) {
            if (confirmed.hasVideo || !source.hasAudio) return false
        } else if (confirmed.hasVideo != source.hasVideo || confirmed.hasAudio != (source.hasAudio || audio != null)) return false
        if (confirmed.hasVideo && !(known(confirmed.width, source.width) && known(confirmed.height, source.height) &&
                known(VideoCodecs.family(confirmed.videoCodec), VideoCodecs.family(source.videoCodec)))) return false
        if (confirmed.conversion == null && !known(audioFamily(confirmed.audioCodec), audioFamily(sound.audioCodec))) return false
        if (confirmed.sourceContainer != null && !confirmed.sourceContainer.equals(sound.container, ignoreCase = true)) return false
        // A merge's container follows from its codecs (MP4 needs AAC); a derived row's is its own recipe.
        if (audio != null && confirmed.container == "mp4" && audioFamily(audio.audioCodec) !in setOf("aac", null)) return false
        if (!derived && audio == null && !confirmed.container.equals(source.container, ignoreCase = true)) return false
        val fresh = if (audio == null) source.size else exactSum(source.size, audio.size)
        return derived || confirmed.size !is Size.Exact || fresh !is Size.Exact || confirmed.size == fresh
    }

    private fun <T> known(confirmed: T?, fresh: T?) = confirmed == null || confirmed == fresh

    private fun exactSum(a: Size, b: Size): Size =
        if (a is Size.Exact && b is Size.Exact && a.bytes <= Long.MAX_VALUE - b.bytes) Size.Exact(a.bytes + b.bytes) else Size.Unknown

    internal fun audioFamily(codec: String?): String? {
        val base = codec?.lowercase()?.substringBefore('.')?.takeIf { it.isNotBlank() && it != "none" } ?: return null
        return if (base == "mp4a") "aac" else base
    }
}
