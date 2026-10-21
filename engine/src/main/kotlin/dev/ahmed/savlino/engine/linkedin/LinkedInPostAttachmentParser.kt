package dev.ahmed.savlino.engine.linkedin

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

class LinkedInImageCandidate(val url: String) {
    override fun toString() = "LinkedInImageCandidate(redacted)"
}
sealed interface LinkedInParseResult {
    /** title/author: the matched post's own JSON-LD headline (else articleBody) first line and author.name. */
    class Success(val candidates: List<LinkedInImageCandidate>, val discovery: ImageDiscovery,
        val title: String? = null, val author: String? = null) : LinkedInParseResult {
        override fun toString() = "LinkedInParseResult.Success(redacted)"
    }
    /** A video post: [postUrl] is the clean public post URL (no query or fragment) yt-dlp inspects; never a media URL. */
    class Video(val postUrl: String) : LinkedInParseResult {
        override fun toString() = "LinkedInParseResult.Video(redacted)"
    }
    data class Failure(val reason: ImageFailure) : LinkedInParseResult
}

/**
 * Untrusted association of already-fetched, bounded post HTML. An attachment needs the matching post's
 * JSON-LD image AND the post-owned DOM image list (plus og:image when exactly one image is evidenced).
 * Parses only bytes it is given: no network, no canonical fetch, no URL rewriting.
 */
class LinkedInPostAttachmentParser {
    private class Budget : RuntimeException()
    private class Conflict : RuntimeException()
    private class Malformed : RuntimeException()

    fun parse(html: ByteArray, resolvedPost: String, length: Int = html.size): LinkedInParseResult = try {
        parseBounded(html, resolvedPost, length)
    } catch (_: Budget) {
        LinkedInParseResult.Failure(ImageFailure.BUDGET)
    } catch (_: Conflict) {
        LinkedInParseResult.Failure(ImageFailure.SOURCE_REFUSED)
    } catch (_: StackOverflowError) {
        LinkedInParseResult.Failure(ImageFailure.BUDGET)
    }

    private fun parseBounded(html: ByteArray, resolvedPost: String, length: Int): LinkedInParseResult {
        if (length !in 0..html.size || length > MediaPageLimits.LINKEDIN) throw Budget()
        val resolved = LinkedInPostIdentity.parse(resolvedPost)?.takeUnless { it.shortlink }
            ?: return LinkedInParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(PARSER_DEPTH))
        var elements = 0
        NodeTraversor.traverse(NodeVisitor { node: Node, depth: Int ->
            if (node is Element && (++elements > MAX_ELEMENTS || depth > MAX_HTML_DEPTH)) throw Budget()
        }, document)
        videoPost(document, resolved)?.let { return it }
        playerVideoPost(document, resolved)?.let { return it }
        val bridge = bridgeContainer(document, resolved)
        val identity = bridge?.second ?: resolved

        // Canonical is association evidence only; any present canonical must name this same post.
        val canonicals = document.select("link[rel=canonical]").map { it.attr("href") }.distinct()
        if (canonicals.size > 1) throw Conflict()
        canonicals.singleOrNull()?.let { href ->
            val canonical = LinkedInPostIdentity.parse(href) ?: throw Conflict()
            if (!canonical.sameAs(identity)) throw Conflict()
        }

        val post = matchingPost(document.select("script"), identity) ?: return noMedia()
        val authorImages = post["author"].asList().flatMap { author -> (author as? Map<*, *>)?.get("image").imageUrls() }.toSet()
        val jsonImages = post["image"].imageUrls()
        if (jsonImages.size > MAX_CANDIDATES) throw Budget()
        val evidenced = jsonImages.filter { it !in authorImages && allowedAsset(it) }.toSet()
        if (evidenced.isEmpty()) return noMedia()

