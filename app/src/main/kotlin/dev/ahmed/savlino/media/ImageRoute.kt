package dev.ahmed.savlino.media

import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.domain.ValidatedLink
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.facebook.FacebookLinkKind
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramLinkKind
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import dev.ahmed.savlino.engine.mediaimage.MediaInspectResult
import dev.ahmed.savlino.engine.x.XPostIdentity
import dev.ahmed.savlino.engine.telegram.TelegramPostIdentity
import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Which inspector a reviewed link goes to. Pure: no network, no engine state. */
object ImageRoute {
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")

    /** Video-only Facebook surfaces skip the page fetch: yt-dlp reads them directly. */
    private val facebookVideoKinds = setOf(FacebookLinkKind.REEL, FacebookLinkKind.WATCH, FacebookLinkKind.VIDEO,
        FacebookLinkKind.SHORTLINK)

    /** A new provider adds its route here and one branch in the ViewModel's exhaustive `when`. */
    fun of(link: ValidatedLink): MediaSourceRoute = when {
        LinkedInPostIdentity.parse(link.rawUrl) != null -> MediaSourceRoute.LINKEDIN
        FacebookPostIdentity.parse(link.rawUrl)?.let(::isFacebookPost) == true -> MediaSourceRoute.FACEBOOK
        // X post links read their photos first; a video/GIF post is handed back to yt-dlp unchanged.
        XPostIdentity.parse(link.rawUrl) != null -> MediaSourceRoute.X
        // Instagram /p/ posts read their photos first; reels and /tv/ are videos and stay on yt-dlp directly.
        InstagramPostIdentity.parse(link.rawUrl)?.kind == InstagramLinkKind.POST -> MediaSourceRoute.INSTAGRAM
        dev.ahmed.savlino.engine.tiktok.TikTokPostIdentity.parse(link.rawUrl)?.isPhoto == true -> MediaSourceRoute.TIKTOK
        TelegramPostIdentity.parse(link.rawUrl) != null -> MediaSourceRoute.TELEGRAM
        BlueskyPostIdentity.parse(link.rawUrl) != null -> MediaSourceRoute.BLUESKY
        isImagePath(link.rawUrl) || isKnownImageCdn(link.rawUrl) -> MediaSourceRoute.DIRECT_IMAGE
        else -> MediaSourceRoute.LEGACY
    }

    /** Share links need the reader to find the bound post's video or photos. */
    private fun isFacebookPost(identity: FacebookPostIdentity): Boolean = identity.kind !in facebookVideoKinds

    fun isUnsupported(link: ValidatedLink): Boolean = FacebookPostIdentity.isEventLink(link.rawUrl) ||
        FacebookPostIdentity.isStoryLink(link.rawUrl)

    private fun isKnownImageCdn(raw: String): Boolean {
        val uri = try { URI(raw) } catch (_: URISyntaxException) { return false }
        return uri.scheme == "https" && uri.host == "pbs.twimg.com" &&
            Regex("/media/[A-Za-z0-9_-]{1,64}").matches(uri.rawPath.orEmpty()) &&
            uri.rawQuery.orEmpty().split('&').count { it.startsWith("format=") } == 1 &&
            uri.rawQuery.orEmpty().split('&').any { it in setOf("format=jpg", "format=png", "format=webp", "format=gif") }
    }

    /** The path's last segment ends in a image extension; the query and host never count. */
    private fun isImagePath(raw: String): Boolean {
        val name = (try { URI(raw).rawPath } catch (_: URISyntaxException) { null } ?: return false)
            .substringAfterLast('/').lowercase(Locale.ROOT)
        return imageExtensions.any { name.length > it.length && name.endsWith(it) }
    }
}

/** App seam over U5b's MediaSourceInspector, used for image routes only. */
interface ImageInspect {
    suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute): MediaInspectResult
    fun cancel(operationId: String)
}
