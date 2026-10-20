package dev.ahmed.savlino.engine.instagram

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.PostText
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

sealed interface InstagramParseResult {
    /**
     * Photo post or mixed carousel: up to the first 20 photos of the post in order (its largest
     * candidate on the Instagram image CDN), with a separately selected small preview. Videos further in a carousel are not counted.
     */
    class Success(val candidates: List<String>, val discovery: ImageDiscovery,
        val title: String? = null, val author: String? = null,
        val previewCandidates: List<String> = candidates) : InstagramParseResult {
        override fun toString() = "InstagramParseResult.Success(redacted)"
    }
    /** A video-only post/carousel: yt-dlp reads the user's link exactly as before. */
    data object Video : InstagramParseResult
    data object PhotosUnavailableVideo : InstagramParseResult
    /**
     * [phase] PAGE: no media verdict yet (the app may fall back to yt-dlp once). IMAGE: the post is known to be a
     * photo (or photo-first carousel) but its photo cannot be used; the error is shown, yt-dlp is never asked.
     */
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : InstagramParseResult
}

/**
 * Untrusted, bounded reading of an already-fetched logged-out `www.instagram.com/p/<code>/` page. Media comes only
 * from `<script type="application/json" data-sjs>` RelayPrefetchedStreamCache results whose `xig_polaris_media.code`
 * is the requested shortcode, read through `if_not_gated_logged_out`: `media_type` 1 photo, 2 video, 8 carousel
 * (`carousel_media`). Comments, related posts and profile data are never visited. No network.
 */
class InstagramPostParser {
    private class Budget : RuntimeException()
    private class Malformed : RuntimeException()

    fun parse(html: ByteArray, code: String, length: Int = html.size): InstagramParseResult = try {
        parseBounded(html, code, length)
    } catch (_: Budget) {
        InstagramParseResult.Failure(ImageFailure.BUDGET)
    } catch (_: StackOverflowError) {
        InstagramParseResult.Failure(ImageFailure.BUDGET)
    }