        // Exactly one post-owned attachment list; several lists under the post are ambiguous.
        val list = ownedImageLists(document, identity).singleOrNull() ?: return noMedia()
        // A bridged share must read its attachment from the bridge container itself, never a neighbour.
        if (bridge != null && postOwner(list) !== bridge.first) throw Conflict()
        val items = list.children()
        if (items.size > MAX_CANDIDATES) throw Budget()
        val ordered = LinkedHashSet<String>()
        var more: Int? = null
        for ((index, item) in items.withIndex()) {
            // Every item must be an explicit attachment evidenced by the post's JSON-LD; one
            // unmarked, unevidenced or differently shaped neighbour makes the whole list ambiguous.
            val url = attachmentUrl(item, index) ?: return noMedia()
            if (url !in evidenced) return noMedia()
            ordered += url
            // Only the last tile may carry the "+N" overlay for photos the grid doesn't show.
            overlayCount(item)?.let { if (index != items.lastIndex) return noMedia() else more = it }
        }
        if (ordered.isEmpty()) return noMedia()
        // The grid shows 5 tiles and "+N" (owner's 6-photo post, 2026-10-21): the hidden photos come from the post's own
        // JSON-LD, in its order, only when exactly N evidenced images are not on the grid.
        val hidden = more?.let { n -> evidenced.filter { it !in ordered }.takeIf { it.size == n } }
        hidden?.let(ordered::addAll)
        if (ordered.size == 1) {
            val og = document.select("meta[property=og:image]").map { it.attr("content") }.distinct()
            if (og != ordered.toList()) return noMedia()
        }

