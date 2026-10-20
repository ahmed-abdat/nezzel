package dev.ahmed.savlino.domain

/**
 * Playback-compatible codecs (OPEN_DECISIONS 2026-10-19). Within one height H.264 wins, then HEVC, AV1, VP9.
 * MP4 merges use these playback-safe video codecs with AAC; VP9/Opus merges use MKV.
 * An absent codec is unknown, never evidence of either.
 */
object VideoCodecs {
    fun family(codec: String?): String? = codec?.lowercase()?.let { when {
        it.startsWith("avc") || it == "h264" -> "avc"
        it.startsWith("hvc1") || it.startsWith("hev1") || it == "hevc" || it == "h265" -> "hevc"
        it.startsWith("av01") || it == "av1" -> "av1"
        it.startsWith("vp09") || it.startsWith("vp9") -> "vp9"
        it.startsWith("vp08") || it.startsWith("vp8") -> "vp8"
        else -> it
    } }

    /** Higher plays on more phones. Unknown or other codecs rank lowest, with VP9. */
    fun rank(codec: String?): Int = when (family(codec)) { "avc" -> 3; "hevc" -> 2; "av1" -> 1; else -> 0 }

    /** Codecs that play from an .mp4 container on current Android players. */
    fun mp4Safe(codec: String?): Boolean = family(codec) in setOf("avc", "hevc", "av1")

    /** Less compatible video yields to a usable mp4Safe rendition at the same height. */
    fun knownMp4Unsafe(codec: String?): Boolean = family(codec) in setOf("vp9", "vp8")
}
