package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*

/**
 * One logged-out post read via [MediaHttpClient.fetchInstagramPost]: INSTAGRAM page policy,
 * [MediaPageLimits.INSTAGRAM], then [MediaHostRule.INSTAGRAM_CDN] photos. HTTP retains all guards.
 */
internal class InstagramReader(private val source: ValidatedLink) {
    private val identity = InstagramPostIdentity.parse(source.rawUrl)

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val page = readPage(http.fetchInstagramPost(token, requested))) {
            is ImagePageRead.Body -> parse(page.body)
            is ImagePageRead.Failed -> page.failure
        }
    }

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = InstagramPostParser().parse(body.bytes, requested.code, body.length)) {
            is InstagramParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
            InstagramParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false)
            InstagramParseResult.PhotosUnavailableVideo -> ImageReadPlan.Video(source, photosUnavailable = true)
            is InstagramParseResult.Success -> ImageReadPlan.Photos(parsed.candidates, parsed.previewCandidates,
                parsed.discovery, MediaMetadata(parsed.title, parsed.author))
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGE_AND_CAROUSEL
        val imageRule = MediaHostRule.INSTAGRAM_CDN
        fun accepts(url: String): Boolean = InstagramPostIdentity.parse(url) != null
    }
}
