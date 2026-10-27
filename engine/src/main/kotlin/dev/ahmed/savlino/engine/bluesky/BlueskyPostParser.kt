package dev.ahmed.savlino.engine.bluesky

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.*

sealed interface BlueskyParseResult {
    class Success(val candidates: List<String>, val previewCandidates: List<String>, val discovery: ImageDiscovery,
        val title: String?, val author: String?) : BlueskyParseResult {
        override fun toString() = "BlueskyParseResult.Success(redacted)"
    }
    data object Video : BlueskyParseResult
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : BlueskyParseResult
}

/** View embeds only: quoted-record images, external cards and author avatars are never attachments. */
class BlueskyPostParser {
    fun resolveDid(body: ByteArray, length: Int = body.size): String? = read(body, length)?.path("did")
        ?.takeIf { it.isTextual }?.asText()?.takeIf(BlueskyPostIdentity::validDid)

    fun parse(body: ByteArray, atUri: String, length: Int = body.size): BlueskyParseResult {
        if (length !in 0..body.size || length > MediaPageLimits.BLUESKY) return BlueskyParseResult.Failure(ImageFailure.BUDGET)
        val root = read(body, length) ?: return BlueskyParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        val thread = root.path("thread")
        when (thread.path("\$type").asText()) {
            "app.bsky.feed.defs#notFoundPost", "app.bsky.feed.defs#blockedPost" -> return BlueskyParseResult.Failure(ImageFailure.PRIVATE)
            "app.bsky.feed.defs#threadViewPost" -> Unit
            else -> return BlueskyParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        }
        val post = thread.path("post")
        val did = atUri.removePrefix("at://").substringBefore('/')
        if (!BlueskyPostIdentity.validDid(did) || post.path("uri").asText() != atUri || post.path("author").path("did").asText() != did)
            return BlueskyParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        var embed = post.path("embed")
        if (embed.path("\$type").asText() == "app.bsky.embed.recordWithMedia#view") embed = embed.path("media")
        return when (embed.path("\$type").asText()) {
            "app.bsky.embed.video#view" -> BlueskyParseResult.Video
            "app.bsky.embed.images#view" -> {
                val images = embed.path("images")
                if (!images.isArray || images.size() == 0) return BlueskyParseResult.Failure(ImageFailure.NO_MEDIA)
                val photos = images.take(20).mapNotNull { image ->
                    val full = jpegUrl(image.path("fullsize").asText(), did, thumbnail = false) ?: return@mapNotNull null
                    val thumb = jpegUrl(image.path("thumb").asText(), did, thumbnail = true) ?: full
                    full to thumb
                }.distinctBy { it.first }
                if (photos.isEmpty()) return BlueskyParseResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
                val unavailable = minOf(images.size(), 20) - photos.size
                val capped = (images.size() - 20).coerceAtLeast(0)
                BlueskyParseResult.Success(photos.map { it.first }, photos.map { it.second }, ImageDiscovery(photos.size,
                    if (unavailable + capped == 0) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
                    unavailableCount = unavailable, cappedCount = capped), PostText.firstLine(post.path("record").path("text").asText(null)),
                    PostText.firstLine(post.path("author").path("displayName").asText(null)))
            }
            else -> BlueskyParseResult.Failure(ImageFailure.NO_MEDIA)
        }
    }
    private fun jpegUrl(raw: String, did: String, thumbnail: Boolean): String? {
        val url = HttpsUrl.parse(raw) ?: return null
        if (!MediaHttpPolicy.isBlueskyImage(url)) return null
        val kind = if (thumbnail) "feed_thumbnail" else "feed_fullsize"
        if (!url.encodedPath.startsWith("/img/$kind/plain/$did/")) return null
        val path = url.encodedPath.replace(Regex("@(?:jpeg|png|webp|avif)$"), "") + "@jpeg"
        return url.newBuilder().encodedPath(path).build().toString()
    }
    private fun read(body: ByteArray, length: Int): JsonNode? {
        if (length !in 0..body.size || length > MediaPageLimits.BLUESKY) return null
        return try { json.readTree(body, 0, length)?.takeIf { it.isObject } } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
    }
    private companion object {
        val json: ObjectMapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    }
}
