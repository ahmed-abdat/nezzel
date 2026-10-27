package dev.ahmed.savlino.engine.reddit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.domain.PreviewMetadata
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** What a public post page holds. Memory only; never logged. */
sealed interface RedditPost {
    /** A Reddit-hosted video: [manifestUrl] is its v.redd.it DASH manifest, built from the id alone. */
    class Video(val videoId: String, val title: String?, val author: String?, val thumbnailUrl: String?,
        val durationSeconds: Long?) : RedditPost {
        val manifestUrl: String get() = "https://v.redd.it/$videoId/DASHPlaylist.mpd"
        override fun toString() = "RedditPost.Video(redacted)"
    }
    /** A link post: the page it links to, as yt-dlp's reddit extractor hands it on. */
    class Link(val url: String) : RedditPost { override fun toString() = "RedditPost.Link(redacted)" }
    /** Text, photo, gallery or poll: the post exists but holds no video. */
    data object NoVideo : RedditPost
    /** Reddit asks logged-out readers to sign in (18+ post). */
    data object LoginRequired : RedditPost
    /** Not a shape this parser knows (crosspost, a changed page): the caller keeps the old path. */
    data object Unknown : RedditPost
}

/** Reads only the requested post's own `<shreddit-post>`; feed items, ads and comments never qualify. */
internal object RedditPostParser {
    private val videoId = Regex("[a-z0-9]{8,24}")
    private val noVideoTypes = setOf("text", "self", "image", "gallery", "poll")
    private val json = ObjectMapper()

    fun parse(html: ByteArray, length: Int, postId: String, expectedVideoId: String? = null): RedditPost {
        if (length !in 0..minOf(html.size, MediaPageLimits.REDDIT)) return RedditPost.Unknown
        return try {
            val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(128))
            if (document.getAllElements().size > 60_000) return RedditPost.Unknown
            val post = document.select("shreddit-post").filter { it.attr("id") == "t3_$postId" }.singleOrNull()
                ?: return RedditPost.Unknown
            val type = post.attr("post-type")
            // A post with no video says so, 18+ or not; only an 18+ video asks for sign-in.
            if (type in noVideoTypes) return RedditPost.NoVideo
            if (post.attributes().any { "nsfw" in it.key.lowercase() }) return RedditPost.LoginRequired
            val content = MediaHttpPolicy.parse(post.attr("content-href"))
            when {
                type == "video" -> video(post, postId, content?.takeIf { it.host == "v.redd.it" }?.pathSegments
                    ?.singleOrNull()?.takeIf(videoId::matches) ?: return RedditPost.Unknown, expectedVideoId)
                type == "link" && content != null && !RedditPostIdentity.isRedditHost(content.host) &&
                    !content.host.endsWith(".redd.it") && !content.host.endsWith(".reddit.com") -> link(post.attr("content-href"))
                else -> RedditPost.Unknown
            }
        } catch (_: Exception) { RedditPost.Unknown }
        catch (_: StackOverflowError) { RedditPost.Unknown }
    }

    /** A linked page passes only as one whole HTTPS link that a pasted link would also pass as. */
    private fun link(raw: String): RedditPost =
        LinkInput.candidates(raw).singleOrNull()?.takeIf { it.rawUrl == raw }?.let { RedditPost.Link(it.rawUrl) } ?: RedditPost.Unknown

    private fun video(post: Element, postId: String, id: String, expectedVideoId: String?): RedditPost {
        if (expectedVideoId != null && id != expectedVideoId) return RedditPost.Unknown
        // Logged-out readers get the player only for posts they may watch; without it, keep the old path.
        val player = post.select("shreddit-player, shreddit-player-2").firstOrNull {
            it.attr("post-id") == "t3_$postId" && MediaHttpPolicy.parse(it.attr("src"))?.let { src ->
                src.host == "v.redd.it" && src.pathSegments.firstOrNull() == id
            } == true
        } ?: return RedditPost.Unknown
        val thumbnail = PreviewMetadata.thumbnail(player.attr("poster"))?.takeIf { raw ->
            MediaHttpPolicy.parse(raw)?.host?.let { it == "preview.redd.it" || it == "external-preview.redd.it" } == true
        }
        val duration = try {
            json.readTree(player.attr("packaged-media-json").takeIf { it.length in 1..64 * 1024 } ?: "{}")
                .path("playbackMp4s").path("duration").takeIf { it.isIntegralNumber }?.asLong()?.takeIf { it > 0 }
        } catch (_: Exception) { null }
        return RedditPost.Video(id, PreviewMetadata.text(post.attr("post-title"), 300),
            PreviewMetadata.text(post.attr("author"), 120), thumbnail, duration)
    }
}