        val claimed = list.attr("data-num-images").takeIf { it.length in 1..4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it > 0 }
        val discovered = ordered.size
        val completeness = when {
            claimed != null && claimed > discovered -> DiscoveryCompleteness.PARTIAL
            evidenced.size > discovered -> DiscoveryCompleteness.PARTIAL
            claimed != null && claimed == discovered && evidenced.size == discovered -> DiscoveryCompleteness.COMPLETE
            hidden != null && evidenced.size == discovered -> DiscoveryCompleteness.COMPLETE
            else -> DiscoveryCompleteness.UNKNOWN
        }
        val title = PostText.firstLine(post["headline"] as? String) ?: PostText.firstLine(post["articleBody"] as? String)
        val author = post["author"].asList().firstNotNullOfOrNull { PostText.firstLine((it as? Map<*, *>)?.get("name") as? String) }
        return LinkedInParseResult.Success(ordered.map(::LinkedInImageCandidate),
            ImageDiscovery(discovered, completeness, claimed), title, author)
    }

    private fun noMedia() = LinkedInParseResult.Failure(ImageFailure.NO_MEDIA)

    /**
     * A video post, stricter than the image path because a reshare or comment video must never stand in:
     * - the page holds exactly one post-marked element (data-activity-urn / data-attributed-urn / data-post-url),
     *   so no nested reshare, decoy or second post can be the source; it is shown (no hidden ancestor);
     * - it names this post by its own activity, or by its share/ugcPost as data-attributed-urn, in which case
     *   data-featured-activity-urn must repeat the same activity (the post page's own featured card);
     * - it holds no image list, and exactly one `video` whose parent is its direct
     *   `[data-test-id=feed-native-video-content]` child, all shown and carrying data-sources;
     * - any canonical names the same post, and a JSON-LD VideoObject names it too.
     * Null whenever any of that is missing: the image path then decides exactly as before. The result is a
     * rebuilt clean post URL (slug kept as served), never a media URL.
     */
    private fun videoPost(document: Element, resolved: LinkedInPostIdentity): LinkedInParseResult.Video? {
        val container = document.select("[data-activity-urn], [data-attributed-urn], [data-post-url]")
            .singleOrNull()?.takeIf(::shown) ?: return null
        val activity = LinkedInPostIdentity.activityUrn(container.attr("data-activity-urn")) ?: return null
        val featured = container.attr("data-featured-activity-urn").takeIf { container.hasAttr("data-featured-activity-urn") }
        if (featured != null && LinkedInPostIdentity.activityUrn(featured) != activity) return null
        val identity = when {
            resolved.matchesActivity(activity) -> resolved
            resolved.activityId == null && featured != null && resolved.matchesAttributed(container.attr("data-attributed-urn")) ->
                resolved.withBridgedActivity(activity)
            else -> return null
        }
        if (container.hasAttr("data-post-url") &&
            LinkedInPostIdentity.parse(container.attr("data-post-url"))?.sameAs(identity) != true) return null
        if (container.select("ul.feed-images-content").isNotEmpty()) return null
        val video = container.select("video").singleOrNull() ?: return null
        val media = video.parent() ?: return null
        if (!video.hasAttr("data-sources") || media.parent() !== container ||
            media.attr("data-test-id") != "feed-native-video-content" || !shown(video)) return null
        val canonicals = document.select("link[rel=canonical]").map { it.attr("href") }.distinct()
        if (canonicals.size > 1) return null
        val canonical = canonicals.singleOrNull()?.let { href ->
            LinkedInPostIdentity.parse(href)?.takeIf { !it.shortlink && it.sameAs(identity) } ?: return null
        }
        if (!namesVideoObject(document.select("script"), identity)) return null
        // The page the user's link landed on, else the page's own canonical post slug, else the activity.
        val slug = resolved.slug ?: canonical?.slug
        return LinkedInParseResult.Video(slug?.let { "https://www.linkedin.com/posts/$it/" }
            ?: "https://www.linkedin.com/feed/update/urn:li:activity:$activity/")
    }

    /**
     * LinkedIn's newer public post layout (a video-js player, no post-marked containers, no `video[data-sources]`):
     * the page itself, not an embedded card, says it is this video post:
     * - exactly one og:type, `video.*`; exactly one canonical naming a public post, any og:url naming the same post;
     * - its JSON-LD holds exactly one VideoObject, naming that post by "@id"/"url", and no SocialMediaPosting;
     * - no attachment image list anywhere on the page;
     * - the canonical is the landing post itself, or (a ugcPost/share landing whose page canonicalises to the
     *   activity) shares no ID kind with it and carries the same author/title slug text.
     * Null whenever any of that is missing. The result is the clean canonical post URL, never a media URL.
     */
    private fun playerVideoPost(document: Element, resolved: LinkedInPostIdentity): LinkedInParseResult.Video? {
        val types = document.select("meta[property=og:type]").map { it.attr("content").trim() }.distinct()
        if (types.singleOrNull()?.startsWith("video.", ignoreCase = true) != true) return null
        val canonical = document.select("link[rel=canonical]").map { it.attr("href") }.distinct().singleOrNull()
            ?.let(LinkedInPostIdentity::parse)?.takeUnless { it.shortlink } ?: return null
        val ogUrls = document.select("meta[property=og:url]").map { it.attr("content") }.distinct()
        if (ogUrls.size > 1 || ogUrls.any { LinkedInPostIdentity.parse(it)?.sameAs(canonical) != true }) return null
        val bound = canonical.sameAs(resolved) || (resolved.sharesNoIdKind(canonical) && resolved.activityId == null &&
            canonical.activityId != null && resolved.vanity != null && resolved.vanity == canonical.vanity)
        if (!bound) return null
        if (document.select("ul.feed-images-content").isNotEmpty()) return null
        val nodes = jsonLdNodes(document.select("script"))
        if (nodes.any { node -> (node as? Map<*, *>)?.get("@type").asList().any { it == "SocialMediaPosting" || it == "DiscussionForumPosting" } }) return null
        val video = nodes.filter { "VideoObject" in (it as? Map<*, *>)?.get("@type").asList() }.singleOrNull() as? Map<*, *>
            ?: return null
        val named = listOfNotNull(video["url"] as? String, video["@id"] as? String)
        if (named.isEmpty() || named.any { LinkedInPostIdentity.parse(it)?.sameAs(canonical) != true }) return null
        return canonical.cleanUrl?.let(LinkedInParseResult::Video)
    }

    /** Every top-level and @graph node of every JSON-LD script (broken JSON-LD contributes nothing). */
    private fun jsonLdNodes(scripts: List<Element>): List<Any?> {
        val jsonLd = scripts.filter { it.attr("type").trim().equals("application/ld+json", ignoreCase = true) }
        if (jsonLd.size > MAX_JSON_SCRIPTS) throw Budget()
        return jsonLd.flatMap { script ->
            when (val root = readJson(script.data())) {
                is List<*> -> root
                is Map<*, *> -> listOf(root) + root["@graph"].asList()
                else -> emptyList()
            }
        }
    }

    /** A JSON-LD VideoObject whose "@id"/"url" all name this post (unrelated graphs are ignored). */
    private fun namesVideoObject(scripts: List<Element>, identity: LinkedInPostIdentity): Boolean {
        val jsonLd = scripts.filter { it.attr("type").trim().equals("application/ld+json", ignoreCase = true) }
        if (jsonLd.size > MAX_JSON_SCRIPTS) throw Budget()
        return jsonLd.any { script ->
            val nodes = when (val root = readJson(script.data())) {
                is List<*> -> root
                is Map<*, *> -> listOf(root) + root["@graph"].asList()
                else -> emptyList()
            }
            nodes.any { node ->
                val video = node as? Map<*, *>
                val named = listOfNotNull(video?.get("url") as? String, video?.get("@id") as? String)
                video != null && "VideoObject" in video["@type"].asList() && named.isNotEmpty() &&
                    named.all { LinkedInPostIdentity.parse(it)?.sameAs(identity) == true }
            }
        }
    }

    /**
     * A share/ugcPost URL (e.g. `posts/<slug>-share-N`) names the post by a different ID kind than its
     * activity. Only the fetched page's own post container may bridge them: exactly one visible element
     * whose data-attributed-urn is this post's typed ID, whose data-activity-urn (plus any
     * data-featured-activity-urn / data-post-url) names one activity, and which is the nearest post
     * container of exactly one visible attachment list. A duplicate or detached claim is a conflict,
     * even when it names the same activity. Null when the URL already carries an activity or nothing claims it.
     */
    private fun bridgeContainer(document: Element, resolved: LinkedInPostIdentity): Pair<Element, LinkedInPostIdentity>? {
        if (resolved.activityId != null) return null
        val claims = document.select("[data-attributed-urn]")
            .filter { visible(it) && resolved.matchesAttributed(it.attr("data-attributed-urn")) }
        if (claims.isEmpty()) return null
        val container = claims.singleOrNull() ?: throw Conflict()
        val activity = LinkedInPostIdentity.activityUrn(container.attr("data-activity-urn")) ?: throw Conflict()
        if (container.hasAttr("data-featured-activity-urn") &&
            LinkedInPostIdentity.activityUrn(container.attr("data-featured-activity-urn")) != activity) throw Conflict()
        val bridged = resolved.withBridgedActivity(activity)
        if (container.hasAttr("data-post-url") &&
            LinkedInPostIdentity.parse(container.attr("data-post-url"))?.sameAs(bridged) != true) throw Conflict()
        val owned = document.select("ul.feed-images-content").filter { visible(it) && postOwner(it) === container }
        if (owned.size != 1) throw Conflict()
        return container to bridged
    }

    private fun visible(element: Element) = element.parents().none { it.tagName() in HIDDEN_CONTAINERS }

    /** [visible], and neither it nor an ancestor is `hidden`, aria-hidden or inline display:none/visibility:hidden. */
    private fun shown(element: Element): Boolean = visible(element) && (listOf(element) + element.parents()).none {
        it.tagName() in HIDDEN_CONTAINERS || it.hasAttr("hidden") || it.attr("aria-hidden").trim().equals("true", true) ||
            HIDDEN_STYLE.containsMatchIn(it.attr("style"))
    }

    /** Nearest ancestor marked as a post container (data-activity-urn or data-post-url). */
    private fun postOwner(element: Element): Element? =
        element.parents().firstOrNull { it.hasAttr("data-activity-urn") || it.hasAttr("data-post-url") }

    /** Exactly one SocialMediaPosting whose url names this post; unrelated graphs are ignored. */
    private fun matchingPost(scripts: List<Element>, identity: LinkedInPostIdentity): Map<*, *>? {
        val jsonLd = scripts.filter { it.attr("type").trim().equals("application/ld+json", ignoreCase = true) }
        if (jsonLd.size > MAX_JSON_SCRIPTS) throw Budget()
        val matches = mutableListOf<Map<*, *>>()
        for (script in jsonLd) {
            val root = readJson(script.data()) ?: continue
            val nodes = when (root) {
                is List<*> -> root
                is Map<*, *> -> listOf(root) + root["@graph"].asList()
                else -> emptyList()
            }
            for (node in nodes) {
                val posting = node as? Map<*, *> ?: continue
                if (posting["@type"].asList().none { it == "SocialMediaPosting" || it == "DiscussionForumPosting" }) continue
                // LinkedIn's public post graph names the post by "@id" (no "url"). When both are present they
                // are compared as post identities (activity slug and feed/update URN of one activity agree).
                val named = listOfNotNull(posting["url"] as? String, posting["@id"] as? String)
                if (named.isEmpty()) continue
                val identities = named.map { LinkedInPostIdentity.parse(it) }
                if (identities.any { it == null || it.shortlink }) continue
                val postIdentity = identities.first()!!
                if (identities.any { !it!!.sameAs(postIdentity) }) throw Conflict()
                if (!postIdentity.sameAs(identity)) continue
                val identifier = posting["identifier"] as? String
                if (identifier != null) {
                    val activity = LinkedInPostIdentity.activityUrn(identifier)
                    val known = identity.activityId ?: postIdentity.activityId
                    if (activity != null && known != null && activity != known) throw Conflict()
                }
                matches += posting
            }
        }
        if (matches.size > 1) throw Conflict()
        return matches.singleOrNull()
    }

    /** ul.feed-images-content whose nearest post-marked ancestor is this post and nothing else. */
    private fun ownedImageLists(document: Element, identity: LinkedInPostIdentity): List<Element> =
        document.select("ul.feed-images-content").filter { list ->
            if (!visible(list)) return@filter false
            val owner = postOwner(list) ?: return@filter false
            val urnOwned = if (owner.hasAttr("data-activity-urn")) {
                LinkedInPostIdentity.activityUrn(owner.attr("data-activity-urn"))?.let(identity::matchesActivity) == true
            } else null
            val urlOwned = if (owner.hasAttr("data-post-url")) {
                LinkedInPostIdentity.parse(owner.attr("data-post-url"))?.sameAs(identity) == true
            } else null
            urnOwned != false && urlOwned != false
        }

    /**
     * Explicit attachment shape: `li` whose only element child is an `img` carrying the
     * data-delayed-url marker, with no attribute outside a small presentational allowlist.
     * Links, wrappers, semantic/tracking attributes or a conflicting src are not attachments.
     */
    private fun attachmentUrl(item: Element, index: Int): String? {
        if (item.tagName() != "li" || item.ownText().isNotBlank()) return null
        if (item.attributes().any { !itemAttribute(it.key, it.value, index) }) return null
        val children = item.children()
        // An image, plus at most LinkedIn's exact "+N" overlay after it.
        if (children.size == 2 && overlayCount(item) == null || children.size !in 1..2) return null
        val image: Element = children[0].takeIf { it.tagName() == "img" } ?: return null
        if (image.attributes().any { !imageAttribute(it.key, it.value) }) return null
        val url = image.attr("data-delayed-url").takeIf(String::isNotBlank) ?: return null
        val src = image.attr("src")
        if (src.isNotBlank() && src != url && !src.startsWith("data:")) return null
        return url
    }

    /** N from a tile's `<div data-test-id="feed-images-content__overlay"> +N </div>` second child, else null. */
    private fun overlayCount(item: Element): Int? {
        val children = item.children()
        if (children.size != 2) return null
        val overlay: Element = children[1]
        if (overlay.tagName() != "div" || overlay.children().isNotEmpty()) return null
        if (overlay.attributes().any { it.key != "class" && !(it.key == "data-test-id" && it.value == OVERLAY_TEST_ID) }) return null
        if (overlay.attr("data-test-id") != OVERLAY_TEST_ID) return null
        return Regex("\\+([1-9][0-9]{0,2})").matchEntire(overlay.ownText().trim())?.groupValues?.get(1)?.toInt()
    }

    /**
     * Presentational allowlist plus the public post markup's exact attachment marker: data-test-id only
     * with the item value, data-position only equal to the item's index, style only when empty.
     */
    private fun itemAttribute(key: String, value: String, index: Int): Boolean = when (key) {
        "class" -> true
        "data-test-id" -> value == ATTACHMENT_ITEM_TEST_ID
        "data-position" -> value == index.toString()
        "style" -> value.isBlank()
        else -> false
    }

    /** data-ghost-url is LinkedIn's static placeholder; allowed only on static.licdn.com, never a candidate. */
    private fun imageAttribute(key: String, value: String): Boolean = when (key) {
        in IMAGE_ATTRIBUTES -> true
        "data-ghost-url" -> value.startsWith("https://static.licdn.com/") &&
            MediaHttpPolicy.parse(value)?.host == "static.licdn.com"
        else -> false
    }

    private fun allowedAsset(url: String): Boolean {
        val parsed = MediaHttpPolicy.parse(url) ?: return false
        return parsed.host == "media.licdn.com" && url.startsWith("https://media.licdn.com/")
    }

    private fun Any?.asList(): List<Any?> = when (this) {
        null -> emptyList()
        is List<*> -> this
        else -> listOf(this)
    }

    /** image: URL string, ImageObject (contentUrl, else url) or a list of those. */
    private fun Any?.imageUrls(): List<String> = asList().mapNotNull { value ->
        when (value) {
            is String -> value
            is Map<*, *> -> (value["contentUrl"] ?: value["url"]) as? String
            else -> null
        }
    }

    private fun readJson(text: String): Any? {
        if (text.length > MAX_JSON_CHARS) throw Budget()
        return try {
            json.createParser(text).use { parser ->
                var tokens = 0
                fun next(): JsonToken? {
                    if (++tokens > MAX_JSON_TOKENS) throw Budget()
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
                            if (map.containsKey(name)) throw Conflict()
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
            null // Broken JSON-LD never becomes evidence.
        }
    }

    private companion object {
        /** Same as the transport's LinkedIn page cap: public video post pages run ~3.8 MB. */
        const val PARSER_DEPTH = 512
        const val MAX_HTML_DEPTH = 256
        const val MAX_ELEMENTS = 65_536
        const val MAX_CANDIDATES = 64
        const val MAX_JSON_SCRIPTS = 16
        const val MAX_JSON_CHARS = 256 * 1024
        const val MAX_JSON_TOKENS = 20_000
        const val MAX_JSON_DEPTH = 32
        const val MAX_JSON_STRING = 64 * 1024
        val HIDDEN_STYLE = Regex("(?i)(?:display\\s*:\\s*none|visibility\\s*:\\s*(?:hidden|collapse))")
        val HIDDEN_CONTAINERS = setOf("template", "noscript", "script", "style", "textarea")
        const val ATTACHMENT_ITEM_TEST_ID = "feed-images-content__list-item"
        const val OVERLAY_TEST_ID = "feed-images-content__overlay"
        val IMAGE_ATTRIBUTES = setOf("class", "alt", "src", "data-delayed-url", "loading", "width", "height")
        val json: JsonFactory = JsonFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
    }
}
