package dev.ahmed.savlino.domain

import java.util.Collections

enum class MediaKind { IMAGE, VIDEO, AUDIO }

/** Adapter identity is local state, never a source URL or a transfer selector. */
data class MediaAdapterIdentity(val adapterId: String, val recipeVersion: Int,
    val runtimeVersion: String, val generation: Long) {
    init {
        require(adapterId.isNotBlank() && runtimeVersion.isNotBlank())
        require(recipeVersion > 0 && generation >= 0)
    }
    override fun toString() = "MediaAdapterIdentity(redacted)"
}

/** IDs come from adapter results. A URL, list position or display title is not an item ID. */
data class MediaItemRef(val adapterId: String, val itemId: String) {
    init { require(adapterId.isNotBlank() && itemId.isNotBlank()) }
    override fun toString() = "MediaItemRef(redacted)"
}
data class MediaRenditionRef(val item: MediaItemRef, val renditionId: String) {
    init { require(renditionId.isNotBlank()) }
    override fun toString() = "MediaRenditionRef(redacted)"
}

/** Untrusted display metadata, with the same sanitation as legacy Inspection. */
class MediaMetadata(title: String? = null, uploader: String? = null,
    durationSeconds: Long? = null, thumbnailUrl: String? = null) {
    val title = PreviewMetadata.text(title, 300)
    val uploader = PreviewMetadata.text(uploader, 120)
    val durationSeconds = durationSeconds?.takeIf { it in 1..PreviewMetadata.MAX_DURATION_SECONDS }
    val thumbnailUrl = PreviewMetadata.thumbnail(thumbnailUrl)
    override fun toString() = "MediaMetadata(redacted)"
}

/** Reported image encoding; the adapter must verify actual content separately. */
enum class MediaImageFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"), GIF("image/gif", "gif")
}

/** Evidence supplied only after a complete body was measured and digested, never a prefix. */
class MediaImageProof(val completeBodySha256: String, val measuredBytes: Long) {
    init {
        require(completeBodySha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(measuredBytes > 0)
    }
    override fun toString() = "MediaImageProof(redacted)"
}

class MediaImageMetadata(val format: MediaImageFormat, val size: Size = Size.Unknown,
    val width: Int? = null, val height: Int? = null, val proof: MediaImageProof? = null) {
    init {
        require(when (size) {
            is Size.Exact -> size.bytes > 0
            is Size.Estimated -> size.bytes > 0
            Size.Unknown -> true
        })
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(proof == null || size !is Size.Exact || size.bytes == proof.measuredBytes)
    }
    override fun toString() = "MediaImageMetadata(redacted)"
}

sealed interface MediaRendition {
    val ref: MediaRenditionRef
    val outputKind: MediaKind
    val size: Size

    class Audiovisual(override val ref: MediaRenditionRef, val format: Format) : MediaRendition {
        init { require(ref.renditionId == format.id) }
        override val outputKind = if (format.hasVideo) MediaKind.VIDEO else MediaKind.AUDIO
        override val size = format.size
        override fun toString() = "MediaRendition.Audiovisual(redacted)"
    }
    class Image(override val ref: MediaRenditionRef, val image: MediaImageMetadata) : MediaRendition {
        override val outputKind = MediaKind.IMAGE
        override val size = image.size
        override fun toString() = "MediaRendition.Image(redacted)"
    }
}

/**
 * One actual attachment. Renditions are alternatives, not additional carousel items.
 * VIDEO items require returned video evidence here and may also offer audio alternatives.
 * A known video source offering only audio would need a deliberate contract extension.
 * Empty legacy no-format results remain representable and offer no selectable output.
 */
class MediaItem(val ref: MediaItemRef, val position: Int, val sourceKind: MediaKind,
    val metadata: MediaMetadata, renditions: List<MediaRendition>,
    presets: List<PresetChoice> = emptyList(), roughSizeBytes: Map<String, Long> = emptyMap()) {
    val renditions: List<MediaRendition> = mediaSnapshot(renditions)
    val presets: List<PresetChoice> = mediaSnapshot(presets)
    /** Display-only, per returned rendition; never a confirmed transfer budget. */
    val roughSizeBytes: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(roughSizeBytes.filter { (id, bytes) ->
        bytes > 0 && this.renditions.any { it.ref.renditionId == id && it.size == Size.Unknown }
    }))

    init {
        require(position >= 0)
        require(this.renditions.map { it.ref.renditionId }.distinct().size == this.renditions.size)
        require(sourceKind != MediaKind.VIDEO || this.renditions.any { it.outputKind == MediaKind.VIDEO })
        for (rendition in this.renditions) {
            require(rendition.ref.item == ref)
            require(when (sourceKind) {
                MediaKind.IMAGE -> rendition is MediaRendition.Image
                MediaKind.VIDEO -> rendition is MediaRendition.Audiovisual
                MediaKind.AUDIO -> rendition is MediaRendition.Audiovisual && rendition.outputKind == MediaKind.AUDIO
            })
        }
        require(this.presets.map { it.preset }.distinct().size == this.presets.size)
        for (choice in this.presets) {
            val rendition = this.renditions.singleOrNull { it.ref.renditionId == choice.formatId }
            require(rendition is MediaRendition.Audiovisual)
            val row = rendition.format
            require(when (choice.preset) {
                Preset.AUDIO_M4A -> !row.hasVideo && row.hasAudio && row.container == "m4a" && row.conversion == null
                Preset.AUDIO_MP3 -> !row.hasVideo && row.hasAudio && row.container == "mp3"
                else -> row.hasVideo && (row.hasAudio || row.requiresMerge)
            })
        }
    }
    override fun toString() = "MediaItem(redacted)"
}

/** Opaque validity of one inspection. Legacy tokens are process-local and do not expire by time. */
class MediaInspectionValidity private constructor() {
    companion object {
        fun processLocal() = MediaInspectionValidity()
    }
    override fun toString() = "MediaInspectionValidity(redacted)"
}

data class MediaSelection(val source: LinkRevision, val identity: MediaAdapterIdentity,
    val renditionRef: MediaRenditionRef, val outputKind: MediaKind, val validity: MediaInspectionValidity) {
    override fun toString() = "MediaSelection(redacted)"
}

class MediaInspection(val source: LinkRevision, val identity: MediaAdapterIdentity,
    items: List<MediaItem>, val validity: MediaInspectionValidity) {
    val items: List<MediaItem> = mediaSnapshot(items)
    init {
        require(this.items.map { it.ref.itemId }.distinct().size == this.items.size)
        require(this.items.all { it.ref.adapterId == identity.adapterId })
    }

    /** Capture the exact returned row; callers must invalidate pending permission work on change. */
    fun selection(ref: MediaRenditionRef): MediaSelection? {
        val item = items.singleOrNull { it.ref == ref.item } ?: return null
        val rendition = item.renditions.singleOrNull { it.ref == ref } ?: return null
        return MediaSelection(source, identity, rendition.ref, rendition.outputKind, validity)
    }
    override fun toString() = "MediaInspection(redacted)"
}

private fun <T> mediaSnapshot(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