    private fun parseBounded(html: ByteArray, code: String, length: Int): InstagramParseResult {
        if (length !in 0..html.size || length > MediaPageLimits.INSTAGRAM) throw Budget()
        val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(PARSER_DEPTH))
        var elements = 0
        NodeTraversor.traverse(NodeVisitor { node: Node, depth: Int ->
            if (node is Element && (++elements > MAX_ELEMENTS || depth > MAX_HTML_DEPTH)) throw Budget()
        }, document)
        val scripts = document.select("script[type=application/json][data-sjs]")
        if (scripts.size > MAX_SCRIPTS) throw Budget()
        val relay = scripts.map { it.data() }.filter { it.contains("RelayPrefetchedStreamCache") && it.contains(POLARIS) }
        if (relay.size > MAX_RELAY_BLOCKS) throw Budget()
        val tokens = TokenBudget()
        // Every polaris media result naming the requested code; repeated copies must agree exactly.
        val found = ArrayList<Map<*, *>>()
        for (text in relay) {
            val root = readJson(text, tokens) ?: continue
            for (data in results(root, 0)) {
                val polaris = data[POLARIS] as? Map<*, *> ?: continue
                if (polaris["code"] == code) found += polaris
                if (found.size > MAX_RESULTS) throw Budget()
            }
        }
        // No post data for this code: a changed page, a missing post or a wall served as 200 (caller decides).
        val polaris = found.firstOrNull() ?: return failure(ImageFailure.SOURCE_REFUSED)
        if (found.any { it != polaris }) return failure(ImageFailure.SOURCE_REFUSED)
        // Gated for logged-out readers (age/region/sensitive) or a private account: not public.
        val media = polaris["if_not_gated_logged_out"] as? Map<*, *>
        if (media == null || polaris["gating_ruling"] != null) return failure(ImageFailure.PRIVATE)
        if (media["code"] != code) return failure(ImageFailure.SOURCE_REFUSED)
        var partial = false
        val (photos, videos) = when (media["media_type"]) {
            PHOTO -> listOf(media) to 0
            VIDEO -> return InstagramParseResult.Video
            CAROUSEL -> {
                val rawItems = media["carousel_media"] as? List<*>
                val items = rawItems?.map { it as? Map<*, *> ?: emptyMap<Any, Any>() }
                if (items.isNullOrEmpty() || items.size > MAX_ITEMS) return failure(ImageFailure.SOURCE_REFUSED)
                val types = items.map { it["media_type"] }
                if (types.first() != PHOTO && types.first() != VIDEO) return failure(ImageFailure.SOURCE_REFUSED)
                partial = types.any { it != PHOTO && it != VIDEO }
                // Preserve video-only handoff; mixed carousels expose photos and an explicit video switch.
                if (types.all { it == VIDEO }) return InstagramParseResult.Video
                items.filter { it["media_type"] == PHOTO } to types.count { it == VIDEO }
            }
            else -> return failure(ImageFailure.SOURCE_REFUSED)
        }
        // Classified as photos from here on: an unusable candidate is an image failure, never a yt-dlp fallback.
        val considered = photos.take(20)
        val renditions = considered.mapNotNull { renditions(it) }
        val candidates = renditions.map { it.first }
        partial = partial || candidates.size != considered.size || photos.size > 20
        if (candidates.isEmpty()) return if (videos > 0) InstagramParseResult.PhotosUnavailableVideo
            else InstagramParseResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
        val text = (media["caption"] as? Map<*, *>)?.get("text") as? String
        val user = media["user"] as? Map<*, *>
        val author = PostText.firstLine(user?.get("full_name") as? String) ?: PostText.firstLine(user?.get("username") as? String)
        return InstagramParseResult.Success(candidates,
            ImageDiscovery(candidates.size, if (videos == 0 && !partial) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
                unavailableCount = considered.size - candidates.size, cappedCount = (photos.size - 20).coerceAtLeast(0), hasVideo = videos > 0),
            PostText.firstLine(text), author, renditions.map { it.second })
    }

    /**
     * The photo's largest rendition: by declared area when candidates declare it, else the first one (Instagram lists
     * the original first). The preview is the smallest evidenced rendition at least 256px on its long axis.
     * Every candidate must be on the Instagram image CDN, or the item is not trusted.
     */
    private fun renditions(item: Map<*, *>): Pair<String, String>? {
        val list = ((item["image_versions2"] as? Map<*, *>)?.get("candidates") as? List<*>) ?: return null
        if (list.isEmpty() || list.size > MAX_CANDIDATES) return null
        val candidates = list.map { candidate ->
            val entry = candidate as? Map<*, *> ?: return null
            val url = entry["url"] as? String ?: return null
            if (MediaHttpPolicy.parse(url)?.let(MediaHttpPolicy::isInstagramImage) != true) return null
            Triple(url, dimension(entry["width"]), dimension(entry["height"]))
        }
        val full = candidates.maxByOrNull { it.second * it.third }?.takeIf { it.second * it.third > 0 } ?: candidates.first()
        // Some logged-out payloads omit width/height; their CDN stp still declares the rendition size.
        val preview = candidates.map { candidate ->
            if (candidate.second > 0 && candidate.third > 0) candidate else {
                val size = MediaHttpPolicy.parse(candidate.first)?.queryParameter("stp")?.let { renditionSize.find(it) }
                if (size == null) candidate else Triple(candidate.first, size.groupValues[1].toLong(), size.groupValues[2].toLong())
            }
        }.filter { it.second > 0 && it.third > 0 && maxOf(it.second, it.third) >= 256 }
            .minByOrNull { it.second * it.third } ?: full
        return full.first to preview.first
    }

    private fun dimension(value: Any?): Long = (value as? String)?.takeIf { it.length in 1..5 && it.all(Char::isDigit) }?.toLong() ?: 0

    private fun failure(reason: ImageFailure) = InstagramParseResult.Failure(reason)

    /** Every `__bbox.result.data` map (Relay results), nested anywhere in a block. */
    private fun results(value: Any?, depth: Int): List<Map<*, *>> {
        if (depth > MAX_JSON_DEPTH) throw Budget()
        return when (value) {
            is Map<*, *> -> {
                val data = (((value["__bbox"] as? Map<*, *>)?.get("result")) as? Map<*, *>)?.get("data") as? Map<*, *>
                listOfNotNull(data) + value.values.flatMap { results(it, depth + 1) }
            }
            is List<*> -> value.flatMap { results(it, depth + 1) }
            else -> emptyList()
        }
    }

    private class TokenBudget { var used = 0 }

    /** Strict, bounded JSON: duplicate keys are malformed; numbers stay their source text. */
    private fun readJson(text: String, budget: TokenBudget): Any? {
        if (text.length > MediaPageLimits.INSTAGRAM) throw Budget()
        return try {
            json.createParser(text).use { parser ->
                fun next(): JsonToken? {
                    if (++budget.used > MAX_JSON_TOKENS) throw Budget()
                    return parser.nextToken()
                }
                fun value(token: JsonToken, depth: Int): Any? = when (token) {
                    JsonToken.START_OBJECT -> {
                        if (depth >= MAX_JSON_DEPTH) throw Budget()
                        val map = LinkedHashMap<String, Any?>()
                        while (true) {
                            val field = next() ?: throw Malformed()
                            if (field == JsonToken.END_OBJECT) break
                            if (field != JsonToken.FIELD_NAME) throw Malformed()
                            val name = parser.currentName() ?: throw Malformed()
                            val child = value(next() ?: throw Malformed(), depth + 1)
                            if (map.containsKey(name)) throw Malformed()
                            map[name] = child
                        }
                        map
                    }
                    JsonToken.START_ARRAY -> {
                        if (depth >= MAX_JSON_DEPTH) throw Budget()
                        val list = ArrayList<Any?>()
                        while (true) {
                            val item = next() ?: throw Malformed()
                            if (item == JsonToken.END_ARRAY) break
                            list += value(item, depth + 1)
                        }
                        list
                    }
                    JsonToken.VALUE_STRING -> parser.text.also { if (it.length > MAX_JSON_STRING) throw Budget() }
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_TRUE,
                    JsonToken.VALUE_FALSE -> parser.text
                    JsonToken.VALUE_NULL -> null
                    else -> throw Malformed()
                }
                val root = value(next() ?: return null, 0)
                if (next() != null) null else root
            }
        } catch (_: Malformed) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        val renditionSize = Regex("(?:^|_)[sp]([0-9]{2,5})x([0-9]{2,5})(?:_|$)")
        const val POLARIS = "xig_polaris_media"
        const val PHOTO = "1"
        const val VIDEO = "2"
        const val CAROUSEL = "8"
        const val PARSER_DEPTH = 512
        const val MAX_HTML_DEPTH = 256
        const val MAX_ELEMENTS = 65_536
        const val MAX_SCRIPTS = 1024
        const val MAX_RELAY_BLOCKS = 16
        const val MAX_RESULTS = 16
        const val MAX_ITEMS = 100
        const val MAX_CANDIDATES = 32
        const val MAX_JSON_TOKENS = 1_000_000
        const val MAX_JSON_DEPTH = 192
        const val MAX_JSON_STRING = 512 * 1024
        val json: JsonFactory = JsonFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
    }
}
