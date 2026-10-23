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
    private val hosts = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "buff.ly", "is.gd", "cutt.ly", "rb.gy",
        "fb.me", "pin.it", "lnkd.in")
    private val token = Regex("/[A-Za-z0-9_-]{1,64}/?")
    private val instagramShare = Regex("/share/(?:reel/|p/)?[A-Za-z0-9_-]{1,64}/?")
    private val pinterestRedirect = Regex("/url_shortener/[A-Za-z0-9_-]{1,64}/redirect/?")

    internal fun isShortLink(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && when (url.host) {
        // lnkd.in/p/ is LinkedIn's own post shortlink; its reader resolves it.
        "lnkd.in" -> !url.encodedPath.startsWith("/p/") && token.matches(url.encodedPath)
        "fb.me", "goo.gl" -> url.encodedPath.length in 2..128
        in hosts -> token.matches(url.encodedPath)
        "www.instagram.com", "instagram.com" -> instagramShare.matches(url.encodedPath)
        "api.pinterest.com" -> pinterestRedirect.matches(url.encodedPath)
        else -> false
    }

    fun isShortLink(link: ValidatedLink): Boolean = MediaHttpPolicy.parse(link.rawUrl)?.let(::isShortLink) == true
}

/**
 * Bounded, optional redirect read of a [ShortLinks] link inside the authorized inspection: HTTPS only, at most three
 * hops, 4 s, no cookie, no body. Any failure returns null and the caller keeps today's path. Memory only; never logged.
 */
class ShortLinkResolver internal constructor(private val http: MediaHttpClient,
    private val fetch: (OperationToken, String) -> MediaHttpResult) {
    constructor(http: MediaHttpClient) : this(http, http::fetchShortLink)

    suspend fun resolve(link: ValidatedLink): ValidatedLink? {
        if (!ShortLinks.isShortLink(link)) return null
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val token = http.newShortLinkOperation()
                continuation.invokeOnCancellation { token.close() }
                val resolved = token.use {
                    try {
                        if (!continuation.isActive) return@use null
                        (fetch(token, link.rawUrl) as? MediaHttpResult.Failure)
                            ?.takeIf { it.reason == MediaHttpFailure.LANDED }?.landing?.let(LinkInput::parse)
                            ?.takeUnless { ShortLinks.isShortLink(it) }
                    } catch (_: Exception) { null }
                }
                if (continuation.isActive) continuation.resume(resolved)
            }
        }
    }
}
