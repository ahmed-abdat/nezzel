package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.PostText
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Declared width/height are the page's claim only (used to prefer a larger copy), never reported as fact. */
class FacebookImageCandidate(val url: String, val photoId: String?, internal val declaredWidth: Long = 0,
    internal val declaredHeight: Long = 0, val previewUrl: String = url) {
    override fun toString() = "FacebookImageCandidate(redacted)"
}
sealed interface FacebookParseResult {
    /** title/author: the bound story's (or matched photo's own story's) message first line and actor name. */
    class Success(val candidates: List<FacebookImageCandidate>, val discovery: ImageDiscovery,
        val title: String? = null, val author: String? = null, val videoId: String? = null) : FacebookParseResult {
        override fun toString() = "FacebookParseResult.Success(redacted)"
    }
    data class Failure(val reason: ImageFailure) : FacebookParseResult
    /** The bound post names one video for this request: yt-dlp gets `watch/?v={videoId}`. */
    class Video(val videoId: String) : FacebookParseResult {
        override fun toString() = "FacebookParseResult.Video(redacted)"
    }
}

/**
 * Untrusted, bounded reading of an already-fetched logged-out Facebook page. Media comes only from
 * `<script data-sjs>` RelayPrefetchedStreamCache blocks, walked along the story's own attachment paths
 * (`node_v2`/`node` Story attachments, album `all_subattachments`, photo-page `currMedia`); comments,
 * save info, feeds and recommendations are never visited. og:image is a labelled preview fallback only
 * when the relay data holds no media nodes at all and the page is the post itself. No network.
 */
class FacebookPageParser {
    private class Budget : RuntimeException()
    private class Malformed : RuntimeException()
    private class Photo(val id: String?, val url: String, val reduced: Boolean, val width: Long, val height: Long,
        val previewUrl: String)

    fun parse(html: ByteArray, page: FacebookPostIdentity, requested: FacebookPostIdentity? = null, length: Int = html.size): FacebookParseResult = try {
        parseBounded(html, page, requested, length)
    } catch (_: Budget) {
        FacebookParseResult.Failure(ImageFailure.BUDGET)
    } catch (_: StackOverflowError) {
        FacebookParseResult.Failure(ImageFailure.BUDGET)
    }

    private fun parseBounded(html: ByteArray, page: FacebookPostIdentity, requested: FacebookPostIdentity?, length: Int): FacebookParseResult {
        if (length !in 0..html.size || length > MediaPageLimits.FACEBOOK) throw Budget()
        val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(PARSER_DEPTH))
        var elements = 0
        NodeTraversor.traverse(NodeVisitor { node: Node, depth: Int ->
            if (node is Element && (++elements > MAX_ELEMENTS || depth > MAX_HTML_DEPTH)) throw Budget()
        }, document)
        val scripts = document.select("script[data-sjs]")
        if (scripts.size > MAX_SCRIPTS) throw Budget()
        val tokens = TokenBudget()

        // The page route decides "content isn't available" before any media reading.
        for (script in scripts) {
            val text = script.data()
            if (!text.contains("\"rootView\"")) continue
            val route = routeName(readJson(text, tokens) ?: continue) ?: continue
            if (route == ERROR_ROUTE || route.startsWith("CometLogin")) return failure(ImageFailure.PRIVATE)
            break
        }

        val photoId = requested?.photoId ?: page.photoId
        val postId = requested?.postId ?: page.postId
        val relay = scripts.map { it.data() }.filter { it.contains("RelayPrefetchedStreamCache") }
        if (relay.size > MAX_RELAY_BLOCKS) throw Budget()
        var mediaNodes = false
        // Root Story nodes keyed by post_id (else id): a copy repeated across blocks collapses only when its
        // media is identical (or one copy carries none); copies that disagree on media bind nothing.
        val stories = LinkedHashMap<String, Root>()
        val conflicted = HashSet<String>()
        val currMedia = ArrayList<Map<*, *>>()
        for (text in relay) {
            val root = readJson(text, tokens) ?: continue
            for (data in results(root, 0)) {
                if (!mediaNodes && hasMediaNode(data, 0)) mediaNodes = true
                val story = (data["node_v2"] as? Map<*, *>) ?: (data["node"] as? Map<*, *>)
                if (story != null && story["__typename"] == "Story") {
                    val key = (story["post_id"] as? String) ?: (story["id"] as? String) ?: continue
                    val root = Root(story["post_id"] as? String, media(story), story)
                    val existing = stories[key]
                    when {
                        existing == null || existing.media.isEmpty -> stories[key] = root
                        root.media.isEmpty || root.media.signature == existing.media.signature -> Unit
                        else -> conflicted += key
                    }
                    if (stories.size > MAX_CANDIDATES) throw Budget()
                }
                (data["currMedia"] as? Map<*, *>)?.let(currMedia::add)
                if (currMedia.size > MAX_CANDIDATES) throw Budget()
            }
        }

