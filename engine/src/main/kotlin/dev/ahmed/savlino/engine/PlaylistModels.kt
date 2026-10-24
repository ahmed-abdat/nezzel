package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.PreviewMetadata
import dev.ahmed.savlino.domain.DownloadError

/** Rows remain visible when disabled; no URL/title-bearing generated toString. */
class PlaylistItem(val id: String, val sourceUrl: String, title: String?, val durationSeconds: Long?,
    thumbnailUrl: String? = null, val unavailable: PlaylistUnavailable? = null) {
    val title = PreviewMetadata.text(title, 300).orEmpty()
    val thumbnailUrl = PreviewMetadata.thumbnail(thumbnailUrl)
    val selectable get() = unavailable == null && durationSeconds != null && durationSeconds > 0
}
enum class PlaylistUnavailable { Private, Live, Unavailable }

/** Memory-only, bound to the link and listing backend. Never persisted or rendered. */
class PlaylistPageToken internal constructor(internal val link: String, internal val offset: Int,
    internal val nativePage: org.schabi.newpipe.extractor.Page? = null,
    internal val seen: Set<String> = emptySet(), internal val title: String? = null, internal val total: Long? = null)

class PlaylistPage(title: String?, val items: List<PlaylistItem>, val totalCount: Long?, val next: PlaylistPageToken?) {
    val title = PreviewMetadata.text(title, 300).orEmpty()
}
sealed interface PlaylistResult {
    class Success(val page: PlaylistPage) : PlaylistResult
    data class Failure(val error: DownloadError) : PlaylistResult
}
