package dev.ahmed.savlino.engine.mediahttp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.PreviewMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.HttpUrl

/** Display metadata only; never an inspection or a source of selectable formats. Memory only. */
class EarlyPreview(val title: String?, val thumbnailUrl: String?) {
    override fun toString() = "EarlyPreview(redacted)"
}

/** Optional, bounded read inside the authorized inspection. Uses the guarded media HTTP transport. */
class YouTubeOEmbed internal constructor(private val http: MediaHttpClient,
    private val fetch: (OperationToken, String) -> MediaHttpResult) {
    constructor(http: MediaHttpClient) : this(http, http::fetchYouTubeOEmbed)

    suspend fun load(source: String): EarlyPreview? {
        val request = requestUrl(source) ?: return null
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val token = http.newYouTubeOEmbedOperation()
                continuation.invokeOnCancellation { token.close() }
                val preview = token.use operation@{
                    try {
                        if (!continuation.isActive) return@operation null
                        val result = fetch(token, request) as? MediaHttpResult.Success ?: return@operation null
                        result.response.use response@{ response ->
                            if (response.measuredBytes > MAX_BYTES) return@response null
                            val body = response.takeBody()
                            val root = json.readTree(body.bytes, 0, body.length)
                            if (root == null || !root.isObject) return@response null
                            val title = PreviewMetadata.text(root.get("title")?.takeIf { it.isTextual }?.asText(), 300)
                            val thumbnail = root.get("thumbnail_url")?.takeIf { it.isTextual }?.asText()
                                ?.let(PreviewMetadata::thumbnail)?.takeIf { raw ->
                                    MediaHttpPolicy.parse(raw)?.let { url -> url.host in THUMBNAIL_HOSTS } == true
                                }
                            if (title == null && thumbnail == null) null else EarlyPreview(title, thumbnail)
                        }
                    } catch (_: Exception) { null } // Optional: no URL-bearing diagnostics or visible failure.
                }
                if (continuation.isActive) continuation.resume(preview)
            }
        }
    }

    companion object {
        internal const val MAX_BYTES = 32 * 1024
        private val json = ObjectMapper()
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")
        private val THUMBNAIL_HOSTS = setOf("i.ytimg.com", "img.youtube.com")
        private val ID = Regex("[A-Za-z0-9_-]{11}")
        private fun videoId(source: String): String? {
            val url = MediaHttpPolicy.parse(source) ?: return null
            if (url.host !in HOSTS) return null
            val id = when {
                url.host == "youtu.be" || url.host == "www.youtu.be" -> url.pathSegments.singleOrNull()
                url.encodedPath == "/watch" -> url.queryParameterValues("v").singleOrNull()
                url.pathSegments.size == 2 && url.pathSegments[0] in setOf("shorts", "embed", "live") -> url.pathSegments[1]
                else -> null
            }
            return id?.takeIf(ID::matches)
        }
        fun supports(source: String): Boolean = videoId(source) != null
        /** The image oEmbed names for this video, known before any request, so the tile can load while oEmbed runs. */
        fun thumbnail(source: String): String? = videoId(source)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        internal fun requestUrl(source: String): String? = videoId(source)?.let { id ->
            HttpUrl.Builder().scheme("https").host("www.youtube.com").addPathSegment("oembed")
                .addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
                .addQueryParameter("format", "json").build().toString()
        }
        internal fun isRequest(url: HttpUrl): Boolean = url.host == "www.youtube.com" && url.encodedPath == "/oembed" &&
            url.queryParameterValues("format") == listOf("json") && url.queryParameterNames == setOf("url", "format") &&
            url.queryParameterValues("url").singleOrNull()?.let { requestUrl(it) == url.toString() } == true
    }
}
