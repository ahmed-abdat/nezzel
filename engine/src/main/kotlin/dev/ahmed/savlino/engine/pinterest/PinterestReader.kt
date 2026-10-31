package dev.ahmed.savlino.engine.pinterest

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
 * One logged-out pin read via [MediaHttpClient.fetchPinterestPost], with a public widget fallback for sign-in walls,
 * then the pin's own image on `i.pinimg.com`. A video pin returns [ImageReadPlan.Video] for the same link.
 */
internal class PinterestReader(private val source: ValidatedLink) {
    private val identity = PinterestPostIdentity.parse(source.rawUrl)

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        val result = http.fetchPinterestPost(token, requested)
        return when (val page = readPage(result)) {
            is ImagePageRead.Body -> parse(page.body)
            is ImagePageRead.Failed -> {
                val signInWall = result is MediaHttpResult.Failure &&
                    (result.reason == MediaHttpFailure.LOGIN_REQUIRED ||
                        (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(401, 403)))
                if (!signInWall) return page.failure
                when (val widget = readPage(http.fetchPinterestPidgets(token, requested))) {
                    is ImagePageRead.Failed -> page.failure
                    is ImagePageRead.Body -> {
                        val parsed = PinterestPostParser().parsePidgets(widget.body.bytes, requested.pinId, widget.body.length)
                        if (parsed is PinterestParseResult.Failure) page.failure else plan(parsed)
                    }
                }
            }
        }
    }

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return plan(PinterestPostParser().parse(body.bytes, requested.pinId, body.length))
    }

    private fun plan(parsed: PinterestParseResult): ImageReadPlan = when (parsed) {
        is PinterestParseResult.Image -> ImageReadPlan.Photos(listOf(parsed.candidate), listOf(parsed.preview),
            parsed.discovery, MediaMetadata(parsed.title, parsed.author))
        PinterestParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false)
        is PinterestParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
    }

    companion object {
        val operation = MediaHttpOperation.PAGES_AND_CAROUSEL
        val imageRule = MediaHostRule.PINTEREST_CDN
        fun accepts(url: String): Boolean = PinterestPostIdentity.parse(url) != null
    }
}
