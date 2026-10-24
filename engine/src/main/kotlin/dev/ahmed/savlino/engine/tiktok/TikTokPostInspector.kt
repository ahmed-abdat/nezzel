package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*

/** One desktop page read. The shared inspector owns previews, cancellation and Save admission. */
class TikTokPostInspector(private val http: MediaHttpClient) {
    internal fun read(token: OperationToken, identity: TikTokPostIdentity): TikTokPageResult {
        val response = when (val result = http.fetchTikTokPost(token, identity)) {
            is MediaHttpResult.Failure -> return TikTokPageResult.Failure(mapTransport(result.reason), result.statusCode)
            is MediaHttpResult.Success -> result.response
        }
        return try {
            val landing = TikTokPostIdentity.parse(response.target.url())
            if (landing?.postId != identity.postId) return TikTokPageResult.Failure(ImageFailure.SOURCE_REFUSED)
            val body = response.takeBody()
            TikTokPageResult.Parsed(TikTokPostParser().parse(body.bytes, identity.postId, body.length))
        } catch (_: java.io.IOException) { TikTokPageResult.Failure(ImageFailure.NETWORK) }
        finally { response.close() }
    }

    companion object {
        /** Try only the known photo-mode template; keep its signed query verbatim. A refusal uses the original. */
        internal fun previewUrl(original: String): String {
            val url = MediaHttpPolicy.parse(original) ?: return original
            val path = url.encodedPath
            val marker = "~tplv-photomode-image"
            if (!path.contains(marker)) return original
            val extension = path.substringAfterLast('.', "").takeIf { it in setOf("jpeg", "jpg", "webp") } ?: return original
            return url.newBuilder().encodedPath(path.substringBefore(marker) + "~tplv-photomode-zoomcover:256:256.$extension")
                .build().toString()
        }
    }
}
internal sealed interface TikTokPageResult {
    class Parsed(val result: TikTokParseResult) : TikTokPageResult
    data class Failure(val reason: ImageFailure, val statusCode: Int? = null) : TikTokPageResult
}
