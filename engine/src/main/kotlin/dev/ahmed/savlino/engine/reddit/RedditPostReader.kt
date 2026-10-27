package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.mediahttp.MediaHttpClient
import dev.ahmed.savlino.engine.mediahttp.MediaHttpFailure
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaHttpResult
import dev.ahmed.savlino.engine.mediahttp.OperationToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What yt-dlp reads in place of a Reddit post link. Memory only; never logged. */
internal sealed interface RedditTarget {
    /** [url]: the video's manifest or a link post's page; [video] carries the post's own title and picture. */
    class Read(val url: String, val video: RedditPost.Video?) : RedditTarget {
        override fun toString() = "RedditTarget.Read(redacted)"
    }
    data class Failed(val error: DownloadError) : RedditTarget
}

/**
 * yt-dlp's reddit extractor needs Reddit's JSON API, which refuses logged-out apps (2026-10); the post page still
 * serves logged-out readers. Null means "keep the old path": not a post link, or a page this reader can't place.
 */
internal class RedditPostReader(private val http: MediaHttpClient,
    private val fetch: (OperationToken, RedditPostIdentity) -> MediaHttpResult = http::fetchRedditPost) {

    suspend fun resolve(raw: String): RedditTarget? {
        val identity = RedditPostIdentity.parse(raw) ?: return null
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val token = http.newRedditOperation()
                continuation.invokeOnCancellation { token.close() }
                val target = token.use { read(it, identity) }
                if (continuation.isActive) continuation.resume(target)
            }
        }
    }

    internal fun read(token: OperationToken, identity: RedditPostIdentity): RedditTarget? {
        val response = when (val result = fetch(token, identity)) {
            is MediaHttpResult.Failure -> return failure(result.reason, result.statusCode)
            is MediaHttpResult.Success -> result.response
        }
        return response.use {
            val postId = MediaHttpPolicy.parse(it.target.url())?.let(RedditPostIdentity::postHop)?.postId ?: return@use null
            val body = try { it.takeBody() } catch (_: java.io.IOException) { return@use RedditTarget.Failed(DownloadError.Network) }
            when (val post = RedditPostParser.parse(body.bytes, body.length, postId, identity.videoId)) {
                is RedditPost.Video -> RedditTarget.Read(post.manifestUrl, post)
                is RedditPost.Link -> RedditTarget.Read(post.url, null)
                // Photos are the image reader's; yt-dlp has no video to read here.
                is RedditPost.Photos, RedditPost.NoVideo -> RedditTarget.Failed(DownloadError.NoVideo)
                RedditPost.LoginRequired -> RedditTarget.Failed(DownloadError.LoginRequired)
                RedditPost.Unknown -> null
            }
        }
    }

    private fun failure(reason: MediaHttpFailure, status: Int?): RedditTarget? = when (reason) {
        MediaHttpFailure.LOGIN_REQUIRED -> RedditTarget.Failed(DownloadError.LoginRequired)
        MediaHttpFailure.NETWORK, MediaHttpFailure.TIMEOUT -> RedditTarget.Failed(DownloadError.Network)
        MediaHttpFailure.HTTP_STATUS -> when (status) {
            404, 410 -> RedditTarget.Failed(DownloadError.Unavailable)
            403, 429 -> RedditTarget.Failed(DownloadError.SourceRefused)
            else -> null
        }
        else -> null
    }
}
