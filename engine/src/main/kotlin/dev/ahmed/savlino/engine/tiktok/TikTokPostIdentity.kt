package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

/** Public post identities only. Tracking parameters never reach the page reader or sound handoff. */
class TikTokPostIdentity private constructor(val username: String, val postId: String, val isPhoto: Boolean) {
    val videoUrl: String get() = "https://www.tiktok.com/@$username/video/$postId"
    override fun toString() = "TikTokPostIdentity(redacted)"

    companion object {
        internal val hosts = setOf("www.tiktok.com", "tiktok.com", "m.tiktok.com")
        private val path = Regex("/@([A-Za-z0-9_.]{1,64})/(photo|video)/([1-9][0-9]{0,24})/?")
        fun parse(raw: String): TikTokPostIdentity? {
            val url = HttpsUrl.parse(raw) ?: return null
            if (url.host !in hosts) return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            return TikTokPostIdentity(match.groupValues[1], match.groupValues[3], match.groupValues[2] == "photo")
        }
        internal fun isPageRequest(url: HttpUrl): Boolean = parse(url.toString())?.videoUrl == url.toString()
    }
}
