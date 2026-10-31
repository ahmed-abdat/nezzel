package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.PreviewMetadata

/** One video yt-dlp's YouTube search returned; opening it is the same path as a pasted watch link. */
class SearchResult(val id: String, title: String?, channel: String?, val durationSeconds: Long?) {
    val title = PreviewMetadata.text(title, 300).orEmpty()
    val channel = PreviewMetadata.text(channel, 100)
    val url get() = "https://www.youtube.com/watch?v=$id"
    /** YouTube's own 16:9 still for this id (320×180), small enough for a list. */
    val thumbnailUrl get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}
class SearchPage(val results: List<SearchResult>, val hasMore: Boolean)
sealed interface SearchOutcome {
    class Success(val page: SearchPage) : SearchOutcome
    data class Failure(val error: DownloadError) : SearchOutcome
}

/**
 * yt-dlp reads `ytsearchN:<words>` as a flat playlist. The words are the person's own text: they go after `--` only,
 * and never into logs, errors, diagnostics or usage counts.
 */
object YouTubeSearch {
    const val PREFIX = "ytsearch"
    const val PAGE_SIZE = 20
    const val MAX_QUERY = 100
    /** Each page asks YouTube for every result before it again, so the list stops here. */
    const val MAX_RESULTS = 100

    fun query(text: String): String? =
        text.replace(CONTROL, " ").trim().replace(WHITESPACE, " ").take(MAX_QUERY).trim().takeIf { it.isNotEmpty() }

    /** [start] is 1-based; one call returns at most [PAGE_SIZE] rows. */
    fun args(query: String, start: Int): List<String> {
        val words = requireNotNull(query(query))
        require(start > 0 && start <= MAX_RESULTS)
        val end = minOf(start + PAGE_SIZE - 1, MAX_RESULTS)
        return listOf("--ignore-config", "--yes-playlist", "--flat-playlist", "--dump-single-json", "--skip-download",
            "--playlist-items", "$start:$end") + YtdlpCommand.NETWORK + listOf("--", "$PREFIX$end:$words")
    }

    /** Rows whose id isn't an exact YouTube video id (channels, playlists, anything odd) are dropped. */
    fun parse(json: String, start: Int): SearchPage {
        val root = ObjectMapper().readTree(json)
        if (root.path("_type").asText() != "playlist" || !root.path("entries").isArray) throw UnsupportedMetadataException()
        val entries = root.path("entries")
        val results = entries.mapNotNull { row ->
            val id = row.path("id").asText().takeIf { it.matches(PlaylistLister.VIDEO_ID) } ?: return@mapNotNull null
            SearchResult(id, row.path("title").asText(""),
                row.path("channel").asText("").ifEmpty { row.path("uploader").asText("") },
                row.path("duration").takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 })
        }.distinctBy { it.id }
        return SearchPage(results, entries.size() >= PAGE_SIZE && start + PAGE_SIZE <= MAX_RESULTS)
    }
    private val WHITESPACE = Regex("\\s+")
    private val CONTROL = Regex("\\p{Cntrl}")
}
