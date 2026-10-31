package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.PreviewMetadata
import org.schabi.newpipe.extractor.Page

/** Which YouTube search tab to read. Videos is the first page of a new search. */
enum class SearchFilter { VIDEOS, MUSIC, PLAYLISTS, CHANNELS }

/** What opening the row does. Music songs are videos: they open a watch link. */
enum class SearchKind { VIDEO, PLAYLIST, CHANNEL }

/** One search row. The words that found it are not stored here. */
class SearchResult(
    val id: String,
    title: String?,
    channel: String?,
    val durationSeconds: Long? = null,
    val kind: SearchKind = SearchKind.VIDEO,
    url: String? = null,
    thumbnailUrl: String? = null,
    val videoCount: Int? = null,
) {
    val title = PreviewMetadata.text(title, 300).orEmpty()
    val channel = PreviewMetadata.text(channel, 100)
    val url = url ?: when (kind) {
        SearchKind.VIDEO -> "https://www.youtube.com/watch?v=$id"
        SearchKind.PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
        SearchKind.CHANNEL -> "https://www.youtube.com/channel/$id/videos"
    }
    /** Videos use YouTube's own 16:9 still. Other kinds keep a checked thumbnail, or none. */
    val thumbnailUrl = thumbnailUrl ?: if (kind == SearchKind.VIDEO && id.matches(PlaylistLister.VIDEO_ID))
        "https://i.ytimg.com/vi/$id/mqdefault.jpg" else null
}

/**
 * Memory only. An NPE continuation can embed the query in its page URL, so this never prints that page.
 * [fallback] is the yt-dlp video search, used only after the first NewPipe page fails.
 */
class SearchPageToken internal constructor(
    internal val nativePage: Page?,
    internal val ytdlpStart: Int?,
    internal val number: Int,
) {
    val fallback: Boolean get() = ytdlpStart != null
    override fun toString() = "SearchPageToken"

    companion object {
        fun fallback(start: Int = 1): SearchPageToken {
            require(start in 1..YouTubeSearch.MAX_RESULTS)
            return SearchPageToken(null, start, ((start - 1) / YouTubeSearch.PAGE_SIZE) + 1)
        }
        /** A stand-in continuation for tests. It carries no query. */
        fun continuation(number: Int = 2): SearchPageToken {
            require(number > 1)
            return SearchPageToken(Page(listOf("p$number")), null, number)
        }
        internal fun npe(page: Page, number: Int): SearchPageToken {
            require(number > 1 && Page.isValid(page))
            return SearchPageToken(page, null, number)
        }
    }
}

class SearchPage(
    val results: List<SearchResult>,
    val next: SearchPageToken? = null,
    /** First NewPipe page had rows, and every one failed the id check. Videos may fall back. */
    val unusable: Boolean = false,
) {
    val hasMore: Boolean get() = next != null
}

sealed interface SearchOutcome {
    class Success(val page: SearchPage) : SearchOutcome
    data class Failure(val error: DownloadError) : SearchOutcome
}

/**
 * yt-dlp reads `ytsearchN:<words>` as a flat playlist. The words are the person's own text: they go after `--` only,
 * and never into logs, errors, diagnostics or usage counts. NewPipe search is the usual path; this one is the
 * video fallback, still capped here.
 */
object YouTubeSearch {
    const val PREFIX = "ytsearch"
    const val PAGE_SIZE = 20
    const val MAX_QUERY = 100
    /** Each yt-dlp page asks YouTube for every result before it again, so that fallback stops here. */
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
        val more = entries.size() >= PAGE_SIZE && start + PAGE_SIZE <= MAX_RESULTS
        return SearchPage(results, if (more) SearchPageToken.fallback(start + PAGE_SIZE) else null)
    }
    private val WHITESPACE = Regex("\\s+")
    private val CONTROL = Regex("\\p{Cntrl}")
}
