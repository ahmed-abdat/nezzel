package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.*
import kotlinx.coroutines.CancellationException

/** Separate metadata path: ordinary inspection still rejects playlist/multi-video documents. */
internal class PlaylistLister(
    private val primary: suspend (LinkRevision, PlaylistPageToken?, String) -> PlaylistPage?,
    private val flat: suspend (LinkRevision, Int, Int) -> String,
) {
    suspend fun list(source: LinkRevision, token: PlaylistPageToken? = null,
        operationId: String = java.util.UUID.randomUUID().toString()): PlaylistResult {
        when (YouTubeLinkKind.classify(source.link)) {
            YouTubeLinkKind.PrivateList -> return PlaylistResult.Failure(DownloadError.LoginRequired)
            YouTubeLinkKind.Playlist, YouTubeLinkKind.VideoInList -> Unit
            else -> return PlaylistResult.Failure(DownloadError.Unsupported)
        }
        if (token != null && token.link != source.link.normalizedUrl) return PlaylistResult.Failure(DownloadError.InvalidInput)
        // A flat continuation stays flat. NPE can fail mid-list: flat resumes at the consumed raw offset.
        val native = if (token == null || token.nativePage != null) try { primary(source, token, operationId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null } else null
        val page = native ?: parseFlat(flat(source, (token?.offset ?: 0) + 1, (token?.offset ?: 0) + PAGE_SIZE), source, token)
        val seen = token?.seen.orEmpty().toMutableSet()
        val items = page.items.filter { seen.add(it.id) }
        val next = page.next?.let { PlaylistPageToken(it.link, it.offset, it.nativePage, seen.toSet(), page.title, page.totalCount) }
        return PlaylistResult.Success(PlaylistPage(page.title, items, page.totalCount, next))
    }

    private fun parseFlat(json: String, source: LinkRevision, token: PlaylistPageToken?): PlaylistPage {
        val root = ObjectMapper().readTree(json)
        if (root.path("_type").asText() != "playlist" || !root.path("entries").isArray) throw UnsupportedMetadataException()
        val entries = root.path("entries")
        val items = entries.mapNotNull { row ->
            val id = row.path("id").asText().takeIf { it.matches(VIDEO_ID) } ?: return@mapNotNull null
            val duration = row.path("duration").takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 }
            val title = row.path("title").asText("")
            val unavailable = when {
                row.path("availability").asText() in setOf("private", "needs_auth", "subscriber_only", "premium_only") || title == "[Private video]" -> PlaylistUnavailable.Private
                row.path("live_status").asText() in setOf("is_live", "is_upcoming", "post_live") || row.path("is_live").asBoolean(false) -> PlaylistUnavailable.Live
                title == "[Deleted video]" || row.path("availability").asText() == "unavailable" || duration == null -> PlaylistUnavailable.Unavailable
                else -> null
            }
            PlaylistItem(id, "https://www.youtube.com/watch?v=$id", title, duration,
                row.path("thumbnails").lastOrNull()?.path("url")?.asText(), unavailable)
        }
        val total = root.path("playlist_count").takeIf { it.isIntegralNumber }?.asLong()?.takeIf { it >= 0 } ?: token?.total
        val offset = (token?.offset ?: 0) + entries.size()
        val next = if (entries.size() >= PAGE_SIZE && (total == null || offset < total))
            PlaylistPageToken(source.link.normalizedUrl, offset) else null
        return PlaylistPage(root.path("title").asText(token?.title.orEmpty()), items,
            total ?: offset.toLong().takeIf { next == null }, next)
    }
    companion object {
        const val PAGE_SIZE = 100
        internal val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
