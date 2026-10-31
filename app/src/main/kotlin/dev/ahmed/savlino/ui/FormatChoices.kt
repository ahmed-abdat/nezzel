package dev.ahmed.savlino.ui

import androidx.annotation.StringRes
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.AudioConversion
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.domain.VideoCodecs
import dev.ahmed.savlino.domain.VideoQuality
import dev.ahmed.savlino.locale.NezzelFormat

/**
 * The list people choose from: one real rendition per picture quality, then plain audio choices.
 * Sites return the same quality several times (AV1, VP9, H.264…); the first list keeps only the
 * rendition most phones play and that has the most honest size. The rest stay one tap away under
 * "Other versions" (owner 2026-10-18). Nothing is invented: every row is a format the engine returned.
 */
internal object FormatChoices {
    fun quality(format: Format): Int? = VideoQuality.of(format.width, format.height)

    enum class Tier(@StringRes val title: Int, @StringRes val hint: Int, val from: Int) {
        UHD(R.string.quality_4k, R.string.quality_4k_hint, 2160),
        QHD(R.string.quality_2k, R.string.quality_2k_hint, 1440),
        FULL_HD(R.string.quality_full_hd, R.string.quality_full_hd_hint, 1080),
        HD(R.string.quality_hd, R.string.quality_hd_hint, 720),
        SD(R.string.quality_sd, R.string.quality_sd_hint, 480),
        LOW(R.string.quality_low, R.string.quality_low_hint, 360),
        VERY_LOW(R.string.quality_very_low, R.string.quality_very_low_hint, 240),
        LOWEST(R.string.quality_lowest, R.string.quality_lowest_hint, 0),
    }

    fun tier(quality: Int): Tier = Tier.entries.first { quality >= it.from }

    /** Both lists describe the rendition, not the preset that picked it. */
    @StringRes fun hint(format: Format, formats: List<Format>): Int = when {
        format.animatedGif -> R.string.video_animated_gif_hint
        format.hasVideo -> quality(format)?.let { side ->
            if (formats.filter { it.hasVideo && !it.animatedGif }.all { quality(it)?.let { it <= side } == true })
                R.string.preset_best else tier(side).hint
        } ?: R.string.video_unknown_quality_hint
        else -> R.string.mp3_hint
    }

    /**
     * The tier name each video row shows in one list. Two qualities that share a tier (540p and 480p are
     * both SD) never read the same: the higher one keeps the name, the lower one shows only its "480p".
     * Rows without a reported quality and audio rows map to null.
     */
    fun tierNames(formats: List<Format>): Map<String, Tier?> {
        val used = HashSet<Tier>()
        return formats.mapNotNull { format -> quality(format)?.takeIf { format.hasVideo && !format.animatedGif }?.let { format to it } }
            .sortedByDescending { it.second }
            .associate { (format, quality) -> format.id to tier(quality).takeIf { used.add(it) } }
    }

    /** Rows with the same key read the same in the list (title is derived only from these facts). */
    fun labelKey(format: Format): String = when {
        format.animatedGif -> "gif"
        format.hasVideo -> quality(format)?.let { "video:$it" } ?: "video"
        format.conversion != null -> "mp3"
        else -> "audio"
    }

    fun simple(formats: List<Format>, preferred: Set<String> = emptySet()): List<Format> {
        val videos = formats.filter { it.hasVideo && quality(it) != null }
            .groupBy { quality(it)!! }
            .map { (_, rows) -> rows.firstOrNull { it.id in preferred } ?: rows.maxWith(videoPreference) }
            .sortedByDescending { quality(it) } +
            // A direct file whose quality the site did not report (X/Twitter) stays one plain "Video" row.
            formats.filter { it.hasVideo && quality(it) == null }
        val audio = formats.filter { !it.hasVideo }
        // One audio choice as the site sent it (the best AAC/M4A), then one MP3.
        val m4a = audio.firstOrNull { it.id in preferred && it.conversion == null } ?: audio.filter { it.conversion == null && it.container == "m4a" }
            .maxWithOrNull(compareBy<Format> { sizeRank(it.size) }.thenBy { it.bitrateKbps ?: 0 })
        // Prefer 192 kbps when the source supports it; otherwise keep the offered fallback.
        val mp3 = audio.firstOrNull { it.conversion == AudioConversion.MAIN }
            ?: audio.filter { it.conversion != null }.minByOrNull { it.conversion!!.bitrateKbps }
        // A site with no M4A still keeps one audio-only row of whatever it does offer.
        val fallback = if (m4a == null) audio.filter { it.conversion == null }.maxByOrNull { it.bitrateKbps ?: 0 } else null
        return videos + listOfNotNull(m4a ?: fallback, mp3)
    }

    /** Plain codec name for "Other versions" (H.264, VP9…); unknown codecs show nothing rather than a raw id. */
    fun codecName(format: Format): String? = when (VideoCodecs.family(format.videoCodec)) {
        "avc" -> "H.264"; "hevc" -> "HEVC"; "av1" -> "AV1"; "vp9" -> "VP9"; "vp8" -> "VP8"; else -> null
    }

    /** Audio rate people compare (128 vs 320 kbps); video rows never show one. */
    fun audioKbps(format: Format): Int? = if (format.hasVideo) null else format.conversion?.bitrateKbps ?: format.bitrateKbps

    /** Rows with the same key look identical in "Other versions": title, file type, codec or rate, and size. */
    fun shownKey(format: Format): List<Any?> =
        listOf(labelKey(format), format.container.uppercase(java.util.Locale.ROOT), codecName(format), audioKbps(format),
            exactBytes(format)?.let { NezzelFormat.size(it, "en") }, format.sourceHasNoSound && !format.animatedGif)

    /**
     * A fresh read still offers exactly what the user picked on an older sheet: same ids (audio track, dub and
     * DRC included), file type, picture height and codecs (HDR shows in the codec string). A file now more than
     * 25% bigger than the size they saw is not the same choice on mobile data. Sizes are the shown bytes, or null.
     */
    fun sameChoice(old: Format, fresh: Format, oldBytes: Long?, freshBytes: Long?): Boolean =
        old.id == fresh.id && old.sourceFormatId == fresh.sourceFormatId && old.audioFormatId == fresh.audioFormatId &&
            old.container == fresh.container && old.sourceContainer == fresh.sourceContainer &&
            old.hasVideo == fresh.hasVideo && old.hasAudio == fresh.hasAudio && old.height == fresh.height &&
            old.videoCodec == fresh.videoCodec && old.audioCodec == fresh.audioCodec &&
            old.conversion == fresh.conversion && old.animatedGif == fresh.animatedGif && old.sourceHasNoSound == fresh.sourceHasNoSound &&
            (oldBytes == null || freshBytes == null || freshBytes - oldBytes <= oldBytes / 4)

    private fun sizeRank(size: Size) = when (size) { is Size.Exact -> 2; is Size.Estimated -> 1; Size.Unknown -> 0 }

    // H.264, then HEVC, AV1, VP9 (playback-compatible codecs, 2026-10-19).
    private val videoPreference = compareBy<Format> { VideoCodecs.rank(it.videoCodec) }
        .thenBy { if (it.container == "mp4") 1 else 0 }
        .thenBy { sizeRank(it.size) }
        .thenBy { if (it.hasAudio) 1 else 0 }
        .thenBy { it.bitrateKbps ?: 0 }
}
