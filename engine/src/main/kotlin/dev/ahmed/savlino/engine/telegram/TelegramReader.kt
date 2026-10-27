package dev.ahmed.savlino.engine.telegram

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*

/**
 * One public widget read via [MediaHttpClient.fetchTelegramPost]: TELEGRAM page policy,
 * [MediaPageLimits.TELEGRAM], identity.fetchUrl, then [MediaHostRule.TELEGRAM_CDN] photos.
 * The HTTP client retains request construction, redirect/host guards and the shared budget.
 */
internal class TelegramReader(private val source: ValidatedLink) {
    private val identity = TelegramPostIdentity.parse(source.rawUrl)

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        val result = http.fetchTelegramPost(token, requested)
        if (result is MediaHttpResult.Failure) pageFailure(result)?.let { return it }
        return when (val page = readPage(result)) {
            is ImagePageRead.Body -> parse(page.body)
            is ImagePageRead.Failed -> page.failure
        }
    }

    /** These widget statuses mean a private/unavailable post, without exposing an HTTP status. */
    fun pageFailure(result: MediaHttpResult.Failure): ImageReadPlan.Failure? =
        if (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(401, 403, 404, 410))
            ImageReadPlan.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE)
        else null

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = TelegramPostParser().parse(body.bytes, requested, body.length)) {
            is TelegramParseResult.Success -> ImageReadPlan.Photos(parsed.candidates, parsed.candidates,
                parsed.discovery, MediaMetadata(title = parsed.title))
            TelegramParseResult.Video -> ImageReadPlan.Video(source, photosUnavailable = false)
            is TelegramParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGE_AND_CAROUSEL
        val imageRule = MediaHostRule.TELEGRAM_CDN
        fun accepts(url: String): Boolean = TelegramPostIdentity.parse(url) != null
    }
}
