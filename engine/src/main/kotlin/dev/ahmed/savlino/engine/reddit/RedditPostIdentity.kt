package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

/**
 * A public Reddit post link and the one www page that reads it. Tracking queries never reach the page read.
 * Post links keep their slug: a slugless `/r/<sub>/comments/<id>/` serves a shell without the post, so it reads
 * `/comments/<id>/`, which Reddit redirects to the full permalink. Share (`/s/`) and video (`v.redd.it`) links
 * name no post id until their redirect does. A gallery share link (`/gallery/<id>`) is the bare id page of its post.
 */
class RedditPostIdentity private constructor(val fetchUrl: String, val postId: String?, val videoId: String?) {
    override fun toString() = "RedditPostIdentity(redacted)"

    companion object {
        private val HOSTS = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "m.reddit.com",
            "np.reddit.com", "sh.reddit.com")
        private const val NAME = "[A-Za-z0-9_-]{1,64}"
        private const val ID = "[a-z0-9]{1,16}"
        private val post = Regex("/(?:(?:r|user|u)/($NAME)/)?comments/($ID)(?:/([A-Za-z0-9_%-]{1,300}))?(?:/[a-z0-9]{1,16})?/?")
        private val share = Regex("/(?:r|u|user)/$NAME/s/[A-Za-z0-9]{1,32}/?")
        private val video = Regex("/($ID)/?")
        private val videoPage = Regex("/video/($ID)/?")
        private val gallery = Regex("/gallery/($ID)/?")

        fun parse(raw: String): RedditPostIdentity? = HttpsUrl.parse(raw)?.let(::parse)

        internal fun parse(url: HttpUrl): RedditPostIdentity? {
            if (!url.isHttps || url.port != 443) return null
            val path = url.encodedPath
            return when (url.host) {
                "redd.it" -> video.matchEntire(path)?.let { post(null, it.groupValues[1], null) }
                "v.redd.it" -> video.matchEntire(path)?.let {
                    RedditPostIdentity("https://www.reddit.com/video/${it.groupValues[1]}/", null, it.groupValues[1])
                }
                in HOSTS -> post.matchEntire(path)?.let { match ->
                    val (owner, id, slug) = match.destructured
                    // A user-profile post keeps its own path; Reddit redirects it as needed.
                    val kind = path.substringAfter('/').substringBefore('/')
                    post(owner.takeIf { it.isNotEmpty() }?.let { "$kind/$it" }, id, slug.takeIf { it.isNotEmpty() })
                } ?: gallery.matchEntire(path)?.let { post(null, it.groupValues[1], null) }
                    ?: share.matchEntire(path)?.let { RedditPostIdentity("https://www.reddit.com$path".trimEnd('/') + "/", null, null) }
                    ?: videoPage.matchEntire(path)?.let {
                        RedditPostIdentity("https://www.reddit.com/video/${it.groupValues[1]}/", null, it.groupValues[1])
                    }
                else -> null
            }
        }

        private fun post(owner: String?, id: String, slug: String?) = RedditPostIdentity(
            if (owner != null && slug != null) "https://www.reddit.com/$owner/comments/$id/$slug/"
            else "https://www.reddit.com/comments/$id/", id, null)

        /** The post a redirect or a final page names, in its query-free www form; null for anything else. */
        internal fun postHop(url: HttpUrl): RedditPostIdentity? = parse(url)?.takeIf { it.postId != null && url.host in HOSTS }

        /** Reddit's own sign-in wall; never followed. */
        internal fun isLoginWall(url: HttpUrl): Boolean = url.host in HOSTS &&
            Regex("/(?:login|account/login|register)/?").matches(url.encodedPath)

        /**
         * Reddit's redirect carries the title slug as raw UTF-8 (a Czech post, 2026-10-27); percent-encoded, it is the
         * same path. Null for a lone surrogate.
         */
        internal fun asciiLocation(raw: String): String? = buildString {
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                if (cp in 0xD800..0xDFFF) return null
                if (cp < 0x80) appendCodePoint(cp)
                else String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it.toInt() and 0xFF)) }
                i += Character.charCount(cp)
            }
        }

        internal fun isRedditHost(host: String): Boolean = host in HOSTS || host == "redd.it" || host == "v.redd.it"
    }
}
