package dev.ahmed.savlino.domain

import java.net.URI
import java.net.URLDecoder

/** Syntactic only. In particular a video shared from a list stays a video by default. */
enum class YouTubeLinkKind {
    Video, VideoInList, Playlist, Mix, ChannelVideos, PrivateList, Other;

    companion object {
        fun classify(text: String?): YouTubeLinkKind = LinkInput.parse(text)?.let(::classify) ?: Other

        fun classify(link: ValidatedLink): YouTubeLinkKind {
            if (link.host != Host.YOUTUBE) return Other
            return try {
                val uri = URI(link.normalizedUrl)
                val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
                    val parts = it.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                }.groupBy({ it.first }, { it.second })
                if (query["list"].orEmpty().size > 1 || query["v"].orEmpty().size > 1) return Other
                val list = query["list"]?.singleOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) && (it.length >= 10 || it in setOf("WL", "LL") || it.startsWith("RD")) }
                val path = uri.path.orEmpty().trimEnd('/')
                val short = uri.host.equals("youtu.be", true) || uri.host.endsWith(".youtu.be", true)
                val video = if (short) path.removePrefix("/").takeIf { '/' !in it && it.isNotBlank() }
                    else if (path == "/watch") query["v"]?.singleOrNull()?.takeIf { it.isNotBlank() }
                    else if (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/")) path.substringAfterLast('/')
                    else null
                val listPath = path == "/playlist" || path == "/watch" || (short && video != null)
                when {
                    listPath && list in setOf("WL", "LL") -> PrivateList
                    listPath && list?.startsWith("RD") == true -> Mix
                    video != null && list != null -> VideoInList
                    video != null -> Video
                    path == "/playlist" && list != null -> Playlist
                    path.matches(Regex("/(?:@[^/]+|channel/[^/]+)/videos")) -> ChannelVideos
                    else -> Other
                }
            } catch (_: Exception) { Other }
        }
    }
}
