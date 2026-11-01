package dev.ahmed.savlino.engine.threads

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.MediaHostRule
import dev.ahmed.savlino.engine.mediahttp.MediaHttpBody
import dev.ahmed.savlino.engine.mediahttp.MediaHttpClient
import dev.ahmed.savlino.engine.mediahttp.MediaHttpFailure
import dev.ahmed.savlino.engine.mediahttp.MediaHttpOperation
import dev.ahmed.savlino.engine.mediahttp.MediaHttpResult
import dev.ahmed.savlino.engine.mediahttp.OperationToken
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePageRead
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.ImageReadPlan

/**
 * One logged-out page read via [MediaHttpClient.fetchThreadsPost]: navigation headers, no cookies,
 * [dev.ahmed.savlino.engine.mediahttp.MediaPageLimits.THREADS], then [MediaHostRule.THREADS_CDN] photos.
 * A video is one progressive MP4 in [ImageReadPlan.Video.infoJson], not a yt-dlp handoff by itself.
 */
internal class ThreadsReader(private val source: ValidatedLink) {
    private val identity = ThreadsPostIdentity.parse(source.rawUrl)
    private val parser = ThreadsPostParser()

    fun read(http: MediaHttpClient, token: OperationToken, readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        val result = http.fetchThreadsPost(token, requested)
        if (result is MediaHttpResult.Failure) pageFailure(result)?.let { return it }
        return when (val page = readPage(result)) {
            is ImagePageRead.Failed -> page.failure
            is ImagePageRead.Body -> parse(page.body)
        }
    }

    /** These statuses mean a private or removed post without exposing an HTTP status. */
    fun pageFailure(result: MediaHttpResult.Failure): ImageReadPlan.Failure? =
        if (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(400, 404, 410))
            ImageReadPlan.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE)
        else null

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = parser.parse(body.bytes, requested.code, body.length)) {
            is ThreadsParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
            is ThreadsParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false, infoJson = parsed.infoJson)
            is ThreadsParseResult.Photos -> ImageReadPlan.Photos(parsed.candidates, parsed.previewCandidates,
                parsed.discovery, MediaMetadata(parsed.title, parsed.author), videoInfoJson = parsed.videoInfoJson)
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGE_AND_CAROUSEL
        val imageRule = MediaHostRule.THREADS_CDN
        fun accepts(url: String): Boolean = ThreadsPostIdentity.parse(url) != null
    }
}