        // Identity binding: a known post id binds only the story naming it; an unknown one binds only a sole story.
        val boundKey = if (postId != null) stories.entries.singleOrNull { it.value.postId == postId }?.key
            else stories.keys.singleOrNull()
        val boundRoot = boundKey?.takeUnless { it in conflicted }?.let { stories[it] }
        val bound = boundRoot?.media ?: StoryMedia.NONE
        val photos = ArrayList(bound.photos)
        val claimed = bound.claimed
        val album = bound.album
        val videos = bound.videos.distinct()
        if ((requested ?: page).isVideoShare && photoId == null)
            videos.singleOrNull()?.let { return FacebookParseResult.Video(it) }
        // Photo page: only a currMedia whose id is the requested fbid (never an unbound viewer preload).
        if (photoId != null) currMedia.mapNotNull(::photo).filter { it.id == photoId }.forEach(photos::add)

        val distinct = LinkedHashMap<String, Photo>()
        for (photo in photos) distinct.putIfAbsent(photo.id ?: photo.url, photo)
        var chosen = distinct.values.toList()
        if (photoId != null) chosen = chosen.filter { it.id == photoId }
        if (chosen.size > MAX_CANDIDATES) throw Budget()
        if (chosen.isNotEmpty()) {
            val discovered = chosen.size
            val total = claimed?.takeIf { album && photoId == null && it >= discovered }
            val completeness = when {
                photoId != null || !album -> DiscoveryCompleteness.COMPLETE
                total == null -> DiscoveryCompleteness.UNKNOWN
                total > discovered -> DiscoveryCompleteness.PARTIAL
                else -> DiscoveryCompleteness.COMPLETE
            }
            // Text only from the story that owns the media. A photo link: the one current-media node with that
            // id (its creation, else container, story) and never another story on the page; a post link: the bound story.
            val owner = if (photoId != null) {
                val current = currMedia.filter { (it["id"] as? String) == photoId }.distinct().singleOrNull()
                listOfNotNull((current?.get("creation_story") ?: current?.get("container_story")) as? Map<*, *>)
            } else listOfNotNull(boundRoot?.story)
            return FacebookParseResult.Success(chosen.map { FacebookImageCandidate(it.url, it.id, it.width, it.height, it.previewUrl) },
                ImageDiscovery(discovered, completeness, total, previewQuality = chosen.first().reduced, hasVideo = videos.isNotEmpty()),
                owner.firstNotNullOfOrNull(::storyMessage), owner.firstNotNullOfOrNull(::storyActor), videos.singleOrNull())
        }
        // No photo, and the bound story's only attachment is one valid Video: yt-dlp reads its watch page.
        if (photoId == null && (requested ?: page).isPostOrPhoto && bound.others == 0)
            bound.videos.singleOrNull()?.let { return FacebookParseResult.Video(it) }
        if (mediaNodes) return failure(ImageFailure.NO_MEDIA)
        // No relay media at all plus a login form: the logged-out wall, decided before any og:image.
        if (document.selectFirst("form#login_form") != null) return failure(ImageFailure.PRIVATE)

        // A video post's og:image is its thumbnail, never the media: NO_MEDIA lets the app hand it to yt-dlp.
        // (Relay-verified Photo media above still wins even when og:type says video.)
        val ogType = document.selectFirst("meta[property=og:type]")?.attr("content")?.trim()?.lowercase(java.util.Locale.ROOT)
        if (ogType?.startsWith("video") == true) return failure(ImageFailure.NO_MEDIA)

