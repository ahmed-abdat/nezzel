package dev.ahmed.savlino.domain

data class LinkRevision(val link: ValidatedLink, val revision: Long) {
    init { require(revision >= 0) }
    fun matches(other: LinkRevision): Boolean = revision == other.revision && link.rawUrl == other.link.rawUrl
}
class Inspection(val source: LinkRevision, val engineVersion: String, formats: List<Format>, presets: List<PresetChoice> = emptyList(),
    title: String? = null, uploader: String? = null, durationSeconds: Long? = null, thumbnailUrl: String? = null, roughSizeBytes: Map<String, Long> = emptyMap()) {
    /** Opaque memory-cache identity; never persisted in the job journal. */
    val inspectionId: String = java.util.UUID.randomUUID().toString()
    val title = PreviewMetadata.text(title, 300)
    val uploader = PreviewMetadata.text(uploader, 120)
    val durationSeconds = durationSeconds?.takeIf { it in 1..PreviewMetadata.MAX_DURATION_SECONDS }
    val thumbnailUrl = PreviewMetadata.thumbnail(thumbnailUrl)
    val formats = formats.toList()
    val presets = presets.toList()
    /** Display-only metadata. Never replaces Format.size or a confirmed transfer budget. */
    val roughSizeBytes = roughSizeBytes.filter { (id, bytes) -> bytes > 0 && this.formats.any { it.id == id && it.size == Size.Unknown } }.toMap()
    init {
        require(engineVersion.isNotBlank())
        require(this.formats.map { it.id }.distinct().size == this.formats.size)
        require(this.presets.map { it.preset }.distinct().size == this.presets.size)
        for (choice in this.presets) {
            val row = this.formats.singleOrNull { it.id == choice.formatId }
            require(row != null)
            when (choice.preset) {
                Preset.AUDIO_M4A -> require(!row.hasVideo && row.hasAudio && row.container == "m4a" && row.conversion == null)
                Preset.AUDIO_MP3 -> require(!row.hasVideo && row.hasAudio && row.container == "mp3")
                else -> require(row.hasVideo && (row.hasAudio || row.requiresMerge || row.expectsNoSound))
            }
        }
    }
}
sealed interface ConfirmResult {
    data class Confirmed(val command: DownloadCommand) : ConfirmResult
    data object Stale : ConfirmResult
    data class Invalid(val reason: DownloadError) : ConfirmResult
}

class DownloadCommand private constructor(val jobId: String, val source: LinkRevision,
    val engineVersion: String, val format: Format, val filename: String, val inspectionId: String) {
    companion object {
        /** Same rule as the job directory: one leading letter or digit, then up to 79 more, 80 in total. */
        internal val JOB_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")

        fun confirm(jobId: String, inspection: Inspection, current: LinkRevision, formatId: String, title: String): ConfirmResult {
            if (!jobId.matches(JOB_ID)) return ConfirmResult.Invalid(DownloadError.InvalidInput)
            if (!inspection.source.matches(current)) return ConfirmResult.Stale
            val format = inspection.formats.singleOrNull { it.id == formatId }
                ?: return ConfirmResult.Invalid(DownloadError.FormatExpired)
            // A silent source is complete without audio; other video-only rows need their partner.
            if (format.hasVideo && !format.hasAudio && !format.requiresMerge && !format.expectsNoSound)
                return ConfirmResult.Invalid(DownloadError.Unsupported)
            return ConfirmResult.Confirmed(DownloadCommand(jobId, inspection.source, inspection.engineVersion, format,
                FilenameSanitizer.sanitize(inspection.title?.takeIf { it.isNotBlank() } ?: title, format.container), inspection.inspectionId))
        }
    }
}
