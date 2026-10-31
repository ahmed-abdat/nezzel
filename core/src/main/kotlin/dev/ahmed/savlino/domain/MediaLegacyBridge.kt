package dev.ahmed.savlino.domain

sealed interface MediaConfirmResult {
    data class Confirmed(val command: DownloadCommand) : MediaConfirmResult {
        override fun toString() = "MediaConfirmResult.Confirmed(redacted)"
    }
    data object Stale : MediaConfirmResult
    data class Invalid(val reason: DownloadError) : MediaConfirmResult
}

/** Additive audiovisual bridge. Does not inspect, transfer or confirm image downloads. */
class MediaLegacyBridge private constructor(private val legacy: Inspection, val inspection: MediaInspection) {
    companion object {
        private val jobIdPattern = DownloadCommand.JOB_ID

        /** returnedItemId must be the adapter's actual source item ID, not a generated placeholder. */
        fun wrap(inspection: Inspection, returnedItemId: String, identity: MediaAdapterIdentity): MediaLegacyBridge {
            require(identity.runtimeVersion == inspection.engineVersion)
            val itemRef = MediaItemRef(identity.adapterId, returnedItemId)
            val item = MediaItem(itemRef, 0,
                if (inspection.formats.any { it.hasVideo }) MediaKind.VIDEO else MediaKind.AUDIO,
                MediaMetadata(inspection.title, inspection.uploader, inspection.durationSeconds, inspection.thumbnailUrl),
                inspection.formats.map { MediaRendition.Audiovisual(MediaRenditionRef(itemRef, it.id), it) },
                inspection.presets, inspection.roughSizeBytes)
            return MediaLegacyBridge(inspection, MediaInspection(inspection.source, identity,
                listOf(item), MediaInspectionValidity.processLocal()))
        }
    }

    /** Precedence: job ID, source, supported kind, returned refs, adapter/validity, legacy confirm. */
    fun confirm(jobId: String, selection: MediaSelection, currentSource: LinkRevision,
        currentIdentity: MediaAdapterIdentity, fallbackTitle: String): MediaConfirmResult {
        if (!jobId.matches(jobIdPattern)) return MediaConfirmResult.Invalid(DownloadError.InvalidInput)
        if (!inspection.source.matches(currentSource) || !inspection.source.matches(selection.source))
            return MediaConfirmResult.Stale
        if (selection.outputKind == MediaKind.IMAGE) return MediaConfirmResult.Invalid(DownloadError.Unsupported)
        val item = inspection.items.singleOrNull { it.ref == selection.renditionRef.item }
        val rendition = item?.renditions?.singleOrNull { it.ref == selection.renditionRef }
            ?: return MediaConfirmResult.Invalid(DownloadError.FormatExpired)
        if (rendition !is MediaRendition.Audiovisual) return MediaConfirmResult.Invalid(DownloadError.Unsupported)
        // Legacy Inspection snapshots its inputs but its exposed List can be cast to MutableList.
        // Never let later mutation substitute a different Format when delegating to that original.
        // Predicate singleOrNull also returns null when duplicate IDs make the match ambiguous.
        if (legacy.formats.singleOrNull { it.id == rendition.format.id } !== rendition.format)
            return MediaConfirmResult.Invalid(DownloadError.FormatExpired)
        if (selection.outputKind != rendition.outputKind) return MediaConfirmResult.Invalid(DownloadError.Unsupported)
        if (selection.identity != inspection.identity || currentIdentity != inspection.identity ||
            selection.validity !== inspection.validity) return MediaConfirmResult.Stale
        return when (val result = DownloadCommand.confirm(jobId, legacy, currentSource, rendition.format.id, fallbackTitle)) {
            is ConfirmResult.Confirmed -> MediaConfirmResult.Confirmed(result.command)
            is ConfirmResult.Invalid -> MediaConfirmResult.Invalid(result.reason)
            ConfirmResult.Stale -> MediaConfirmResult.Stale
        }
    }
    override fun toString() = "MediaLegacyBridge(redacted)"
}
