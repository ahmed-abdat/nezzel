package dev.ahmed.savlino.engine.x

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.engine.YtdlpCommand
import java.net.URI

/**
 * yt-dlp-shaped info JSON from the syndication JSON the reader already fetched, field names and format ids as
 * yt-dlp's twitter extractor writes them, so `--load-info-json` downloads the same `http-*` row. Only
 * progressive mp4 variants on video.twimg.com become rows; hls rows stay yt-dlp-only. Codec and size are not in
 * the syndication JSON and stay absent. No `webpage_url`: a failed download never re-extracts silently.
 *
 * Null means "let yt-dlp read the post": no own video, a card, quoted or reposted post (yt-dlp would add their
 * media), a possibly sensitive (age-gated) post, an unknown media or variant shape, ids that are not exact and
 * unique, or a link whose `/photo|video/<n>` picks anything but the first video. Several own videos keep yt-dlp's
 * inspect semantics (`--playlist-items 1`): the first video, titled ` #1`.
 */
internal object XVideoInfo {
    fun build(root: JsonNode, postId: String, mediaIndex: Int? = null): String? {
        if (!root.isObject || root.path("__typename").asText(null) != "Tweet" || root.path("id_str").asText(null) != postId)
            return null
        if (DECLINED.any(root::hasNonNull)) return null
        // yt-dlp marks these 18+ (`age_limit`); anything but an explicit false stays its call.
        if (root.get("possibly_sensitive")?.let { !it.isNull && !(it.isBoolean && !it.booleanValue()) } == true) return null
        val media = root.get("mediaDetails")?.takeIf { it.isArray } ?: return null
        val videos = media.filter { it.path("type").asText("") != "photo" }
        if (videos.any { !it.isObject || it.path("type").asText("") !in VIDEO_TYPES }) return null
        val video = videos.firstOrNull() ?: return null
        // twitter.py with an index: `mediaDetails[n-1]`, photos counted, must be a `video` (a GIF is refused).
        if (mediaIndex != null && (media.get(mediaIndex - 1) !== video || video.path("type").asText() != "video")) return null
        val formats = formats(video) ?: return null
        // A GIF is one progressive file without bitrate (yt-dlp: one `http` row); any other GIF shape → yt-dlp.
        if (video.path("type").asText() == "animated_gif" && video.path("video_info").path("variants")
                .filter { ".m3u8" !in it.path("url").asText("") }.let { it.size != 1 || bitrate(it.single()) != 0L }) return null
        return json.writeValueAsString(json.createObjectNode().apply {
            put("id", video.path("id_str").asText("").takeIf(DIGITS::matches) ?: postId)
            put("display_id", postId)
            put("title", title(root) + if (videos.size > 1) " #1" else "")
            root.path("text").takeIf { it.isTextual }?.let { put("description", it.asText().replace('\n', ' ')) }
            root.path("user").path("name").takeIf { it.isTextual }?.let { put("uploader", it.asText()) }
            root.path("user").path("screen_name").takeIf { it.isTextual }?.let { put("uploader_id", it.asText()) }
            video.path("video_info").path("duration_millis").takeIf { it.isNumber && it.asDouble() >= 0 }
                ?.let { put("duration", it.asDouble() / 1000) }
            thumbnails(video, this)
            put("extractor", "twitter")
            put("extractor_key", "Twitter")
            set<JsonNode>("formats", formats)
        })
    }

