package dev.ahmed.savlino.engine.mediahttp

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PAGE_AND_CAROUSEL: X/Instagram, one page + up to 20 images sharing 2 MiB of image bodies,
 * 24 requests including redirects, and one 15 s fetch deadline. PAGE_AND_IMAGE: one page/image.
 * PAGES_AND_IMAGE: Facebook — at most two page
 * fetches (post, then its photo page) and one image. PAGES_AND_CAROUSEL: Bluesky, at most two API
 * reads (handle resolution + post), with the same carousel image/request/deadline budgets.
 */
enum class MediaHttpOperation { DIRECT_IMAGE, PAGE_AND_IMAGE, PAGES_AND_IMAGE, PAGE_AND_CAROUSEL, PAGES_AND_CAROUSEL;
    internal val isCarousel: Boolean get() = this == PAGE_AND_CAROUSEL || this == PAGES_AND_CAROUSEL
}
enum class MediaHttpPurpose { PAGE, IMAGE }
enum class MediaHttpFailure {
    UNAVAILABLE, INVALID_URL, INVALID_CONTEXT, ADDRESS_REJECTED, HTTP_STATUS,
    REDIRECT_LIMIT, REQUEST_LIMIT, RESOURCE_LIMIT, REDIRECT_LOOP, BODY_LIMIT,
    ENCODING, PARTIAL_BODY, NETWORK, TIMEOUT, CANCELLED, BUSY, CLOSED,
    /** A provider page redirected to its login wall (after any login?next= unwrap). */
    LOGIN_REQUIRED
}
/**
 * Extra host allowlist enforced on the first URL and on every redirect hop. X_MEDIA: `https://pbs.twimg.com/media/` only.
 * INSTAGRAM_CDN: `instagram.f<pop>.fna.fbcdn.net` / `scontent[-<pop>].cdninstagram.com` image paths only.
 */
enum class MediaHostRule { ANY, FACEBOOK_CDN, X_MEDIA, INSTAGRAM_CDN, TELEGRAM_CDN, BLUESKY_CDN }

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
     * [landing]: LinkedIn page reads only — the clean public post URL (query/fragment stripped) the read had reached
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
    @Synchronized fun release() { data = null }
}
