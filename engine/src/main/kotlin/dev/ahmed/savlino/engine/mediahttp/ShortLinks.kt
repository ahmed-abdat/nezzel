package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.domain.ValidatedLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.HttpUrl

/** Redirect-only link shapes: their destination is known from the redirect alone, so no page is ever read. */
object ShortLinks {
    internal val hosts = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "buff.ly", "is.gd", "cutt.ly", "rb.gy",
        "fb.me", "pin.it", "lnkd.in", "vm.tiktok.com", "vt.tiktok.com")
    internal val instagramShareHosts = setOf("www.instagram.com", "instagram.com")
    internal const val PINTEREST_HOST = "api.pinterest.com"
    private val token = Regex("/[A-Za-z0-9_-]{1,64}/?")
    private val instagramShare = Regex("/share/(?:reel/|p/)?[A-Za-z0-9_-]{1,64}/?")
    private val pinterestRedirect = Regex("/url_shortener/[A-Za-z0-9_-]{1,64}/redirect/?")

    internal fun isShortLink(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && when (url.host) {
        // lnkd.in/p/ is LinkedIn's own post shortlink; its reader resolves it.
        "lnkd.in" -> !url.encodedPath.startsWith("/p/") && token.matches(url.encodedPath)
        "fb.me", "goo.gl" -> url.encodedPath.length in 2..128
        in hosts -> token.matches(url.encodedPath)
        in instagramShareHosts -> instagramShare.matches(url.encodedPath)
        PINTEREST_HOST -> pinterestRedirect.matches(url.encodedPath)
        else -> false
    }

    fun isShortLink(link: ValidatedLink): Boolean = HttpsUrl.parse(link.rawUrl)?.let(::isShortLink) == true
}

/** What a short link's redirect named. */
sealed interface ShortLinkOutcome {
    data class Resolved(val link: ValidatedLink) : ShortLinkOutcome
    /** Nothing usable was learned (interstitial, timeout, chain too long): the caller keeps today's path. */
    data object Unresolved : ShortLinkOutcome
    /** The redirect points at a non-public address or an unusable link: neither it nor the short link may be read. */
    data object Blocked : ShortLinkOutcome
}

/**
 * Bounded, optional redirect read of a [ShortLinks] link inside the authorized inspection: HTTPS only, at most three
 * hops (the landing one included), 4 s, no cookie, no body. The destination must pass the same public-address DNS
 * check as every hop. Memory only; never logged.
 */
class ShortLinkResolver internal constructor(private val http: MediaHttpClient,
    private val fetch: (OperationToken, String) -> MediaHttpResult,
    private val requirePublic: (OperationToken, HttpUrl) -> Unit) {
    constructor(http: MediaHttpClient) : this(http, http::fetchShortLink, http::requirePublicHost)

    suspend fun resolve(link: ValidatedLink): ShortLinkOutcome {
        if (!ShortLinks.isShortLink(link)) return ShortLinkOutcome.Unresolved
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val token = http.newShortLinkOperation()
                continuation.invokeOnCancellation { token.close() }
                val outcome = token.use {
                    try {
                        if (!continuation.isActive) ShortLinkOutcome.Unresolved else land(token, link)
                    } catch (failure: MediaHttpException) {
                        if (failure.reason == MediaHttpFailure.ADDRESS_REJECTED) ShortLinkOutcome.Blocked
                        else ShortLinkOutcome.Unresolved
                    } catch (_: Exception) { ShortLinkOutcome.Unresolved }
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        }
    }

    private fun land(token: OperationToken, link: ValidatedLink): ShortLinkOutcome {
        val failure = fetch(token, link.rawUrl) as? MediaHttpResult.Failure ?: return ShortLinkOutcome.Unresolved
        if (failure.reason == MediaHttpFailure.ADDRESS_REJECTED) return ShortLinkOutcome.Blocked
        val landing = failure.takeIf { it.reason == MediaHttpFailure.LANDED }?.landing ?: return ShortLinkOutcome.Unresolved
        // An IP literal, localhost or malformed destination is never handed on, not even through the short link.
        val resolved = LinkInput.parse(landing) ?: return ShortLinkOutcome.Blocked
        val url = HttpsUrl.parse(resolved.rawUrl) ?: return ShortLinkOutcome.Blocked
        if (ShortLinks.isShortLink(url)) return ShortLinkOutcome.Unresolved
        requirePublic(token, url)
        return ShortLinkOutcome.Resolved(resolved)
    }
}
