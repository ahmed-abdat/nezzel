package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

enum class FacebookLinkKind { SHARE, SHORTLINK, STORY, PERMALINK, POST, PHOTO, REEL, WATCH, VIDEO }

/**
 * A public Facebook link from the exact accepted shapes, normalised to www.facebook.com with only the
 * identifying query kept (tracking parameters dropped). Shortlinks carry no ids until the transport
 * resolves them. Ids are node ids only when numeric; a pfbid token is never compared with a node id.
 */
class FacebookPostIdentity private constructor(val kind: FacebookLinkKind, val fetchUrl: String,
    val postId: String?, val photoId: String?, val videoId: String?, val isVideoShare: Boolean = false) {
    val isShortlink: Boolean get() = kind == FacebookLinkKind.SHARE || kind == FacebookLinkKind.SHORTLINK

    internal fun fetchUrlOn(host: String): String = if (host == "web.facebook.com")
        fetchUrl.replaceFirst(WWW, "https://web.facebook.com") else fetchUrl

    /** The page is the post or photo itself (not a video/reel surface), so its og:image describes it. */
    internal val isPostOrPhoto: Boolean get() = kind in setOf(FacebookLinkKind.STORY, FacebookLinkKind.PERMALINK,
        FacebookLinkKind.POST, FacebookLinkKind.PHOTO, FacebookLinkKind.SHARE)

    companion object {
        private const val WWW = "https://www.facebook.com"
        private val hosts = setOf("www.facebook.com", "facebook.com", "m.facebook.com", "web.facebook.com")
        /** Mobile and locale hosts (ar-ar.facebook.com) serve the same posts; parsed links always fetch www/web. */
        private val inputHosts = hosts + setOf("mbasic.facebook.com", "touch.facebook.com", "mobile.facebook.com")
        private val localeHost = Regex("[a-z]{2}-[a-z]{2}\\.facebook\\.com")
        private val token = Regex("[A-Za-z0-9_-]{1,64}")
        private val numeric = Regex("[0-9]{1,25}")
        private val storyId = Regex("[0-9]{1,25}|pfbid[A-Za-z0-9]{1,80}")
        private val segment = Regex("[A-Za-z0-9._-]{1,100}")
        private val share = Regex("/share/(?:([pvr])/)?(${token.pattern})/?")
        private val shortlink = Regex("/(${token.pattern})/?")
        private val posts = Regex("/(?:groups/)?(${segment.pattern})/(?:posts|permalink)/(?:[^/]{1,512}/)?([0-9]{1,25}|pfbid[A-Za-z0-9]{1,80})/?")
        private val photos = Regex("/(${segment.pattern})/photos/(?:[a-z]{1,8}\\.[0-9]{1,25}/)?([0-9]{1,25})/?")
        private val reel = Regex("/reel/([0-9]{1,25})/?")
        private val group = Regex("/groups/(${segment.pattern})/?")
        private val videos = Regex("/(${segment.pattern})/videos/(?:[^/]{1,512}/)?([0-9]{1,25})/?")

        fun parse(url: String): FacebookPostIdentity? {
            if ('#' in url) return null
            val parsed = HttpsUrl.parse(url) ?: return null
            val host = parsed.host.lowercase()
            val path = parsed.encodedPath
            if (host == "fb.watch") {
                return shortlink.matchEntire(path)?.let {
                    FacebookPostIdentity(FacebookLinkKind.SHORTLINK, "https://fb.watch$path", null, null, null)
                }
            }
            if (!isFacebookHost(parsed)) return null
            if (path.startsWith("/events/") || path.startsWith("/stories/")) return null
            share.matchEntire(path)?.let { return FacebookPostIdentity(FacebookLinkKind.SHARE, WWW + path, null, null, null,
                it.groupValues[1] in setOf("v", "r")) }
            when (path) {
                "/story.php", "/permalink.php" -> {
                    val story = single(parsed, "story_fbid", storyId) ?: return null
                    val owner = single(parsed, "id", numeric) ?: return null
                    val kind = if (path == "/story.php") FacebookLinkKind.STORY else FacebookLinkKind.PERMALINK
                    return FacebookPostIdentity(kind, "$WWW$path?story_fbid=$story&id=$owner",
                        story.takeIf(numeric::matches), null, null)
                }
                "/photo", "/photo/", "/photo.php" -> {
                    val photo = single(parsed, "fbid", numeric) ?: return null
                    return FacebookPostIdentity(FacebookLinkKind.PHOTO, "$WWW/photo/?fbid=$photo", null, photo, null)
                }
                "/watch", "/watch/" -> {
                    val video = single(parsed, "v", numeric) ?: return null
                    return FacebookPostIdentity(FacebookLinkKind.WATCH, "$WWW/watch/?v=$video", null, null, video)
                }
            }
            reel.matchEntire(path)?.let { return FacebookPostIdentity(FacebookLinkKind.REEL, WWW + path, null, null, it.groupValues[1]) }
            photos.matchEntire(path)?.let { return FacebookPostIdentity(FacebookLinkKind.PHOTO, WWW + path, null, it.groupValues[2], null) }
            videos.matchEntire(path)?.let { return FacebookPostIdentity(FacebookLinkKind.VIDEO, WWW + path, null, null, it.groupValues[2]) }
            // A group feed opened on one post: /groups/<g>/?multi_permalinks=<id> is that group post.
            group.matchEntire(path)?.let { match ->
                val id = single(parsed, "multi_permalinks", numeric) ?: return null
                return FacebookPostIdentity(FacebookLinkKind.POST, "$WWW/groups/${match.groupValues[1]}/posts/$id/", id, null, null)
            }
            posts.matchEntire(path)?.let { match ->
                val id = match.groupValues[2]
                return FacebookPostIdentity(FacebookLinkKind.POST, WWW + path, id.takeIf(numeric::matches), null, null)
            }
            return null
        }

        /** Any accepted facebook.com input host, including ones we never fetch; every guard uses this same family. */
        internal fun isFacebookHost(url: HttpUrl): Boolean = url.host.lowercase().let { it in inputHosts || localeHost.matches(it) }

        /** Event pages are unsupported, including mobile hosts and nested event paths. */
        fun isEventLink(url: String): Boolean = HttpsUrl.parse(url)?.let {
            isFacebookHost(it) && it.encodedPath.startsWith("/events/")
        } == true

        /** Ephemeral stories always need sign-in; story.php is a separate, supported post shape. */
        fun isStoryLink(url: String): Boolean = HttpsUrl.parse(url)?.let {
            isFacebookHost(it) && it.encodedPath.startsWith("/stories/")
        } == true

        private val loginPaths = setOf("/login", "/login/", "/login.php", "/checkpoint", "/checkpoint/")

        /** Exactly /login, /login/, /login.php, /checkpoint(/) on any Facebook host: the logged-out wall. */
        internal fun isLoginWall(url: HttpUrl): Boolean = isFacebookHost(url) && url.encodedPath in loginPaths

        private fun single(url: HttpUrl, name: String, grammar: Regex): String? =
            url.queryParameterValues(name).singleOrNull()?.takeIf { grammar.matches(it) }
    }

    override fun toString() = "FacebookPostIdentity(redacted)"
}