        // No relay media and no wall: the post's own og:image, labelled as preview quality.
        if (page.isPostOrPhoto && photoId == null) {
            val og = document.select("meta[property=og:image]").map { it.attr("content") }.distinct()
            og.singleOrNull()?.takeIf(::allowedCdn)?.let { url ->
                return FacebookParseResult.Success(listOf(FacebookImageCandidate(url, null)),
                    ImageDiscovery(1, DiscoveryCompleteness.UNKNOWN, null, previewQuality = true))
            }
        }
        return failure(ImageFailure.NO_MEDIA)
    }

    /** One Story root's attachments: photos (album nodes included), one id per video attachment, and the rest. */
    private class StoryMedia(val photos: List<Photo>, val videos: List<String>, val others: Int,
        val album: Boolean, val claimed: Int?) {
        val isEmpty get() = photos.isEmpty() && videos.isEmpty() && others == 0
        val signature get() = Triple(photos.map { it.id ?: it.url }, videos, others)
        companion object { val NONE = StoryMedia(emptyList(), emptyList(), 0, false, null) }
    }
    private class Root(val postId: String?, val media: StoryMedia, val story: Map<*, *>)

    private fun media(story: Map<*, *>): StoryMedia {
        val content = (((story["comet_sections"] as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("story") as? Map<*, *>)
        val attachments = (story["attachments"] as? List<*>)?.takeIf { it.isNotEmpty() }
            ?: (content?.get("attachments") as? List<*>).orEmpty()
        val photos = ArrayList<Photo>()
        val videos = ArrayList<String>()
        var others = if (attachments.size > MAX_CANDIDATES) 1 else 0
        var claimed: Int? = null
        var album = false
        for (attachment in attachments.take(MAX_CANDIDATES)) {
            val entry = attachment as? Map<*, *> ?: continue
            val styled = (entry["styles"] as? Map<*, *>)?.get("attachment") as? Map<*, *>
            val set = (styled?.get("all_subattachments") ?: entry["all_subattachments"]) as? Map<*, *>
            if (set != null) {
                album = true
                others++ // an album is never "one video"
                claimed = (set["count"] as? String)?.takeIf { it.length <= 6 && it.all(Char::isDigit) }?.toInt()
                    ?.takeIf { it > 0 } ?: claimed
                for (node in (set["nodes"] as? List<*>).orEmpty().take(MAX_CANDIDATES)) {
                    val media = (node as? Map<*, *>)?.get("media") as? Map<*, *> ?: continue
                    photo(media)?.let(photos::add)
                    if (media["__typename"] == "Video") videoId(media)?.let(videos::add)
                }
                continue
            }
            val photo = photo(styled?.get("media"))
            if (photo != null) { photos += photo; others++; continue }
            // Every Video node of this attachment must carry the same valid numeric id; anything else is "other".
            val nodes = listOf(styled?.get("media"), entry["media"]).filter { (it as? Map<*, *>)?.get("__typename") == "Video" }
            val ids = nodes.map(::videoId)
            if (ids.isNotEmpty() && ids.all { it != null } && ids.distinct().size == 1) videos += ids.first()!! else others++
        }
        return StoryMedia(photos, videos, others, album, claimed)
    }

    /** story.message.text, else comet_sections.content.story.message.text (first line, bounded). */
    private fun storyMessage(story: Map<*, *>): String? = listOf(story, contentStory(story)).firstNotNullOfOrNull {
        PostText.firstLine(((it?.get("message") as? Map<*, *>)?.get("text")) as? String)
    }

    /** First actor's name of the story itself (never a commenter or feedback profile). */
    private fun storyActor(story: Map<*, *>): String? = listOf(story, contentStory(story)).firstNotNullOfOrNull {
        PostText.firstLine(((it?.get("actors") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("name") as? String)
    }

    private fun contentStory(story: Map<*, *>): Map<*, *>? =
        ((story["comet_sections"] as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("story") as? Map<*, *>

    /** Prefer viewer_image for Save; a smaller rendition of the same photo may supply its CDN preview. */
    private fun photo(value: Any?): Photo? {
        val media = value as? Map<*, *> ?: return null
        if (media["__typename"] != "Photo") return null
        val id = (media["id"] as? String)?.takeIf { it.length <= 25 && it.all(Char::isDigit) }
        val viewer = media["viewer_image"] as? Map<*, *>
        for (key in listOf("viewer_image", "photo_image", "image")) {
            val image = media[key] as? Map<*, *> ?: continue
            val url = image["uri"] as? String ?: continue
            if (!allowedCdn(url)) continue
            val reduced = key != "viewer_image" && viewer != null &&
                (dimension(viewer["width"]) > dimension(image["width"]) || dimension(viewer["height"]) > dimension(image["height"]))
            val width = dimension(image["width"])
            val height = dimension(image["height"])
            val preview = listOf("photo_image", "image").mapNotNull { media[it] as? Map<*, *> }
                .firstOrNull {
                    val w = dimension(it["width"])
                    val h = dimension(it["height"])
                    val uri = it["uri"] as? String
                    uri != null && allowedCdn(uri) && w in 1..width && h in 1..height && (w < width || h < height)
                }?.get("uri") as? String
            return Photo(id, url, reduced, width, height, preview ?: url)
        }
        return null
    }

    /** A Video media node's numeric id (never a URL: the media itself stays with yt-dlp). */
    private fun videoId(value: Any?): String? {
        val media = value as? Map<*, *> ?: return null
        return (media["id"] as? String)?.takeIf { it.length <= 25 && it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    }

    private fun dimension(value: Any?): Long = (value as? String)?.takeIf { it.length <= 6 && it.all(Char::isDigit) }?.toLong() ?: 0

    private fun allowedCdn(url: String): Boolean = HttpsUrl.parse(url)?.let(MediaHttpPolicy::isFacebookImageCdn) == true

    private fun failure(reason: ImageFailure) = FacebookParseResult.Failure(reason)

    /** Every `__bbox.result.data` object in one relay block. */
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

    private fun hasMediaNode(value: Any?, depth: Int): Boolean {
        if (depth > MAX_JSON_DEPTH) throw Budget()
        return when (value) {
            is Map<*, *> -> value["__typename"] in MEDIA_TYPES || value.values.any { hasMediaNode(it, depth + 1) }
            is List<*> -> value.any { hasMediaNode(it, depth + 1) }
            else -> false
        }
    }

    /** initialRouteInfo.route.rootView.resource.__dr of the page route. */
    private fun routeName(value: Any?, depth: Int = 0): String? {
        if (depth > MAX_JSON_DEPTH) throw Budget()
        return when (value) {
            is Map<*, *> -> {
                val route = (value["initialRouteInfo"] as? Map<*, *>)?.get("route") as? Map<*, *>
                val resource = ((route?.get("rootView") as? Map<*, *>)?.get("resource") as? Map<*, *>)?.get("__dr") as? String
                resource ?: value.values.firstNotNullOfOrNull { routeName(it, depth + 1) }
            }
            is List<*> -> value.firstNotNullOfOrNull { routeName(it, depth + 1) }
            else -> null
        }
    }

    private class TokenBudget { var used = 0 }

    /** Bounded tree; numbers are kept as their text. A malformed block is ignored, never evidence. */
    private fun readJson(text: String, budget: TokenBudget): Any? {
        if (text.length > MediaPageLimits.FACEBOOK) throw Budget()
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
        const val PARSER_DEPTH = 512
        const val MAX_HTML_DEPTH = 256
        const val MAX_ELEMENTS = 65_536
        const val MAX_SCRIPTS = 1024
        const val MAX_RELAY_BLOCKS = 128
        const val MAX_CANDIDATES = 64
        const val MAX_JSON_TOKENS = 1_000_000
        const val MAX_JSON_DEPTH = 192
        const val MAX_JSON_STRING = 512 * 1024
        const val ERROR_ROUTE = "CometErrorRoot.react"
        val MEDIA_TYPES = setOf("Photo", "Video", "Story")
        val json: JsonFactory = JsonFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
    }
}
