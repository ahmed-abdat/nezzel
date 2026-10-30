package dev.ahmed.savlino.engine.pinterest

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.MediaHostRule
import dev.ahmed.savlino.engine.mediahttp.MediaHttpBody
import dev.ahmed.savlino.engine.mediahttp.MediaHttpClient
import dev.ahmed.savlino.engine.mediahttp.MediaHttpOperation
import dev.ahmed.savlino.engine.mediahttp.MediaHttpResult
import dev.ahmed.savlino.engine.mediahttp.OperationToken
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePageRead
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.ImageReadPlan

/**
 * One logged-out pin read via [MediaHttpClient.fetchPinterestPost]: the canonical pin page, then the pin's own
 * image on `i.pinimg.com`. A video pin returns [ImageReadPlan.Video] so the existing path reads the same link.
 */
internal class PinterestReader(private val source: ValidatedLink) {
    private val identity = PinterestPostIdentity.parse(source.rawUrl)

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val page = readPage(http.fetchPinterestPost(token, requested))) {
            is ImagePageRead.Body -> parse(page.body)
            is ImagePageRead.Failed -> page.failure
        }
    }

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = PinterestPostParser().parse(body.bytes, requested.pinId, body.length)) {
            is PinterestParseResult.Image -> ImageReadPlan.Photos(listOf(parsed.candidate), listOf(parsed.preview),
                parsed.discovery, MediaMetadata(parsed.title, parsed.author))
            PinterestParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false)
            is PinterestParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGE_AND_CAROUSEL
        val imageRule = MediaHostRule.PINTEREST_CDN
        fun accepts(url: String): Boolean = PinterestPostIdentity.parse(url) != null
    }
}
