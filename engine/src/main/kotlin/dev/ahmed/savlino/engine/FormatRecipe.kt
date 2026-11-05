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

    /** Already-confirmed jobs only: ids and byte counts may change, the chosen quality/recipe may not. */
    fun equivalent(confirmed: Format, streams: Map<String, Format>): Format? {
        if (same(confirmed, streams)) return confirmed
        val derived = confirmed.conversion != null || (!confirmed.hasVideo && confirmed.id != confirmed.sourceFormatId)
        if (derived) {
            val oldSource = streams[confirmed.sourceFormatId]
            val family = audioFamily(oldSource?.audioCodec) ?: if (confirmed.conversion == null) audioFamily(confirmed.audioCodec)
                else when (confirmed.sourceContainer?.lowercase()) { "m4a" -> "aac"; "mp3" -> "mp3"; else -> null }
            val source = streams.values.filter {
                it.hasAudio && known(family, audioFamily(it.audioCodec)) &&
                    (confirmed.sourceContainer == null || confirmed.sourceContainer.equals(it.container, true)) &&
                    // Lossless M4A extraction and MP3 encoding keep their respective source recipes.
                    (confirmed.conversion != null || it.hasVideo)
            }.singleOrNull() ?: return null
            val conversion = confirmed.conversion
            return confirmed.copy(id = if (conversion != null)
                "nezzel:mp3:${conversion.bitrateKbps}:${source.id}" else "nezzel:m4a:${source.id}",
                sourceFormatId = source.id, sourceContainer = source.container.takeIf { confirmed.conversion != null },
                size = Size.Unknown)
        }
        val candidates = streams.values.filter { source ->
            source.hasVideo == confirmed.hasVideo &&
                source.hasAudio == (confirmed.hasAudio && !confirmed.requiresMerge) &&
                (!confirmed.hasVideo || known(confirmed.width, source.width) && known(confirmed.height, source.height) &&
                    VideoCodecs.family(confirmed.videoCodec) == VideoCodecs.family(source.videoCodec)) &&
                (confirmed.requiresMerge || confirmed.container.equals(source.container, true) &&
                    audioFamily(confirmed.audioCodec) == audioFamily(source.audioCodec))
        }
        if (!confirmed.requiresMerge) return candidates.singleOrNull()?.let {
            if (confirmed.sourceHasNoSound) it.copy(sourceHasNoSound = true) else it
        }
        // Older jobs lack partner facts: use a surviving partner, or the MP4 AAC/M4A recipe.
        // Without its size, closeness to the stored total is the remaining size evidence.
        val oldAudio = streams[confirmed.audioFormatId]
        val family = audioFamily(confirmed.audioCodec) ?: audioFamily(oldAudio?.audioCodec)
            ?: "aac".takeIf { confirmed.container == "mp4" }
        if (family == null) return null
        val container = confirmed.audioContainer ?: oldAudio?.container ?: "m4a".takeIf { confirmed.container == "mp4" }
        val audio = streams.values.filter { !it.hasVideo && it.hasAudio &&
            known(family, audioFamily(it.audioCodec)) && (container == null || container.equals(it.container, true)) }
        val video = candidates.singleOrNull() ?: return null
        val partners = audio.filter { sound ->
            val output = if (VideoCodecs.mp4Safe(video.videoCodec) && audioFamily(sound.audioCodec) == "aac") "mp4" else "mkv"
            confirmed.container.equals(output, true)
        }
        val audioSize = confirmed.audioSize.takeUnless { it == Size.Unknown } ?: oldAudio?.size
        val audioBitrate = confirmed.audioBitrateKbps ?: oldAudio?.bitrateKbps
        fun rank(sound: Format): Pair<Long, Long> =
            (if (audioSize != null) distance(audioSize, sound.size)
                else distance(confirmed.size, exactSum(video.size, sound.size))) to
                (audioBitrate?.let { old -> sound.bitrateKbps?.let { kotlin.math.abs(old.toLong() - it) } } ?: Long.MAX_VALUE)
        val ranked = partners.groupBy(::rank)
        val best = ranked.keys.minWithOrNull(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        val sound = ranked[best]?.singleOrNull() ?: return null
        return video.copy(container = confirmed.container, hasAudio = confirmed.hasAudio, audioFormatId = sound.id,
            requiresMerge = true, audioCodec = sound.audioCodec, size = exactSum(video.size, sound.size),
            audioContainer = sound.container, audioSize = sound.size, audioBitrateKbps = sound.bitrateKbps)
    }

    private fun distance(a: Size, b: Size): Long {
        fun bytes(size: Size): Long? = when (size) {
            is Size.Exact -> size.bytes
            is Size.Estimated -> size.bytes
            Size.Unknown -> null
        }
        val x = bytes(a) ?: return Long.MAX_VALUE
        val y = bytes(b) ?: return Long.MAX_VALUE
        return kotlin.math.abs(x - y)
    }

    private fun <T> known(confirmed: T?, fresh: T?) = confirmed == null || confirmed == fresh

    private fun exactSum(a: Size, b: Size): Size =
        if (a is Size.Exact && b is Size.Exact && a.bytes <= Long.MAX_VALUE - b.bytes) Size.Exact(a.bytes + b.bytes) else Size.Unknown

    internal fun audioFamily(codec: String?): String? {
        val base = codec?.lowercase()?.substringBefore('.')?.takeIf { it.isNotBlank() && it != "none" } ?: return null
        return if (base == "mp4a") "aac" else base
    }
}
