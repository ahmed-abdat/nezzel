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
    /**
     * An image or gallery post's own photos, in post order. [originals]: what Save fetches, the `i.redd.it` file when
     * the page names it, else the largest signed preview it names ([previewQuality]). [previews]: a small copy the
     * page names for each, else the original itself. [skipped]: gallery items that are no photo (an animated clip).
     */
    class Photos(val originals: List<String>, val previews: List<String>, val title: String?, val author: String?,
        val previewQuality: Boolean, val skipped: Int) : RedditPost {
        override fun toString() = "RedditPost.Photos(redacted)"
    }
    /** Text or poll, or a photo post whose page names no photo: the post exists but holds nothing to save. */
    data object NoVideo : RedditPost
    /** Reddit asks logged-out readers to sign in (18+ post). */
    data object LoginRequired : RedditPost
    /** Not a shape this parser knows (crosspost, a changed page): the caller keeps the old path. */
    data object Unknown : RedditPost
}

/** Reads only the requested post's own `<shreddit-post>`; feed items, ads and comments never qualify. */
internal object RedditPostParser {
    private val videoId = Regex("[a-z0-9]{8,24}")
    private val noVideoTypes = setOf("text", "self", "poll")
    private val photoTypes = setOf("image", "gallery")
    /**
     * Reddit galleries hold at most 20 items; a longer list is not a page this parser knows. Up to this many pass on,
     * and the carousel keeps its own image cap and counts the rest as capped.
     */
    private const val MAX_ITEMS = 40
    private const val PREVIEW_WIDTH = 320
    private val json = ObjectMapper()

    fun parse(html: ByteArray, length: Int, postId: String, expectedVideoId: String? = null): RedditPost {
        if (length !in 0..minOf(html.size, MediaPageLimits.REDDIT)) return RedditPost.Unknown
        return try {
            val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(128))
            if (document.getAllElements().size > 60_000) return RedditPost.Unknown
            val post = document.select("shreddit-post").filter { it.attr("id") == "t3_$postId" }.singleOrNull()
                ?: return RedditPost.Unknown
            val type = post.attr("post-type")
            // A post with no media says so, 18+ or not; an 18+ photo or video asks for sign-in (logged out, it may
            // name only blurred copies).
            if (type in noVideoTypes) return RedditPost.NoVideo
            if (post.attributes().any { "nsfw" in it.key.lowercase() }) return RedditPost.LoginRequired
            if (type in photoTypes) return photos(post, type == "gallery")
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

    /** One rendition a page names: a Reddit image URL and the width it is declared at (0 when undeclared). */
    private class Named(val url: String, val width: Int) {
        val original: Boolean get() = url.startsWith("https://i.redd.it/")
    }

    /**
     * Only URLs this post's own markup names, on Reddit's image hosts: an image post's `content-href` file, a gallery's
     * `<li slot="page-N">` items in page order. Nothing is built from an id, so a size or file is never guessed.
     */
    private fun photos(post: Element, gallery: Boolean): RedditPost {
        val items: List<List<Named>?> = if (gallery) {
            val pages = post.select("gallery-carousel li").filter { it.attr("slot").startsWith("page-") }
                .ifEmpty { post.select("gallery-carousel ul > li") }
            if (pages.size > MAX_ITEMS) return RedditPost.Unknown
            pages.map { page ->
                // A clip item (Reddit plays animated items as video) has no photo to save.
                if (page.select("shreddit-player, shreddit-player-2, video").isNotEmpty()) null
                else named(page.select("figure img").ifEmpty { page.select("img") }, page.select("a[href]"))
                    .takeIf { it.isNotEmpty() }
            }
        } else {
            val content = rendition(post.attr("content-href"), 0)?.takeIf { it.original }
            val shown = named(post.select("img"), emptyList())
            listOf((listOfNotNull(content) + shown).takeIf { it.isNotEmpty() })
        }
        val found = items.filterNotNull()
        if (found.isEmpty()) return RedditPost.NoVideo
        val originals = found.map { names -> names.firstOrNull { it.original } ?: names.filterNot { it.original }.maxBy { it.width } }
        val previews = found.mapIndexed { index, names ->
            val sized = names.filter { !it.original && it.width > 0 }
            (sized.filter { it.width >= PREVIEW_WIDTH }.minByOrNull { it.width } ?: sized.maxByOrNull { it.width }
                ?: originals[index]).url
        }
        return RedditPost.Photos(originals.map { it.url }, previews, PreviewMetadata.text(post.attr("post-title"), 300),
            PreviewMetadata.text(post.attr("author"), 120), originals.any { !it.original }, items.size - found.size)
    }

    private fun named(images: List<Element>, links: List<Element>): List<Named> {
        val names = ArrayList<Named>()
        for (image in images) {
            for (key in listOf("src", "data-lazy-src")) rendition(image.attr(key), 0)?.let(names::add)
            for (key in listOf("srcset", "data-lazy-srcset")) image.attr(key).split(Regex(",\\s*(?=https://)")).forEach { entry ->
                val parts = entry.trim().split(Regex("\\s+"))
                rendition(parts[0], parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0)?.let(names::add)
            }
        }
        links.forEach { link -> rendition(link.attr("href"), 0)?.let(names::add) }
        return names.distinctBy { it.url }
    }

    /** [raw] as the page names it, when it is a Reddit image URL; a preview's `width=` query counts as its width. */
    private fun rendition(raw: String, declared: Int): Named? {
        val url = MediaHttpPolicy.parse(raw)?.takeIf(MediaHttpPolicy::isRedditImage) ?: return null
        val width = declared.takeIf { it > 0 } ?: url.queryParameter("width")?.toIntOrNull()?.takeIf { it in 1..20_000 } ?: 0
        return Named(url.toString(), width)
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
