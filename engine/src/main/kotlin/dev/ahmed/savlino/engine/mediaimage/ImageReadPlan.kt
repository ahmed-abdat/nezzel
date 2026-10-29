package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.mediahttp.MediaHttpTarget
import dev.ahmed.savlino.engine.mediahttp.MediaHttpBody
import dev.ahmed.savlino.engine.x.XPostIdentity

/** A bounded page transfer or the shared transport failure; site interpretation stays in readers. */
internal sealed class ImagePageRead {
    class Body(val body: MediaHttpBody) : ImagePageRead()
    class Failed(val failure: ImageReadPlan.Failure) : ImagePageRead()
}

/** A site's verdict before the shared image fetch, validation, preview and Save pipeline. */
internal sealed interface ImageReadPlan {
    /**
     * [candidates] are post-owned original URLs (all photo sites); [previewCandidates] are the
     * smaller copies used by X, Instagram, Bluesky, Facebook and Reddit (Telegram uses originals).
     * [discovery] carries counts, completeness and mixed-video availability for all photo sites,
     * plus TikTok's sound capability. [metadata] carries titles (all sites) and authors (e.g. X).
     * [pageContext] binds LinkedIn images/Save to the fetched post.
     * [videoTarget] carries Facebook's canonical watch link or TikTok's post-bound sound link.
     * TikTok mirrors, positions and resize retry remain in its existing pipeline until step 1c.
     */
    class Photos(val candidates: List<String>, val previewCandidates: List<String>,
        val discovery: ImageDiscovery, val metadata: MediaMetadata,
        val pageContext: MediaHttpTarget? = null, val videoTarget: ValidatedLink? = null) : ImageReadPlan {
        override fun toString() = "ImageReadPlan.Photos(redacted)"
    }

    /**
     * [target] is the source post (Telegram, X, Instagram, Bluesky, Reddit) or canonical video
     * post (Facebook, LinkedIn, TikTok). [photosUnavailable] distinguishes X/Instagram mixed posts
     * whose photos failed from video-only posts. [infoJson] is X's already-read video info.
     * [xIdentity] is X's parsed post identity for the existing info-cache callback; other sites leave it null.
     */
    class Video(val target: ValidatedLink, val photosUnavailable: Boolean,
        val infoJson: String? = null, val xIdentity: XPostIdentity? = null) : ImageReadPlan {
        override fun toString() = "ImageReadPlan.Video(redacted)"
    }

    /**
     * [reason] and [phase] preserve site/parser failures (e.g. Telegram's refused photo URLs
     * fail in IMAGE). [statusCode] preserves HTTP failures (TikTok and shared page reads).
     * [fallback] is LinkedIn's clean reached post URL for one legacy attempt at the same revision.
     */
    class Failure(val reason: ImageFailure, val phase: ImagePhase, val statusCode: Int? = null,
        val fallback: ValidatedLink? = null) : ImageReadPlan {
        override fun toString() = "ImageReadPlan.Failure($reason, $phase, $statusCode)"
    }
}
