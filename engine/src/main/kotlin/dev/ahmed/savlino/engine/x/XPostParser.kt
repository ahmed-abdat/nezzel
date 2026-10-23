package dev.ahmed.savlino.engine.x

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.PostText

sealed interface XParseResult {
    /**
     * Photo or mixed post: every own photo, in post order, as `pbs.twimg.com/media/<name>?format=<ext>&name=<size>`.
     * [previewQuality] when any photo declares more than 2048 px and only the `large` copy is fetched.
     */
    class Success(val candidates: List<String>, val discovery: ImageDiscovery,
        val title: String? = null, val author: String? = null) : XParseResult {
        override fun toString() = "XParseResult.Success(redacted)"
    }
    /**
     * The post has only usable video/GIF media. [infoJson] is the yt-dlp-shaped info built from this JSON
     * ([XVideoInfo]); null means yt-dlp reads the post as before.
     */
    class Video(val infoJson: String?) : XParseResult {
        override fun toString() = "XParseResult.Video(redacted)"
    }
    class PhotosUnavailableVideo(val infoJson: String?) : XParseResult {
        override fun toString() = "XParseResult.PhotosUnavailableVideo(redacted)"
    }
    /**
     * [phase] PAGE: no media verdict yet (the app may fall back to yt-dlp once). IMAGE: the post is known to be
     * photo-only but its photo cannot be used; yt-dlp has nothing to add, so the error is shown.
     */
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : XParseResult
}

/**
 * Reads the public syndication JSON of one post. Only the post's own `mediaDetails` count: quoted posts,
 * link cards and avatars never become attachments.
 */
class XPostParser {
    fun parse(body: ByteArray, postId: String, length: Int = body.size): XParseResult {
        if (length !in 0..body.size || length > MediaPageLimits.X)
            return XParseResult.Failure(ImageFailure.BUDGET)
        val root = try { json.readTree(body, 0, length) } catch (_: Exception) { null }
        if (root == null || !root.isObject) return XParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        when (root.path("__typename").asText(null)) {
            "Tweet" -> Unit
            // A removed, protected or age-gated post answers with a tombstone for a logged-out reader.
            "TweetTombstone" -> return XParseResult.Failure(ImageFailure.PRIVATE)
            else -> return XParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        }
        if (root.path("id_str").asText(null) != postId) return XParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        val linked = externalLink.containsMatchIn(root.path("text").asText("")) || root.hasNonNull("card") ||
            root.hasNonNull("quoted_tweet") || root.hasNonNull("quoted_status") ||
            root.path("entities").path("urls").any { it.path("expanded_url").asText("").isNotBlank() || it.path("url").asText("").isNotBlank() }
        fun noMedia() = XParseResult.Failure(if (linked) ImageFailure.LINKED_ELSEWHERE else ImageFailure.NO_MEDIA)
        val media = root.get("mediaDetails")
        if (media == null || media.isNull) return noMedia()
        if (!media.isArray || media.size() > MAX_MEDIA) return XParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        if (media.size() == 0) return noMedia()
        val types = media.map { it.path("type").asText("") }
        val hasVideo = types.any { it == "video" || it == "animated_gif" }
        val photos = media.filter { it.path("type").asText("") == "photo" }
        if (photos.isEmpty()) return if (hasVideo) XParseResult.Video(XVideoInfo.build(root, postId)) else noMedia()
        // Keep only evidenced own photos. Malformed siblings make discovery partial; no usable photo is an
        // IMAGE failure, so a known photo post never becomes a yt-dlp fallback.
        val usable = photos.take(20).mapNotNull { photo ->
            mediaUrl.matchEntire(photo.path("media_url_https").asText(""))?.let { photo to it }
        }
        if (usable.isEmpty()) return if (hasVideo) XParseResult.PhotosUnavailableVideo(XVideoInfo.build(root, postId))
            else XParseResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
        var large = false
        val candidates = usable.map { (photo, match) ->
            val (name, ext) = match.destructured
            val format = if (ext == "jpeg") "jpg" else ext
            val info = photo.path("original_info")
            val reduced = info.path("width").asLong(0) > LARGE_EDGE || info.path("height").asLong(0) > LARGE_EDGE
            large = large || reduced
            "https://pbs.twimg.com/media/$name?format=$format&name=${if (reduced) "large" else "orig"}"
        }
        val text = root.path("text").takeIf { it.isTextual }?.asText()
        val author = root.path("user").path("name").takeIf { it.isTextual }?.asText()
        return XParseResult.Success(candidates,
            ImageDiscovery(candidates.size, if (usable.size == media.size()) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL, previewQuality = large,
                unavailableCount = minOf(photos.size, 20) - usable.size, cappedCount = (photos.size - 20).coerceAtLeast(0), hasVideo = hasVideo),
            PostText.firstLine(text), PostText.firstLine(author))
    }

    private companion object {
        val externalLink = Regex("https?://[^\\s]+")
        const val MAX_MEDIA = 16
        const val LARGE_EDGE = 2048L
        val mediaUrl = Regex("https://pbs\\.twimg\\.com/media/([A-Za-z0-9_-]{1,64})\\.(jpg|jpeg|png|webp|gif|avif)")
        val json: ObjectMapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    }
}
