package dev.ahmed.savlino.engine.pinterest

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.PostText
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64

sealed interface PinterestParseResult {
    /**
     * The pin's own largest image. [candidate] is the uploaded file when the page names one (`/originals/`);
     * otherwise the rendition with the greatest declared area. [preview] is a smaller copy when one is declared.
     * No byte size is invented: dimensions are used only to choose among URLs the page listed.
     */
    class Image(val candidate: String, val preview: String, val discovery: ImageDiscovery,
        val title: String?, val author: String?) : PinterestParseResult {
        override fun toString() = "PinterestParseResult.Image(redacted)"
    }
    /** A video pin, including an idea pin or an embedded player: yt-dlp reads the user's link. */
    data object Video : PinterestParseResult
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : PinterestParseResult
}

/**
 * Bounded read of one logged-out pin page. The pin is taken only from its own relay result, Redux `pins` entry, or
 * `PinResource` — never from a related pin, og:image, or a stylesheet URL. No network.
 */
class PinterestPostParser {
    private class Budget : RuntimeException()
    private class Malformed : RuntimeException()

    fun parse(html: ByteArray, pinId: String, length: Int = html.size): PinterestParseResult = try {
        parseBounded(html, pinId, length)
    } catch (_: Budget) {
        PinterestParseResult.Failure(ImageFailure.BUDGET)
    } catch (_: Refusal) {
        PinterestParseResult.Failure(ImageFailure.SOURCE_REFUSED)
    } catch (_: StackOverflowError) {
        PinterestParseResult.Failure(ImageFailure.BUDGET)
    }

