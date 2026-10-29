package dev.ahmed.savlino.engine.bluesky

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*

/**
 * At most two exact public API reads via [MediaHttpClient.fetchBlueskyApi]: BLUESKY page policy,
 * [MediaPageLimits.BLUESKY], then [MediaHostRule.BLUESKY_CDN] photos. A DID skips handle resolution;
 * both reads share the token, deadline and budget. HTTP retains request construction and guards.
 */
internal class BlueskyReader(private val source: ValidatedLink) {
    private val identity = BlueskyPostIdentity.parse(source.rawUrl)
    private val parser = BlueskyPostParser()

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        fun read(url: String): ImagePageRead {
            val result = http.fetchBlueskyApi(token, url)
            if (result is MediaHttpResult.Failure) pageFailure(result)?.let { return ImagePageRead.Failed(it) }
            return readPage(result)
        }
        val did = requested.did ?: when (val page = read(requested.resolveUrl)) {
            is ImagePageRead.Failed -> return page.failure
            is ImagePageRead.Body -> parser.resolveDid(page.body.bytes, page.body.length)
                ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        }
        return when (val page = read(requested.threadUrl(did))) {
            is ImagePageRead.Body -> parse(page.body, did)
            is ImagePageRead.Failed -> page.failure
        }
    }

    /** These API statuses mean a private/unavailable post without exposing an HTTP status. */
    fun pageFailure(result: MediaHttpResult.Failure): ImageReadPlan.Failure? =
        if (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(400, 404, 410))
            ImageReadPlan.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE)
        else null

    fun parse(body: MediaHttpBody, did: String): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = parser.parse(body.bytes, requested.atUri(did), body.length)) {
            is BlueskyParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
            BlueskyParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false)
            is BlueskyParseResult.Success -> ImageReadPlan.Photos(parsed.candidates, parsed.previewCandidates,
                parsed.discovery, MediaMetadata(parsed.title, parsed.author))
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGES_AND_CAROUSEL
        val imageRule = MediaHostRule.BLUESKY_CDN
        fun accepts(url: String): Boolean = BlueskyPostIdentity.parse(url) != null
    }
}
