package dev.ahmed.savlino.engine.threads

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediahttp.VideoCdnPolicy
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.PostText

sealed interface ThreadsParseResult {
    class Photos(val candidates: List<String>, val previewCandidates: List<String>, val discovery: ImageDiscovery,
        val title: String?, val author: String?, val videoInfoJson: String?) : ThreadsParseResult {
        override fun toString() = "ThreadsParseResult.Photos(redacted)"
    }
    class Video(val infoJson: String) : ThreadsParseResult {
        override fun toString() = "ThreadsParseResult.Video(redacted)"
    }
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : ThreadsParseResult
}

/**
 * The logged-out post page's TargetQuery preloader. `video_dash_manifest` is ignored: one progressive
 * MP4 from `video_versions` is the only video. Malformed input fails; it does not throw.
 */
class ThreadsPostParser {
    fun parse(body: ByteArray, code: String, length: Int = body.size): ThreadsParseResult {
        if (length !in 0..body.size || length > MediaPageLimits.THREADS) return ThreadsParseResult.Failure(ImageFailure.BUDGET)
        val root = read(body, length) ?: return ThreadsParseResult.Failure(ImageFailure.UNAVAILABLE)
        val data = root.path("__bbox").path("result").path("data")
        if (!data.isObject || !data.has("media")) return ThreadsParseResult.Failure(ImageFailure.UNAVAILABLE)
        val media = data.get("media") ?: return ThreadsParseResult.Failure(ImageFailure.PRIVATE)
        if (media.isNull) return ThreadsParseResult.Failure(ImageFailure.PRIVATE)
        if (!media.isObject) return ThreadsParseResult.Failure(ImageFailure.UNAVAILABLE)
        if (!samePost(media, code)) return ThreadsParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        val title = PostText.firstLine(media.path("caption").path("text").takeIf { it.isTextual }?.asText())
        val author = PostText.firstLine(media.path("user").path("username").takeIf { it.isTextual }?.asText())
        return when (media.path("media_type").asInt(-1)) {
            1 -> photos(listOf(media), title, author, video = null)
            2 -> videoOf(media, code, title, author)
            8 -> carousel(media, code, title, author)
            else -> ThreadsParseResult.Failure(ImageFailure.NO_MEDIA)
        }
    }

    private fun carousel(media: JsonNode, code: String, title: String?, author: String?): ThreadsParseResult {
        val items = media.path("carousel_media")
        if (!items.isArray || items.size() == 0) return ThreadsParseResult.Failure(ImageFailure.NO_MEDIA)
        var video: VideoPick? = null
        val images = ArrayList<JsonNode>()
        var unavailable = 0
        for (item in items.take(40)) {
            when (item.path("media_type").asInt(-1)) {
                1 -> if (imageNode(item) == null) unavailable++ else images.add(item)
                2 -> if (video == null) video = videoPick(item)
                else -> unavailable++
            }
        }
        if (images.isEmpty()) return video?.let { ThreadsParseResult.Video(info(code, it, title, author)) }
            ?: ThreadsParseResult.Failure(if (unavailable > 0) ImageFailure.SOURCE_REFUSED else ImageFailure.NO_MEDIA,
                if (unavailable > 0) ImagePhase.IMAGE else ImagePhase.PAGE)
        return photos(images, title, author, video?.let { info(code, it, title, author) }, unavailable,
            (items.size() - 40).coerceAtLeast(0))
    }

    private fun videoOf(media: JsonNode, code: String, title: String?, author: String?): ThreadsParseResult {
        val pick = videoPick(media)
        if (pick != null) return ThreadsParseResult.Video(info(code, pick, title, author))
        val versions = media.path("video_versions")
        return ThreadsParseResult.Failure(
            if (versions.isArray && versions.size() > 0) ImageFailure.SOURCE_REFUSED else ImageFailure.NO_MEDIA,
            if (versions.isArray && versions.size() > 0) ImagePhase.IMAGE else ImagePhase.PAGE)
    }

    private fun photos(nodes: List<JsonNode>, title: String?, author: String?, video: String?,
        unavailable: Int = 0, extraCapped: Int = 0): ThreadsParseResult {
        val parsed = nodes.mapNotNull(::imageNode)
        val missed = nodes.size - parsed.size
        if (parsed.isEmpty()) return ThreadsParseResult.Failure(
            if (nodes.any { it.path("image_versions2").path("candidates").let { c -> c.isArray && c.size() > 0 } })
                ImageFailure.SOURCE_REFUSED else ImageFailure.NO_MEDIA,
            if (nodes.any { it.path("image_versions2").path("candidates").let { c -> c.isArray && c.size() > 0 } })
                ImagePhase.IMAGE else ImagePhase.PAGE)
        val shown = parsed.take(20)
        val capped = (parsed.size - 20).coerceAtLeast(0) + extraCapped
        val dropped = unavailable + missed
        return ThreadsParseResult.Photos(shown.map { it.first }, shown.map { it.second }, ImageDiscovery(shown.size,
            if (dropped + capped == 0) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
            unavailableCount = dropped, cappedCount = capped, hasVideo = video != null), title, author, video)
    }

