package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import okhttp3.HttpUrl

enum class FacebookLinkKind { SHARE, SHORTLINK, STORY, PERMALINK, POST, PHOTO, REEL, WATCH, VIDEO }

/**
 * A public Facebook link from the exact accepted shapes, normalised to www.facebook.com with only the
 * identifying query kept (tracking parameters dropped). Shortlinks carry no ids until the transport
 * resolves them. Ids are node ids only when numeric; a pfbid token is never compared with a node id.
 */
class FacebookPostIdentity private constructor(val kind: FacebookLinkKind, val fetchUrl: String,
    val postId: String?, val photoId: String?, val videoId: String?) {
    val isShortlink: Boolean get() = kind == FacebookLinkKind.SHARE || kind == FacebookLinkKind.SHORTLINK

    /** The page is the post or photo itself (not a video/reel surface), so its og:image describes it. */
    internal val isPostOrPhoto: Boolean get() = kind in setOf(FacebookLinkKind.STORY, FacebookLinkKind.PERMALINK,
        FacebookLinkKind.POST, FacebookLinkKind.PHOTO, FacebookLinkKind.SHARE)

    companion object {
        private const val WWW = "https://www.facebook.com"
        private val hosts = setOf("www.facebook.com", "facebook.com", "m.facebook.com", "web.facebook.com")
        private val token = Regex("[A-Za-z0-9_-]{1,64}")
        private val numeric = Regex("[0-9]{1,25}")
        private val storyId = Regex("[0-9]{1,25}|pfbid[A-Za-z0-9]{1,80}")
        private val segment = Regex("[A-Za-z0-9._-]{1,100}")
        private val share = Regex("/share/([pvr])/(${token.pattern})/?")
        private val shortlink = Regex("/(${token.pattern})/?")
        private val posts = Regex("/(?:groups/)?(${segment.pattern})/(?:posts|permalink)/(?:[^/]{1,512}/)?([0-9]{1,25}|pfbid[A-Za-z0-9]{1,80})/?")
        private val photos = Regex("/(${segment.pattern})/photos/(?:[a-z]{1,8}\\.[0-9]{1,25}/)?([0-9]{1,25})/?")
        private val reel = Regex("/reel/([0-9]{1,25})/?")
        private val videos = Regex("/(${segment.pattern})/videos/(?:[^/]{1,512}/)?([0-9]{1,25})/?")

        fun parse(url: String): FacebookPostIdentity? {
            if ('#' in url) return null
            val parsed = MediaHttpPolicy.parse(url) ?: return null
            val host = parsed.host.lowercase()
            val path = parsed.encodedPath
            if (host == "fb.watch") {
                return shortlink.matchEntire(path)?.let {
                    FacebookPostIdentity(FacebookLinkKind.SHORTLINK, "https://fb.watch$path", null, null, null)
                }
            }
            if (host !in hosts) return null
            share.matchEntire(path)?.let { return FacebookPostIdentity(FacebookLinkKind.SHARE, WWW + path, null, null, null) }
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
            posts.matchEntire(path)?.let { match ->
                val id = match.groupValues[2]
                return FacebookPostIdentity(FacebookLinkKind.POST, WWW + path, id.takeIf(numeric::matches), null, null)
            }
            return null
        }

        /** Any facebook.com host family page, including ones we never fetch (used to recognise login walls). */
        internal fun isFacebookHost(url: HttpUrl): Boolean = url.host.lowercase() in hosts

        private val loginPaths = setOf("/login", "/login/", "/login.php", "/checkpoint", "/checkpoint/")

        /** Exactly www/m/web /login, /login/, /login.php, /checkpoint(/): the logged-out wall. */
        internal fun isLoginWall(url: HttpUrl): Boolean = isFacebookHost(url) && url.encodedPath in loginPaths

        private fun single(url: HttpUrl, name: String, grammar: Regex): String? =
            url.queryParameterValues(name).singleOrNull()?.takeIf { grammar.matches(it) }
    }

    override fun toString() = "FacebookPostIdentity(redacted)"
}
