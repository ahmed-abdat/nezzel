package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import okhttp3.HttpUrl

enum class InstagramLinkKind { POST, REEL, TV }

/**
 * A public Instagram post link: `instagram.com/p/<code>/`, `/reel/<code>/` (or `/reels/`), `/tv/<code>/`, with or without
 * `www.`/`m.`; any query (share tracking) is dropped. Only the shortcode is kept. Reels/TV are videos and never fetched
 * by this reader; a post is read only as `https://www.instagram.com/p/<code>/`.
 */
class InstagramPostIdentity private constructor(val kind: InstagramLinkKind, val code: String) {
    /** The one page this reader may request for a post: canonical, no query. */
    val fetchUrl: String get() = "https://$PAGE_HOST/p/$code/"

    /** Canonical post/video link for yt-dlp, including aliases and user-prefixed input paths. */
    val legacyUrl: String get() = "https://$PAGE_HOST/${when (kind) {
        InstagramLinkKind.POST -> "p"
        InstagramLinkKind.REEL -> "reel"
        InstagramLinkKind.TV -> "tv"
    }}/$code/"

    override fun toString() = "InstagramPostIdentity(redacted)"

    companion object {
        const val PAGE_HOST = "www.instagram.com"
        private val hosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am")
        private const val CODE = "[A-Za-z0-9_-]{5,64}"
        private val path = Regex("/(?:[A-Za-z0-9_.]{1,30}/)?(p|reels?|tv)/($CODE)/?")
        private val pagePath = Regex("/p/($CODE)/")

        fun parse(url: String): InstagramPostIdentity? {
            val parsed = MediaHttpPolicy.parse(url) ?: return null
            if (parsed.host.lowercase() !in hosts) return null
            val match = path.matchEntire(parsed.encodedPath) ?: return null
            val kind = when (match.groupValues[1]) {
                "p" -> InstagramLinkKind.POST
                "tv" -> InstagramLinkKind.TV
                else -> InstagramLinkKind.REEL
            }
            return InstagramPostIdentity(kind, match.groupValues[2])
        }

        /** True only for this reader's own page request: `https://www.instagram.com/p/<code>/`, no query. */
        internal fun isPageRequest(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host == PAGE_HOST &&
            url.encodedQuery == null && pagePath.matches(url.encodedPath)

        /** Instagram's logged-out walls: the login page and the checkpoint/challenge flow. */
        internal fun isLoginWall(url: HttpUrl): Boolean = url.host.lowercase() in hosts && loginWall.matches(url.encodedPath)
        /** Exactly `/accounts/login` or `/challenge`, optionally followed by `/` and subpaths (never `/challenge-x`). */
        private val loginWall = Regex("/(?:accounts/login|challenge)(?:/.*)?")

        /** Any Instagram web host (pages of other routes must never borrow it). */
        internal fun isInstagramHost(host: String): Boolean = host.lowercase() in hosts
    }
}