    /** twitter.py `_extract_variant_formats`: `http-<bitrate // 1000>`, or `http` when that is 0 or missing. */
    private fun formats(video: JsonNode): JsonNode? {
        val variants = video.path("video_info").path("variants").takeIf { it.isArray } ?: return null
        val rows = json.createArrayNode()
        for (variant in variants) {
            val url = variant.path("url").takeIf { it.isTextual }?.asText() ?: return null
            if (".m3u8" in url) continue
            if (variant.path("content_type").asText(null) != "video/mp4" || !isXVideo(url)) return null
            val rate = bitrate(variant) ?: return null
            val tbr = rate / 1000
            rows.addObject().apply {
                put("format_id", if (tbr > 0) "http-$tbr" else "http")
                put("url", url)
                put("ext", "mp4")
                put("protocol", "https")
                if (tbr > 0) put("tbr", tbr)
                DIMENSIONS.find(url)?.let { match ->
                    val width = match.groupValues[1].toIntOrNull() ?: return null
                    val height = match.groupValues[2].toIntOrNull() ?: return null
                    put("width", width)
                    put("height", height)
                }
            }
        }
        val ids = rows.map { it.path("format_id").asText() }
        if (ids.isEmpty() || ids.toSet().size != ids.size || !ids.all(YtdlpCommand::isExactId)) return null
        return rows
    }

    /** yt-dlp `dict_get(variant, ('bitrate', 'bit_rate'))` skips missing and zero values; null = unusable value. */
    private fun bitrate(variant: JsonNode): Long? {
        for (key in listOf("bitrate", "bit_rate")) {
            val node = variant.get(key) ?: continue
            if (node.isNull) continue
            if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 0) return null
            if (node.asLong() > 0) return node.asLong()
        }
        return 0
    }

    private fun isXVideo(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.host == "video.twimg.com" &&
            uri.rawPath?.endsWith(".mp4") == true
    }

    /** twitter.py: text with newlines as spaces, links stripped, cut at 72 code points, prefixed by the author. */
    private fun title(root: JsonNode): String {
        val text = LINK.replace(root.path("text").takeIf { it.isTextual }?.asText().orEmpty().replace('\n', ' '), "")
        val cut = if (text.codePointCount(0, text.length) <= 72) text
            else text.substring(0, text.offsetByCodePoints(0, 69)) + "..."
        val author = root.path("user").path("name").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
        return if (author != null) "$author - $cut" else cut
    }

    /** twitter.py: one `?name=<size>` per declared size, then `orig`; yt-dlp's `thumbnail` is the largest. */
    private fun thumbnails(video: JsonNode, info: ObjectNode) {
        val base = video.path("media_url_https").takeIf { it.isTextual }?.asText()
            ?.takeIf { url -> runCatching { URI(url) }.getOrNull()?.let {
                it.scheme == "https" && it.host == "pbs.twimg.com" && it.rawQuery == null && it.rawFragment == null } == true }
            ?: return
        val sizes = video.path("sizes").takeIf { it.isObject }?.fields()?.asSequence()?.map { it.key to it.value }?.toList().orEmpty() +
            ("orig" to video.path("original_info"))
        val list = info.putArray("thumbnails")
        var best: Triple<Int, Int, String>? = null
        for ((name, size) in sizes) {
            if (!NAME.matches(name)) continue
            val width = (size.get("w") ?: size.get("width"))?.takeIf { it.isIntegralNumber }?.asInt()
            val height = (size.get("h") ?: size.get("height"))?.takeIf { it.isIntegralNumber }?.asInt()
            list.addObject().apply {
                put("id", name)
                put("url", "$base?name=$name")
                width?.let { put("width", it) }
                height?.let { put("height", it) }
            }
            val key = Triple(width ?: 0, height ?: 0, name)
            if (best == null || compareValuesBy(key, best, { it.first }, { it.second }, { it.third }) >= 0) best = key
        }
        best?.let { info.put("thumbnail", "$base?name=${it.third}") }
    }

    private val json = ObjectMapper()
    private val DECLINED = listOf("card", "quoted_tweet", "quoted_status", "retweeted_status", "retweeted_tweet")
    private val VIDEO_TYPES = setOf("video", "animated_gif")
    private val DIGITS = Regex("[0-9]{1,25}")
    private val NAME = Regex("[a-z0-9_]{1,32}")
    private val DIMENSIONS = Regex("/(\\d+)x(\\d+)/")
    // Android's ICU regex rejects the Unicode-classes flag; \p{Z} and NEL add the Unicode spaces it covered on the JVM.
    private val LINK = Regex("[\\s\\p{Z}\\u0085]+(https?://[^ ]+)")
}
