package dev.ahmed.savlino.domain

sealed interface Size {
    data class Exact(val bytes: Long) : Size { init { require(bytes >= 0) } }
    data class Estimated(val bytes: Long) : Size { init { require(bytes >= 0) } }
    data object Unknown : Size
}
enum class Preset { BEST, BALANCED, SMALL, AUDIO_M4A, AUDIO_MP3 }
enum class AudioConversion(val bitrateKbps: Int) {
    MP3_128(128), MP3_192(192), MP3_320(320);
    companion object {
        /** The MP3 in the main list and the MP3 preset; the others stay in Other options. */
        val MAIN = MP3_192
    }
}
data class PresetChoice(val preset: Preset, val formatId: String)
data class Format(val id: String, val container: String, val hasVideo: Boolean, val hasAudio: Boolean,
    val videoCodec: String? = null, val audioCodec: String? = null, val width: Int? = null,
    val height: Int? = null, val bitrateKbps: Int? = null, val size: Size = Size.Unknown,
    val audioFormatId: String? = null, val requiresMerge: Boolean = false,
    val conversion: AudioConversion? = null, val sourceFormatId: String = id,
    val sourceContainer: String? = null,
    /** X/Twitter animated GIF: the engine's file came from X's `tweet_video/` path, which carries no sound. */
    val animatedGif: Boolean = false) {
    /**
     * A returned complete video with unreported audio facts (direct files or a sole HLS MP4).
     * Known video codecs survive; unknown audio is never evidence for extraction/conversion.
     */
    val unreportedStreams: Boolean get() = hasVideo && hasAudio && audioCodec == null &&
        !requiresMerge && conversion == null
    init {
        require(id.isNotBlank() && sourceFormatId.isNotBlank())
        require(container.matches(Regex("[a-zA-Z0-9]{1,10}")))
        require(sourceContainer == null || (conversion != null && sourceContainer.matches(Regex("[a-zA-Z0-9]{1,10}"))))
        require(hasVideo || hasAudio)
        require(!animatedGif || hasVideo)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(bitrateKbps == null || bitrateKbps > 0)
        require(audioFormatId == null || audioFormatId.isNotBlank())
        require(requiresMerge == (audioFormatId != null))
        require(!requiresMerge || hasVideo)
        require(conversion == null || (!hasVideo && hasAudio && container == "mp3" && !requiresMerge))
    }
}
