package dev.ahmed.savlino.engine.x

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*

/**
 * One syndication JSON read via [MediaHttpClient.fetchXPost]: X page policy, [MediaPageLimits.X],
 * then [MediaHostRule.X_MEDIA] photos. Request construction and every transport guard stay in HTTP.
 */
internal class XReader(private val source: ValidatedLink) {
    private val identity = XPostIdentity.parse(source.rawUrl)

    fun read(http: MediaHttpClient, token: OperationToken,
        readPage: (MediaHttpResult) -> ImagePageRead): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val page = readPage(http.fetchXPost(token, requested))) {
            is ImagePageRead.Body -> parse(page.body)
            is ImagePageRead.Failed -> page.failure
        }
    }

    fun parse(body: MediaHttpBody): ImageReadPlan {
        val requested = identity ?: return ImageReadPlan.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        return when (val parsed = XPostParser().parse(body.bytes, requested.postId, body.length, requested.mediaIndex)) {
            is XParseResult.Failure -> ImageReadPlan.Failure(parsed.reason, parsed.phase)
            is XParseResult.Video -> ImageReadPlan.Video(source, false, parsed.infoJson, requested)
            is XParseResult.PhotosUnavailableVideo -> ImageReadPlan.Video(source, true, parsed.infoJson, requested)
            is XParseResult.Success -> {
                val carousel = parsed.candidates.size > 1 || parsed.discovery.unavailableCount > 0 || parsed.discovery.cappedCount > 0
                val originals = if (carousel) parsed.candidates.map {
                    HttpsUrl.parse(it)!!.newBuilder().setQueryParameter("name", "orig").build().toString()
                } else parsed.candidates
                ImageReadPlan.Photos(originals, parsed.candidates.map {
                    HttpsUrl.parse(it)!!.newBuilder().setQueryParameter("name", "small").build().toString()
                }, if (carousel) parsed.discovery.copy(previewQuality = false) else parsed.discovery,
                    MediaMetadata(parsed.title, parsed.author))
            }
        }
    }

    companion object {
        val operation = MediaHttpOperation.PAGE_AND_CAROUSEL
        val imageRule = MediaHostRule.X_MEDIA
        fun accepts(url: String): Boolean = XPostIdentity.parse(url) != null
    }
}
