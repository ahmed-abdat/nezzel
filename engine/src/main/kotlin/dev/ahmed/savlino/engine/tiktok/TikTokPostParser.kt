package dev.ahmed.savlino.engine.tiktok

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.MediaImageFormat
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Memory-only candidates; never render signed URLs or the post caption in debug output. */
class TikTokPhoto(val position: Int, val urls: List<String>, val format: MediaImageFormat) {
    override fun toString() = "TikTokPhoto(redacted)"
}
class TikTokSound(val title: String?, val author: String?, val durationSeconds: Int?) {
    override fun toString() = "TikTokSound(redacted)"
}
sealed interface TikTokParseResult {
    class Success(val photos: List<TikTokPhoto>, val discovery: ImageDiscovery, val title: String?,
        val sound: TikTokSound?) : TikTokParseResult {
        override fun toString() = "TikTokParseResult.Success(redacted)"
    }
    data object Video : TikTokParseResult
    data class Failure(val reason: ImageFailure) : TikTokParseResult
}

/** Only the requested item's imagePost is read; recommendations, covers and avatars cannot become photos. */
class TikTokPostParser {
    fun parse(html: ByteArray, postId: String, length: Int = html.size): TikTokParseResult {
        if (length !in 0..html.size || length > MediaPageLimits.TIKTOK) return TikTokParseResult.Failure(ImageFailure.BUDGET)
        return try {
            val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(64))
            if (document.getAllElements().size > 30_000) return TikTokParseResult.Failure(ImageFailure.BUDGET)
            val scripts = document.select("script[id=__UNIVERSAL_DATA_FOR_REHYDRATION__]")
            if (scripts.size != 1) return TikTokParseResult.Failure(ImageFailure.SOURCE_REFUSED)
            val text = scripts.single().data()
            // Check nesting and token count before building an untrusted tree.
            json.factory.createParser(text).use { parser ->
                var depth = 0
                var tokens = 0
                var roots = 0
                while (true) {
                    val token = parser.nextToken() ?: break
                    if (++tokens > 100_000) return TikTokParseResult.Failure(ImageFailure.BUDGET)
                    if (depth == 0 && ++roots > 1) return TikTokParseResult.Failure(ImageFailure.SOURCE_REFUSED)
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
                    if (depth > 64) return TikTokParseResult.Failure(ImageFailure.BUDGET)
                    if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
                }
            }
            val root = json.readTree(text)
            val detail = root.path("__DEFAULT_SCOPE__").path("webapp.video-detail")
            val status = detail.path("statusCode").asInt(0)
            if (status != 0) return TikTokParseResult.Failure(
                if (status in setOf(10216, 10222)) ImageFailure.PRIVATE else ImageFailure.NO_MEDIA)
            val item = detail.path("itemInfo").path("itemStruct")
            if (item.path("id").asText() != postId) return TikTokParseResult.Failure(ImageFailure.SOURCE_REFUSED)
            val post = item.path("imagePost")
            if (post.isMissingNode || post.isNull) return if (item.path("video").isObject) TikTokParseResult.Video
                else TikTokParseResult.Failure(ImageFailure.NO_MEDIA)
            val images = post.path("images")
            if (!images.isArray || images.size() == 0) return TikTokParseResult.Failure(ImageFailure.NO_MEDIA)
            val candidates = images.take(MAX_PHOTOS).mapIndexedNotNull { position, image ->
                val urls = image.path("imageURL").path("urlList")
                if (!urls.isArray) return@mapIndexedNotNull null
                // Preserve provider priority; only its first two candidates can authorize Save.
                val accepted = urls.take(2).mapNotNull { node ->
                    node.takeIf { it.isTextual }?.asText()?.let(MediaHttpPolicy::parse)
                        ?.takeIf(MediaHttpPolicy::isTikTokImage)?.toString()?.takeIf { format(it) != null }
                }.distinct()
                if (accepted.isEmpty()) null else TikTokPhoto(position, accepted, checkNotNull(format(accepted.first())))
            }
            val photos = candidates.distinctBy { it.urls.first() }
            if (photos.isEmpty()) return TikTokParseResult.Failure(ImageFailure.SOURCE_REFUSED)
            val unavailable = minOf(images.size(), MAX_PHOTOS) - candidates.size
            val capped = (images.size() - MAX_PHOTOS).coerceAtLeast(0)
            val music = item.path("music")
            val sound = if (music.isObject && music.path("playUrl").asText().isNotBlank()) TikTokSound(
                PostText.firstLine(music.path("title").asText(null)), PostText.firstLine(music.path("authorName").asText(null)),
                music.path("duration").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()?.takeIf { it > 0 }) else null
            TikTokParseResult.Success(photos, ImageDiscovery(photos.size,
                if (unavailable + capped == 0) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
                claimedCount = images.size(), unavailableCount = unavailable, cappedCount = capped),
                PostText.firstLine(item.path("desc").asText(null)), sound)
        } catch (_: Exception) { TikTokParseResult.Failure(ImageFailure.SOURCE_REFUSED) }
        catch (_: StackOverflowError) { TikTokParseResult.Failure(ImageFailure.BUDGET) }
    }

    companion object {
        const val MAX_PHOTOS = 40
        private fun format(url: String): MediaImageFormat? = when (MediaHttpPolicy.parse(url)?.encodedPath?.substringAfterLast('.')?.lowercase(java.util.Locale.ROOT)) {
            "jpeg", "jpg" -> MediaImageFormat.JPEG
            "png" -> MediaImageFormat.PNG
            "webp" -> MediaImageFormat.WEBP
            "gif" -> MediaImageFormat.GIF
            "avif" -> MediaImageFormat.AVIF
            else -> null
        }
        private val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    }
}
