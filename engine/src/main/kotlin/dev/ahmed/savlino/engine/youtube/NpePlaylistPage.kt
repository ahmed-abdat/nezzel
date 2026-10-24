package dev.ahmed.savlino.engine.youtube

import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.*
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI

internal object NpePlaylistPage {
    fun read(source: LinkRevision, token: PlaylistPageToken?): PlaylistPage {
        val url = source.link.rawUrl
        val title: String?
        val total: Long?
        val rows: List<StreamInfoItem>
        val next: Page?
        if (token?.nativePage == null) {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            title = info.name; total = info.streamCount.takeIf { it >= 0 }
            rows = info.relatedItems; next = info.nextPage
        } else {
            val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, token.nativePage)
            title = token.title; total = token.total
            rows = page.items; next = page.nextPage
        }
        val items = rows.mapNotNull(::item)
        val offset = (token?.offset ?: 0) + rows.size
        val continuation = next?.takeIf(Page::isValid)?.let { PlaylistPageToken(source.link.normalizedUrl, offset, it) }
        return PlaylistPage(title, items, total ?: offset.toLong().takeIf { continuation == null }, continuation)
    }

    internal fun item(row: StreamInfoItem): PlaylistItem? {
        val link = LinkInput.parse(row.url) ?: return null
        if (dev.ahmed.savlino.domain.YouTubeLinkKind.classify(link) != dev.ahmed.savlino.domain.YouTubeLinkKind.Video) return null
        val id = URI(link.normalizedUrl).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substring(2)
            ?.takeIf { it.matches(PlaylistLister.VIDEO_ID) } ?: return null
        val duration = row.duration.takeIf { it > 0 }
        val unavailable = when {
            row.name == "[Private video]" || row.contentAvailability.name in setOf("MEMBERSHIP", "PAID") -> PlaylistUnavailable.Private
            row.contentAvailability.name == "UPCOMING" || row.streamType in setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM, StreamType.POST_LIVE_STREAM,
                StreamType.POST_LIVE_AUDIO_STREAM) -> PlaylistUnavailable.Live
            row.name == "[Deleted video]" || row.contentAvailability.name !in setOf("AVAILABLE", "UNKNOWN") || duration == null -> PlaylistUnavailable.Unavailable
            else -> null
        }
        return PlaylistItem(id, "https://www.youtube.com/watch?v=$id", row.name, duration,
            row.thumbnails.lastOrNull()?.url, unavailable)
    }
}
