package dev.ahmed.savlino.engine.youtube

import dev.ahmed.savlino.domain.PreviewMetadata
import dev.ahmed.savlino.engine.PlaylistLister
import dev.ahmed.savlino.engine.SearchFilter
import dev.ahmed.savlino.engine.SearchKind
import dev.ahmed.savlino.engine.SearchPage
import dev.ahmed.savlino.engine.SearchPageToken
import dev.ahmed.savlino.engine.SearchResult
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * YouTube search through NewPipe's continuation pages. The query is handed to the extractor and nowhere else:
 * failures keep no message, because an extractor message can echo the words.
 */
internal object NpeSearch {
    fun read(query: String, filter: SearchFilter, page: SearchPageToken?): SearchPage {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(content(filter)), "")
        val fetched = try {
            if (page?.nativePage == null) extractor.getInitialPage() else extractor.getPage(page.nativePage)
        } catch (_: SearchExtractor.NothingFoundException) {
            return SearchPage(emptyList(), null)
        }
        val raw = fetched.items.orEmpty()
        // A real miss is an empty page. Errors with no rows are a failed read, and the message stays here.
        if (raw.isEmpty() && fetched.errors.orEmpty().isNotEmpty()) throw IOException("Extraction request failed")
        val results = raw.mapNotNull { item(it, filter) }.distinctBy { it.id }
        val more = fetched.nextPage?.takeIf { Page.isValid(it) }?.let { SearchPageToken.npe(it, (page?.number ?: 1) + 1) }
        val unusable = page?.nativePage == null && raw.isNotEmpty() && results.isEmpty()
        return SearchPage(results, if (unusable) null else more, unusable = unusable)
    }

    /** One NewPipe row, or null when its id, kind or host is not one this filter opens. */
    fun item(row: InfoItem, filter: SearchFilter): SearchResult? = when (filter) {
        SearchFilter.VIDEOS, SearchFilter.MUSIC -> video(row, filter)
        SearchFilter.PLAYLISTS -> playlist(row)
        SearchFilter.CHANNELS -> channel(row)
    }

    private fun video(row: InfoItem, filter: SearchFilter): SearchResult? {
        val stream = row as? StreamInfoItem ?: return null
        if (filter == SearchFilter.VIDEOS && stream.isShortFormContent) return null
        val type = stream.streamType
        val allowed = when (filter) {
            SearchFilter.VIDEOS -> type == StreamType.VIDEO_STREAM
            SearchFilter.MUSIC -> type == StreamType.VIDEO_STREAM || type == StreamType.AUDIO_STREAM
            else -> false
        }
        if (!allowed) return null
        val id = videoId(stream.url, filter) ?: return null
        return SearchResult(id, stream.name, stream.uploaderName, stream.duration.takeIf { it > 0 })
    }

    private fun playlist(row: InfoItem): SearchResult? {
        val item = row as? PlaylistInfoItem ?: return null
        val type = item.playlistType
        if (type != null && type != PlaylistInfo.PlaylistType.NORMAL) return null
        val id = playlistId(item.url) ?: return null
        val count = item.streamCount.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt()
        return SearchResult(id, item.name, item.uploaderName, null, SearchKind.PLAYLIST,
            thumbnailUrl = thumb(item.thumbnails), videoCount = count)
    }

    private fun channel(row: InfoItem): SearchResult? {
        val item = row as? ChannelInfoItem ?: return null
        val id = channelId(item.url) ?: return null
        return SearchResult(id, item.name, null, null, SearchKind.CHANNEL, thumbnailUrl = thumb(item.thumbnails))
    }

    private fun content(filter: SearchFilter) = when (filter) {
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.MUSIC -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
        SearchFilter.CHANNELS -> YoutubeSearchQueryHandlerFactory.CHANNELS
    }

    private fun videoId(url: String, filter: SearchFilter): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host?.let(::youtubeHost) != true) return null
        val path = uri.rawPath.orEmpty()
        if (filter == SearchFilter.VIDEOS && (path.startsWith("/shorts/") || query(uri, "list")?.startsWith("RD") == true)) return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path == "/watch" -> query(uri, "v")
            path.startsWith("/embed/") || path.startsWith("/live/") || path.startsWith("/shorts/") ->
                path.substringAfterLast('/').substringBefore('/')
            else -> null
        } ?: return null
        return id.takeIf { it.matches(PlaylistLister.VIDEO_ID) }
    }

    private fun playlistId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host?.let(::youtubeHost) != true) return null
        return query(uri, "list")?.takeIf { it.matches(PLAYLIST_ID) }
    }

    private fun channelId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host?.let(::youtubeHost) != true) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (!path.startsWith("/channel/")) return null
        val id = path.removePrefix("/channel/")
        if ('/' in id) return null
        return id.takeIf { it.matches(CHANNEL_ID) }
    }

    private fun thumb(images: List<Image>?): String? = images.orEmpty().sortedBy { image ->
        val width = image.width
        if (width <= 0) Int.MAX_VALUE else kotlin.math.abs(width - 320)
    }.firstNotNullOfOrNull { allowedThumbnail(it.url) }

    /** Same preview gate as the list loader, plus the image hosts a channel avatar or playlist still uses. */
    internal fun allowedThumbnail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val https = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> "https://" + raw.substring(7)
            raw.startsWith("//") -> "https:$raw"
            else -> return null
        }
        val accepted = PreviewMetadata.thumbnail(https) ?: return null
        val host = runCatching { URI(accepted).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        return accepted.takeIf { host in THUMBNAIL_HOSTS }
    }

    private fun youtubeHost(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT).removePrefix("www.")
        return value == "youtu.be" || value.endsWith(".youtu.be") ||
            value == "youtube.com" || value.endsWith(".youtube.com") ||
            value == "youtube-nocookie.com" || value.endsWith(".youtube-nocookie.com")
    }

    private fun query(uri: URI, name: String): String? = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
        val eq = part.indexOf('=')
        if (eq > 0 && part.substring(0, eq) == name) part.substring(eq + 1).takeIf { it.isNotEmpty() } else null
    }

    private val PLAYLIST_ID = Regex("PL[A-Za-z0-9_-]{10,48}|UU[A-Za-z0-9_-]{22}")
    private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
    private val THUMBNAIL_HOSTS = setOf("i.ytimg.com", "img.youtube.com", "yt3.ggpht.com", "yt3.googleusercontent.com")
}

/** Blocking on the caller thread. Does not take the engine lock or the single fast-source slot. */
internal class NpeSearchSource(private val downloader: NpeDownloader = NpeDownloader.create()) {
    fun search(query: String, filter: SearchFilter, page: SearchPageToken?, operationId: String, timeoutMs: Long): SearchPage {
        if (timeoutMs <= 0) throw IOException("Extraction stopped")
        downloader.install()
        val started = System.nanoTime()
        val result = downloader.operation(operationId, timeoutMs) { NpeSearch.read(query, filter, page) }
        if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) throw IOException("Extraction stopped")
        return result
    }

    fun cancel(operationId: String) {
        try { downloader.cancel(operationId) }
        catch (_: Exception) { /* The view model drops a late page. No extractor text is kept. */ }
        catch (_: LinkageError) { /* Older Android runtimes may lack a desugared API. */ }
    }
}
