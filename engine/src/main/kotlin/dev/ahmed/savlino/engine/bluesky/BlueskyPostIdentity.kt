package dev.ahmed.savlino.engine.bluesky

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Only public post links; DID paths (did:plc, did:web) never make a handle-resolution request. The post's
 * likes/reposted-by/quotes views are the post itself.
 */
class BlueskyPostIdentity private constructor(val actor: String, val rkey: String) {
    val did: String? get() = actor.takeIf(::validDid)
    val resolveUrl: String get() = "$API/com.atproto.identity.resolveHandle".toHttpUrl().newBuilder()
        .addQueryParameter("handle", actor).build().toString()
    fun atUri(did: String): String { require(validDid(did)); return "at://$did/app.bsky.feed.post/$rkey" }
    fun threadUrl(did: String): String = "$API/app.bsky.feed.getPostThread".toHttpUrl().newBuilder()
        .addQueryParameter("uri", atUri(did)).addQueryParameter("depth", "0").build().toString()
    override fun toString() = "BlueskyPostIdentity(redacted)"

    companion object {
        internal const val ENTRY_HOST = "bsky.app"
        const val API_HOST = "public.api.bsky.app"
        private const val API = "https://$API_HOST/xrpc"
        private val path = Regex("/profile/([^/]+)/post/([A-Za-z0-9_-]{1,64})(?:/(?:likes|reposted-by|quotes))?/?")
        private const val HOST_NAME = "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"
        /** A DID this reader accepts: did:plc, or did:web on a plain lowercase host name (no port). */
        internal const val DID = "did:plc:[a-z2-7]{24}|did:web:$HOST_NAME"
        private val did = Regex(DID)
        private val threadUri = Regex("at://(?:$DID)/app\\.bsky\\.feed\\.post/[A-Za-z0-9_-]{1,64}")
        private val handle = Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        internal fun validDid(value: String): Boolean = value.length <= 253 && did.matches(value)
        fun parse(raw: String): BlueskyPostIdentity? {
            val url = HttpsUrl.parse(raw) ?: return null
            if (url.host != ENTRY_HOST) return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            val actor = match.groupValues[1]
            if (!validDid(actor) && !(actor.length <= 253 && handle.matches(actor))) return null
            return BlueskyPostIdentity(actor, match.groupValues[2])
        }
        internal fun isApiRequest(url: HttpUrl): Boolean {
            if (!url.isHttps || url.host != API_HOST || url.port != 443) return false
            return when (url.encodedPath) {
                "/xrpc/com.atproto.identity.resolveHandle" -> url.querySize == 1 &&
                    url.queryParameterNames == setOf("handle") && url.queryParameter("handle")?.let { it.length <= 253 && handle.matches(it) } == true
                "/xrpc/app.bsky.feed.getPostThread" -> url.querySize == 2 && url.queryParameterNames == setOf("uri", "depth") &&
                    url.queryParameter("depth") == "0" && url.queryParameter("uri")?.let(threadUri::matches) == true
                else -> false
            }
        }
    }
}