    /** The public widget's first result must name this pin; related pins and outside images are never candidates. */
    fun parsePidgets(bytes: ByteArray, pinId: String, length: Int = bytes.size): PinterestParseResult = try {
        if (length !in 0..bytes.size || length > MediaPageLimits.PINTEREST) throw Budget()
        val root = readJson(String(bytes, 0, length, Charsets.UTF_8), TokenBudget()) as? Map<*, *> ?: throw Refusal()
        if (root["status"] != "success") throw Refusal()
        val pin = (root["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw Refusal()
        if (pin["id"] != pinId) throw Refusal()
        if (pin["is_video"] == true) PinterestParseResult.Video
        else {
            val images = renditions(mapOf("images" to pin["images"]))
                .filter { HttpsUrl.parse(it.url)?.let(MediaHttpPolicy::isPinterestImage) == true }.distinctBy { it.url }
            if (images.isEmpty()) throw Refusal()
            choose(images, text(pin), author(pin))
        }
    } catch (_: Budget) {
        PinterestParseResult.Failure(ImageFailure.BUDGET)
    } catch (_: Refusal) {
        PinterestParseResult.Failure(ImageFailure.SOURCE_REFUSED)
    } catch (_: StackOverflowError) {
        PinterestParseResult.Failure(ImageFailure.BUDGET)
    }

    private fun parseBounded(html: ByteArray, pinId: String, length: Int): PinterestParseResult {
        if (length !in 0..html.size || length > MediaPageLimits.PINTEREST) throw Budget()
        val page = String(html, 0, length, Charsets.UTF_8)
        val tokens = TokenBudget()
        val found = ArrayList<Map<*, *>>()
        script(page, "__PWS_INITIAL_PROPS__")?.let { body ->
            pinMap(readJson(body, tokens), pinId)?.let { found += it }
        }
        script(page, "resource-response-data")?.let { body ->
            resourcePin(readJson(body, tokens), pinId)?.let { found += it }
        }
        found += relayPins(page, pinId, tokens)
        if (found.isEmpty()) return failure(ImageFailure.SOURCE_REFUSED)
        val kinds = found.map { classify(it) }
        if (kinds.any { it is Kind.Video })
            return if (kinds.all { it is Kind.Video }) PinterestParseResult.Video else failure(ImageFailure.SOURCE_REFUSED)
        val images = kinds.filterIsInstance<Kind.Images>()
        if (images.isEmpty()) return PinterestParseResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.IMAGE)
        if (images.size != kinds.size) return failure(ImageFailure.SOURCE_REFUSED)
        // Redux often lists a sized copy while Relay lists /originals/ of that same file. Those are one image.
        val merged = images.flatMap { it.renditions }.distinctBy { it.url }
        if (merged.map { fileSignature(it.url) }.distinct().size != 1) return failure(ImageFailure.SOURCE_REFUSED)
        return choose(merged, images.firstNotNullOfOrNull { it.title }, images.firstNotNullOfOrNull { it.author })
    }

    /** Relay results whose query names [pinId]. A matching query with a different entity is refused. */
    private fun relayPins(page: String, pinId: String, tokens: TokenBudget): List<Map<*, *>> {
        val found = ArrayList<Map<*, *>>()
        var relays = 0
        var from = 0
        while (relays < MAX_RELAYS) {
            val at = page.indexOf(RELAY, from)
            if (at < 0) break
            relays++
            val open = page.indexOf('(', at)
            val quote = if (open >= 0) page.indexOf('"', open) else -1
            val endQuote = if (quote >= 0) page.indexOf('"', quote + 1) else -1
            if (open < 0 || quote < 0 || endQuote < 0 || quote - open > 4 || endQuote - quote > MAX_QUERY) {
                from = at + RELAY.length
                continue
            }
            val decoded = try { URLDecoder.decode(page.substring(quote + 1, endQuote), "UTF-8") }
                catch (_: IllegalArgumentException) {
                    from = endQuote + 1
                    continue
                }
            val named = decoded.contains("\"pinId\":\"$pinId\"")
            val comma = page.indexOf(',', endQuote)
            val brace = if (comma >= 0) page.indexOf('{', comma) else -1
            if (comma < 0 || brace < 0 || brace - comma > 8) {
                if (named) throw Refusal()
                from = endQuote + 1
                continue
            }
            val end = jsonEnd(page, brace)
            if (end == null) {
                if (named) throw Refusal()
                from = brace + 1
                continue
            }
            from = end
            if (!named) continue
            val pin = relayPin(readJson(page.substring(brace, end), tokens) ?: throw Refusal()) ?: throw Refusal()
            if (!namesPin(pin, pinId)) throw Refusal()
            found += pin
        }
        if (page.indexOf(RELAY, from) >= 0) throw Budget()
        return found
    }

    private class Refusal : RuntimeException()

    /** `entityId`, or for a light image-only result with no `entityId`, its Relay node id `base64("Pin:<id>")`. */
    private fun namesPin(pin: Map<*, *>, pinId: String): Boolean {
        pin["entityId"]?.let { return it == pinId }
        val node = pin["id"] as? String ?: return false
        if (node.length > 64) return false
        val decoded = try { String(Base64.getDecoder().decode(node), Charsets.UTF_8) }
            catch (_: IllegalArgumentException) { return false }
        return decoded == "Pin:$pinId"
    }

    private fun jsonEnd(page: String, brace: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = brace
        while (index < page.length && index - brace <= MAX_SCRIPT) {
            val char = page[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        if (index - brace > MAX_SCRIPT) throw Budget()
        return null
    }

    private fun failure(reason: ImageFailure) = PinterestParseResult.Failure(reason)

    private fun script(page: String, id: String): String? {
        val marker = "id=\"$id\""
        val at = page.indexOf(marker)
        if (at < 0) return null
        val tagEnd = page.indexOf('>', at)
        val close = page.indexOf("</script>", at)
        if (tagEnd < 0 || close < 0 || tagEnd > close) return null
        if (close - tagEnd > MAX_SCRIPT) throw Budget()
        return page.substring(tagEnd + 1, close)
    }

    private fun pinMap(root: Any?, pinId: String): Map<*, *>? {
        val pins = ((root as? Map<*, *>)?.get("initialReduxState") as? Map<*, *>)?.get("pins") as? Map<*, *>
        return pins?.get(pinId) as? Map<*, *>
    }

    private fun resourcePin(root: Any?, pinId: String): Map<*, *>? {
        val resource = root as? Map<*, *> ?: return null
        if ((resource["resource"] as? Map<*, *>)?.get("name") != "PinResource") return null
        val data = (resource["resource_response"] as? Map<*, *>)?.get("data") as? Map<*, *> ?: return null
        val id = data["id"]
        return data.takeIf { id == pinId || id?.toString() == pinId }
    }

    private fun relayPin(root: Any?): Map<*, *>? {
        val data = (root as? Map<*, *>)?.get("data") as? Map<*, *> ?: return null
        val query = data["v3GetPinQueryv2"] as? Map<*, *> ?: return null
        return query["data"] as? Map<*, *>
    }

    private sealed interface Kind {
        data object Video : Kind
        data object None : Kind
        class Images(val renditions: List<Rendition>, val title: String?, val author: String?) : Kind
    }

    private fun classify(pin: Map<*, *>): Kind {
        if (isVideo(pin)) return Kind.Video
        val images = renditions(pin).filter { HttpsUrl.parse(it.url)?.let(MediaHttpPolicy::isPinterestImage) == true }
            .distinctBy { it.url }
        return if (images.isEmpty()) Kind.None else Kind.Images(images, text(pin), author(pin))
    }

    /** The uploaded file is kept even when the page omits its pixels. Otherwise the greatest declared area wins. */
    private fun choose(images: List<Rendition>, title: String?, author: String?): PinterestParseResult.Image {
        val originals = images.filter { it.original }
        val save = (if (originals.isNotEmpty()) originals else images)
            .maxWith(compareBy<Rendition> { it.area }.thenBy { bucket(it.url) })
        val preview = images.filter { it.url != save.url && it.area > 0 && maxOf(it.width, it.height) >= 256 }
            .minByOrNull { it.area }?.url ?: save.url
        return PinterestParseResult.Image(save.url, preview,
            ImageDiscovery(1, DiscoveryCompleteness.COMPLETE, previewQuality = !save.original), title, author)
    }

    /** A video list, a story pin, or an embedded player. A linked article is not a video. */
    private fun isVideo(pin: Map<*, *>): Boolean {
        if (pin["is_video"] == true || pin["isVideo"] == true) return true
        if (pin["videos"] is Map<*, *>) return true
        val src = (pin["embed"] as? Map<*, *>)?.get("src")
        if (src is String && src.isNotBlank()) return true
        // Story pages can hide a video past any fixed scan. The whole pin stays on the video path.
        return pin["story_pin_data"] != null || pin["storyPinData"] != null
    }

    /** Filename stem. Sized copies and `/originals/` of one upload share it; a different stem is a different file. */
    private fun fileSignature(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').lowercase()

    private class Rendition(val url: String, val width: Int, val height: Int, val original: Boolean) {
        val area: Int get() = if (width > 0 && height > 0) width * height else 0
    }

    private fun renditions(pin: Map<*, *>): List<Rendition> {
        val out = ArrayList<Rendition>()
        val images = pin["images"]
        if (images is Map<*, *>) {
            for ((key, value) in images) {
                val entry = value as? Map<*, *> ?: continue
                val url = entry["url"] as? String ?: continue
                out += Rendition(url, dimension(entry["width"]), dimension(entry["height"]),
                    key == "orig" || url.contains("/originals/"))
            }
        }
        for ((key, value) in pin) {
            val name = key as? String ?: continue
            if (!name.startsWith("images_") && !name.startsWith("imageSpec_")) continue
            val entry = value as? Map<*, *> ?: continue
            val url = entry["url"] as? String ?: continue
            out += Rendition(url, dimension(entry["width"]), dimension(entry["height"]),
                name == "images_orig" || url.contains("/originals/"))
        }
        val large = pin["imageLargeUrl"] as? String ?: pin["image_large_url"] as? String
        if (large != null) out += Rendition(large, 0, 0, large.contains("/originals/"))
        return out
    }

    /** Path bucket such as `736` in `/736x/`, used only when the page declares no pixel area. */
    private fun bucket(url: String): Int = if (url.contains("/originals/")) 100_000
        else Regex("/([0-9]{2,4})x").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** A declared pixel edge. Anything else stays unknown and is not treated as a size. */
    private fun dimension(value: Any?): Int {
        val text = value as? String ?: return 0
        if (text.length !in 1..5 || text.any { it !in '0'..'9' }) return 0
        val number = text.toInt()
        return if (number in 1..20_000) number else 0
    }

    private fun text(pin: Map<*, *>): String? = listOf("grid_title", "gridTitle", "seo_title", "seoTitle", "title",
        "closeup_unified_description", "description").firstNotNullOfOrNull { PostText.firstLine(pin[it] as? String) }

    private fun author(pin: Map<*, *>): String? {
        val attribution = pin["closeup_attribution"] as? Map<*, *> ?: pin["closeupAttribution"] as? Map<*, *>
        val pinner = pin["pinner"] as? Map<*, *>
        val name = attribution?.get("full_name") ?: attribution?.get("fullName") ?: pinner?.get("full_name")
            ?: pinner?.get("fullName")
        return PostText.firstLine(name as? String)
    }

    private class TokenBudget { var used = 0 }

    private fun readJson(text: String, budget: TokenBudget): Any? {
        if (text.length > MAX_SCRIPT) throw Budget()
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
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> parser.text
                    JsonToken.VALUE_TRUE -> true
                    JsonToken.VALUE_FALSE -> false
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
        const val RELAY = "window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__("
        const val MAX_RELAYS = 8
        const val MAX_SCRIPT = 512 * 1024
        const val MAX_QUERY = 8 * 1024
        const val MAX_JSON_TOKENS = 250_000
        const val MAX_JSON_DEPTH = 48
        const val MAX_JSON_STRING = 64 * 1024
        val json: JsonFactory = JsonFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
    }
}
