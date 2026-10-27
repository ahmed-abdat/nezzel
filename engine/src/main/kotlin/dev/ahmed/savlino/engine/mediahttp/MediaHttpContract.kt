package dev.ahmed.savlino.engine.mediahttp

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PAGE_AND_CAROUSEL: X/Instagram, one page + up to 20 images sharing 2 MiB of image bodies,
 * 24 requests including redirects, and one 15 s fetch deadline. PAGE_AND_IMAGE: one page/image.
 * PAGES_AND_IMAGE: at most two page fetches and one image. PAGES_AND_CAROUSEL: Facebook (post,
 * then an optional single photo page) and Bluesky (handle resolution + post), at most two page/API
 * reads with the same carousel image/request/deadline budgets.
 * TIKTOK_PAGE_AND_CAROUSEL: one page, 40 photos sharing 4 MiB, up to 80 image requests
 * (a refused resize may try the original), 84 requests including redirects, same 15 s deadline.
 * LINKEDIN_PAGE_AND_CAROUSEL: one page + up to 64 images, 68 requests including redirects,
 * sharing the same 2 MiB image budget and 15 s fetch deadline.
 */
enum class MediaHttpOperation { DIRECT_IMAGE, PAGE_AND_IMAGE, PAGES_AND_IMAGE, PAGE_AND_CAROUSEL, PAGES_AND_CAROUSEL,
    LINKEDIN_PAGE_AND_CAROUSEL, TIKTOK_PAGE_AND_CAROUSEL;
    internal val isCarousel: Boolean get() = this == PAGE_AND_CAROUSEL || this == PAGES_AND_CAROUSEL ||
        this == LINKEDIN_PAGE_AND_CAROUSEL || this == TIKTOK_PAGE_AND_CAROUSEL
    internal val maxImages: Int get() = if (this == LINKEDIN_PAGE_AND_CAROUSEL) 64 else if (this == TIKTOK_PAGE_AND_CAROUSEL) 40 else if (isCarousel) 20 else 1
}
enum class MediaHttpPurpose { PAGE, IMAGE }
enum class MediaHttpFailure {
    UNAVAILABLE, INVALID_URL, INVALID_CONTEXT, ADDRESS_REJECTED, HTTP_STATUS,
    REDIRECT_LIMIT, REQUEST_LIMIT, RESOURCE_LIMIT, REDIRECT_LOOP, BODY_LIMIT,
    ENCODING, PARTIAL_BODY, NETWORK, TIMEOUT, CANCELLED, BUSY, CLOSED,
    /** A provider page redirected to its login wall (after any login?next= unwrap). */
    LOGIN_REQUIRED,
    /** Facebook stories require sign-in; neither the story nor its login wall is fetched. */
    FACEBOOK_STORY,
    /**
     * Not an error: a read stopped at an accepted redirect hop that already answers the question (a shortener's
     * destination, a Facebook shortlink's video). The hop is in [MediaHttpResult.Failure.landing], never requested.
     */
    LANDED
}
/**
 * Extra host allowlist enforced on the first URL and on every redirect hop. X_MEDIA: `https://pbs.twimg.com/media/` only.
 * INSTAGRAM_CDN: `instagram.f<pop>.fna.fbcdn.net` / `scontent[-<pop>].cdninstagram.com` image paths only.
 */
enum class MediaHostRule { ANY, FACEBOOK_CDN, X_MEDIA, INSTAGRAM_CDN, TELEGRAM_CDN, BLUESKY_CDN, TIKTOK_CDN }

class PublicMediaRequest(val url: String, val purpose: MediaHttpPurpose,
    val pageContext: MediaHttpTarget? = null, val hostRule: MediaHostRule = MediaHostRule.ANY) {
    override fun toString() = "PublicMediaRequest(redacted)"
}

/** Explicit memory-only access; identity and debug rendering never contain the target URL. */
class MediaHttpTarget internal constructor(private val value: String, internal val operationId: OperationId,
    internal val purpose: MediaHttpPurpose) {
    fun url(): String {
        if (operationId.terminal != null) throw IOException("media_http_target_retired")
        return value
    }
    override fun toString() = "MediaHttpTarget(redacted)"
}

sealed interface MediaHttpResult {
    class Success(val response: MediaHttpResponse) : MediaHttpResult {
        override fun toString() = "MediaHttpResult.Success(redacted)"
    }
    /**
     * [landing]: the LANDED hop, or for LinkedIn page reads the clean public post URL (query/fragment stripped) the read had reached
     * when it failed, so the caller can hand that post to yt-dlp. Never a media URL; excluded from equality/toString.
     */
    data class Failure(val reason: MediaHttpFailure, val statusCode: Int? = null) : MediaHttpResult {
        /** Declared type of a size-limited image; no byte-validation proof. Excluded from debug output. */
        internal var imageMimeType: String? = null
        internal var landing: String? = null
            private set
        internal constructor(reason: MediaHttpFailure, statusCode: Int?, landing: String?) : this(reason, statusCode) {
            this.landing = landing
        }
    }
}

/** One transferred allocation; consumers must use [length], never the spare capacity in [bytes]. */
internal class MediaHttpBody(val bytes: ByteArray, val length: Int) {
    override fun toString() = "MediaHttpBody(redacted)"
}

/** Sole owner of a complete bounded in-memory HTTP representation; not image-validation proof. */
class MediaHttpResponse internal constructor(val mimeType: String?, val measuredBytes: Long,
    val target: MediaHttpTarget, bytes: ByteArray) : Closeable {
    private val body = OwnedMediaBody(bytes, measuredBytes.toInt())
    private val closed = AtomicBoolean()
    internal var onClose: () -> Unit = {}
    /** Transfer the exact measured representation once; retirement closes every other access path. */
    internal fun takeBody(): MediaHttpBody = try { body.take() } finally { close() }
    /** Read the body in place without taking it; null once closed. */
    internal fun <T> peek(block: (ByteArray, Int) -> T): T? = body.peek(block)
    override fun close() {
        if (closed.compareAndSet(false, true)) { body.release(); onClose() }
    }
    override fun toString() = "MediaHttpResponse(redacted)"
}

private class OwnedMediaBody(bytes: ByteArray, private val length: Int) {
    private var data: ByteArray? = bytes
    @Synchronized fun take(): MediaHttpBody {
        val owned = data ?: throw IOException("media_http_body_closed")
        val result = MediaHttpBody(owned, length)
        data = null
        return result
    }
    @Synchronized fun <T> peek(block: (ByteArray, Int) -> T): T? = data?.let { block(it, length) }
    @Synchronized fun release() { data = null }
}