    /** Largest candidate by width (the first of that width). Preview is a smaller candidate when one exists. */
    private fun imageNode(node: JsonNode): Pair<String, String>? {
        val candidates = node.path("image_versions2").path("candidates")
        if (!candidates.isArray || candidates.size() == 0) return null
        val urls = candidates.mapNotNull { candidate ->
            val raw = candidate.path("url").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
            val url = imageUrl(raw) ?: return@mapNotNull null
            val width = candidate.path("width").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 } ?: 0
            width to url
        }
        if (urls.isEmpty()) return null
        val widest = urls.maxOf { it.first }
        val full = urls.first { it.first == widest }.second
        val preview = urls.filter { it.second != full && it.first in 1 until widest }.minByOrNull { it.first }?.second ?: full
        return full to preview
    }

    /** Largest `video_versions` entry by width×height. Missing dimensions tie, and the first wins. */
    private fun videoPick(node: JsonNode): VideoPick? {
        val versions = node.path("video_versions")
        if (!versions.isArray || versions.size() == 0) return null
        val rows = versions.mapNotNull { version ->
            val raw = version.path("url").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
            val url = videoUrl(raw) ?: return@mapNotNull null
            val width = version.path("width").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
            val height = version.path("height").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
            val area = if (width != null && height != null) width.toLong() * height else -1L
            val bytes = version.path("filesize").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }
            Row(area, url, width, height, bytes)
        }
        if (rows.isEmpty()) return null
        val best = rows.maxOf { it.area }
        val chosen = rows.first { it.area == best }
        val originalWidth = node.path("original_width").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
        val originalHeight = node.path("original_height").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 }
        // A version's own dimensions win. original_* fills in only when the version omitted both.
        val width = if (chosen.width != null && chosen.height != null) chosen.width
            else if (chosen.width == null && chosen.height == null) originalWidth else null
        val height = if (chosen.width != null && chosen.height != null) chosen.height
            else if (chosen.width == null && chosen.height == null) originalHeight else null
        return VideoPick(chosen.url, width, height, chosen.bytes,
            node.path("has_audio").takeIf { it.isBoolean }?.booleanValue())
    }

    private fun info(code: String, pick: VideoPick, title: String?, author: String?): String {
        val root = json.createObjectNode()
        root.put("id", code)
        title?.let { root.put("title", it) }
        author?.let { root.put("uploader", it) }
        root.put("extractor_key", "Threads")
        val format = root.putArray("formats").addObject()
        format.put("format_id", "progressive")
        format.put("url", pick.url)
        format.put("ext", "mp4")
        format.put("protocol", "https")
        pick.width?.let { format.put("width", it) }
        pick.height?.let { format.put("height", it) }
        pick.bytes?.let { format.put("filesize", it) }
        // A silent progressive file is h264 with no audio track. Omitting that would present a soundtrack.
        if (pick.hasAudio == false) {
            format.put("vcodec", "h264")
            format.put("acodec", "none")
        }
        return json.writeValueAsString(root)
    }

    private fun samePost(media: JsonNode, code: String): Boolean {
        val posted = media.path("code").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
        val pk = media.path("pk").takeIf { !it.isNull && !it.isMissingNode }?.asText()?.takeIf { it.isNotEmpty() }
        if (posted == null && pk == null) return false
        val decoded = ThreadsPostIdentity.mediaId(code)
        if (posted != null && posted != code) return false
        if (pk != null && pk != decoded) return false
        return posted == code || pk == decoded
    }

    private fun imageUrl(raw: String): String? {
        val url = HttpsUrl.parse(raw) ?: return null
        return url.takeIf(MediaHttpPolicy::isThreadsImage)?.toString()
    }

    private fun videoUrl(raw: String): String? {
        val url = HttpsUrl.parse(raw) ?: return null
        if (!VideoCdnPolicy.hostPath(Host.THREADS, url.host, url.encodedPath)) return null
        return url.toString()
    }

    private fun read(body: ByteArray, length: Int): JsonNode? {
        if (length !in 0..body.size || length > MediaPageLimits.THREADS) return null
        val start = preloaderAt(body, length)
        if (start < 0) return null
        return try {
            json.factory.createParser(body, start, length - start).use { parser ->
                val tree = json.readTree<JsonNode>(parser)
                if (tree != null && tree.isObject) tree else null
            }
        } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
    }

    /** Byte index of the `{` that opens the TargetQuery `__bbox` object, or -1. */
    private fun preloaderAt(body: ByteArray, length: Int): Int {
        val key = PRELOADER
        var from = 0
        while (from < length) {
            val at = indexOf(body, length, key, from)
            if (at < 0) return -1
            var hex = at + key.size
            val hexStart = hex
            while (hex < length && body[hex].toInt().and(0xff).toChar().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) hex++
            if (hex > hexStart && hex - hexStart <= 64 && indexOf(body, length, BBOX, hex) == hex) return hex + 2
            from = at + 1
        }
        return -1
    }

    private fun indexOf(body: ByteArray, length: Int, needle: ByteArray, from: Int): Int {
        val last = length - needle.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < needle.size && body[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private class Row(val area: Long, val url: String, val width: Int?, val height: Int?, val bytes: Long?)
    private class VideoPick(val url: String, val width: Int?, val height: Int?, val bytes: Long?, val hasAudio: Boolean?)

    private companion object {
        val json: ObjectMapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val PRELOADER = "adp_BarcelonaPostPageTargetQueryRelayPreloader_".toByteArray(Charsets.US_ASCII)
        /** `",{"__bbox":` — the `{` is two bytes in. */
        val BBOX = "\",{\"__bbox\":".toByteArray(Charsets.US_ASCII)
    }
}
