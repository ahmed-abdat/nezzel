package dev.ahmed.savlino.engine.spotify

import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Two bounded page reads at most, sharing the media client's deadline, DNS guards and cancellation. */
class SpotifyReader internal constructor(private val http: MediaHttpClient,
    private val fetch: (OperationToken, PublicMediaRequest) -> MediaHttpResult) {
    constructor(http: MediaHttpClient) : this(http, http::fetch)

    suspend fun load(source: String): SpotifyMetadata? {
        val link = SpotifyLink.parse(source) ?: return null
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val token = http.newOperation(MediaHttpOperation.PAGES_AND_IMAGE)
                continuation.invokeOnCancellation { token.close() }
                val metadata = token.use operation@{
                    try {
                        if (!continuation.isActive) return@operation null
                        // Transport failure is final; oEmbed is only a parse fallback.
                        val embed = body(token, link.embedUrl) ?: return@operation null
                        SpotifyMetadata.parseEmbed(embed, link.type)?.let { return@operation it }
                        if (!continuation.isActive) return@operation null
                        body(token, link.oembedUrl)?.let(SpotifyMetadata::parseOEmbed)
                    } catch (_: Exception) { null } // Never expose URL-bearing network/parser diagnostics.
                }
                if (continuation.isActive) continuation.resume(metadata)
            }
        }
    }

    private fun body(token: OperationToken, url: String): String? {
        val result = fetch(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE)) as? MediaHttpResult.Success ?: return null
        return result.response.use { response ->
            if (response.measuredBytes > SpotifyMetadata.MAX_BYTES) return@use null
            val body = response.takeBody()
            String(body.bytes, 0, body.length, Charsets.UTF_8)
        }
    }
}
