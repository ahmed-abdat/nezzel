package dev.ahmed.savlino.engine.threads

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import java.math.BigInteger
import okhttp3.HttpUrl

/** A public post link. Profile, reply and other paths are not posts. */
class ThreadsPostIdentity private constructor(val user: String, val code: String) {
    val fetchUrl: String get() = "https://$PAGE_HOST/@$user/post/$code"
    override fun toString() = "ThreadsPostIdentity(redacted)"

    companion object {
        const val PAGE_HOST = "www.threads.com"
        val hosts: Set<String> = setOf("threads.com", PAGE_HOST, "threads.net", "www.threads.net")
        private val path = Regex("/(?:@|%40)([A-Za-z0-9._]{1,30})/post/([A-Za-z0-9_-]{1,32})(?:/media)?/?")
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun parse(raw: String): ThreadsPostIdentity? {
            val url = HttpsUrl.parse(raw) ?: return null
            if (url.host !in hosts) return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            val user = match.groupValues[1]
            if (user == "." || user == ".." || user.startsWith('.') || user.endsWith('.')) return null
            return ThreadsPostIdentity(user, match.groupValues[2])
        }

        /** The one canonical page read: www.threads.com, no query, this post's path. */
        internal fun isPageRequest(url: HttpUrl): Boolean {
            if (!url.isHttps || url.port != 443 || url.host != PAGE_HOST || url.encodedQuery != null) return false
            val identity = parse(url.toString()) ?: return false
            return identity.fetchUrl == url.toString()
        }

        /** Instagram shortcode alphabet. Null when [code] is not that alphabet. */
        internal fun mediaId(code: String): String? {
            var n = BigInteger.ZERO
            val base = BigInteger.valueOf(64)
            for (ch in code) {
                val index = ALPHABET.indexOf(ch)
                if (index < 0) return null
                n = n.multiply(base).add(BigInteger.valueOf(index.toLong()))
            }
            return n.toString()
        }
    }
}
