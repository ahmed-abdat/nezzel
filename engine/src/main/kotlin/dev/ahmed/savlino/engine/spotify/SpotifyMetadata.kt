package dev.ahmed.savlino.engine.spotify

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.PreviewMetadata
import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import java.net.URI
import okhttp3.HttpUrl
import org.jsoup.Jsoup

/** Public metadata identity only. No Spotify URL is a media source. */
class SpotifyLink private constructor(val type: String, private val id: String) {
    val embedUrl get() = "https://open.spotify.com/embed/$type/$id"
    val oembedUrl get() = HttpUrl.Builder().scheme("https").host("open.spotify.com").addPathSegment("oembed")
        .addQueryParameter("url", "https://open.spotify.com/$type/$id").build().toString()
    override fun toString() = "SpotifyLink(redacted)"

    companion object {
        private val path = Regex("/(?:intl-[a-z]{2}/)?(track|album|playlist)/([A-Za-z0-9]{22})/?")
        fun parse(raw: String): SpotifyLink? {
            val url = HttpsUrl.parse(raw) ?: return null
            if (url.host != "open.spotify.com") return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            return SpotifyLink(match.groupValues[1], match.groupValues[2])
        }
        /** Unsupported Spotify shapes and unresolved Spotify shortlinks must also stay out of yt-dlp. */
        fun owns(raw: String): Boolean = try {
            val host = URI(raw).host?.lowercase().orEmpty().trimEnd('.')
            host == "spotify.com" || host.endsWith(".spotify.com") || host == "spotify.link" || host.endsWith(".spotify.link")
        } catch (_: Exception) { false }
    }
}

class SpotifyTrack(val title: String, val artist: String, val durationMs: Long?) {
    val searchWords get() = "$artist $title".trim()
    override fun toString() = "SpotifyTrack(redacted)"
}

/** Memory-only search hints: never formats, download commands, or file names. */
class SpotifyMetadata(val title: String, val subtitle: String?, val tracks: List<SpotifyTrack>, val isList: Boolean,
    private val fallbackTitle: Boolean = false) {
    val searchWords: String? get() = if (fallbackTitle) title else if (!isList) tracks.singleOrNull()?.searchWords else null
    override fun toString() = "SpotifyMetadata(redacted)"

    companion object {
        internal const val MAX_BYTES = 1024 * 1024
        const val MAX_TRACKS = 100
        private val json = ObjectMapper()
        fun parseEmbed(html: String, type: String): SpotifyMetadata? = try {
            if (html.length > MAX_BYTES) null else {
                val script = Jsoup.parse(html).selectFirst("script[id=__NEXT_DATA__]")?.data()
                val entity = script?.let(json::readTree)?.path("props")?.path("pageProps")?.path("state")?.path("data")?.path("entity")
                entity?.let { parseEntity(it, type) }
            }
        } catch (_: Exception) { null }

        private fun parseEntity(entity: JsonNode, type: String): SpotifyMetadata? {
            val title = text(entity.get("name")) ?: return null
            if (type == "track") {
                val artists = entity.get("artists")?.takeIf { it.isArray && !it.isEmpty } ?: return null
                val names = artists.map { text(it.get("name")) ?: return null }
                return SpotifyMetadata(title, null, listOf(SpotifyTrack(title, names.joinToString(", "), duration(entity))), false)
            }
            if (type !in setOf("album", "playlist")) return null
            val rows = entity.get("trackList")?.takeIf { it.isArray && !it.isEmpty } ?: return null
            val tracks = rows.take(MAX_TRACKS).map { row ->
                SpotifyTrack(text(row.get("title")) ?: return null, text(row.get("subtitle")) ?: return null, duration(row))
            }
            return SpotifyMetadata(title, text(entity.get("subtitle")), tracks, true)
        }

        /** oEmbed names only the item; it cannot invent an album's tracks or a song's artist. */
        fun parseOEmbed(body: String): SpotifyMetadata? = try {
            if (body.length > MAX_BYTES) null else text(json.readTree(body)?.get("title"))?.let {
                SpotifyMetadata(it, null, emptyList(), false, fallbackTitle = true)
            }
        } catch (_: Exception) { null }

        private fun text(node: JsonNode?): String? = PreviewMetadata.text(node?.takeIf { it.isTextual }?.asText(), 300)
        private fun duration(node: JsonNode): Long? = node.get("duration")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
            ?.longValue()?.takeIf { it > 0 }
    }
}
