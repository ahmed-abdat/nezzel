package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.InspectResult
import java.io.Closeable

enum class ImageFailure {
    PREVIEW_LIMIT, UNAVAILABLE, UNSUPPORTED, NO_MEDIA, LINKED_ELSEWHERE, HTTP, BUDGET, INVALID_IMAGE, ANIMATED,
    DIMENSIONS, DECODE, CANCELLED, STALE, PROOF_MISMATCH, SAVE_UNAVAILABLE, NETWORK, STAGING, INSUFFICIENT_SPACE,
    /** The provider showed a login wall or "content isn't available" to a logged-out visitor. */
    PRIVATE,
    /** Facebook stories cannot be read without signing in. */
    FACEBOOK_STORY,
    /** A signed photo expired or was removed; reopening the post may refresh its capability. */
    EXPIRED,
    /** The site keeps this media in its app; its public page offers no file (Telegram's "Media is too big"). */
    APP_ONLY,
    /**
     * Nezzel refused the source itself: the page or redirect did not prove the same public post, or the
     * transport rejected the URL/address/encoding. Not an image-type verdict (that stays [UNSUPPORTED]).
     */
    SOURCE_REFUSED
}
enum class ImagePhase { PAGE, IMAGE, DECODE }
enum class ImageSourceType { DIRECT, LINKEDIN, FACEBOOK, X, INSTAGRAM, TELEGRAM, BLUESKY, TIKTOK, REDDIT }
enum class DiscoveryCompleteness { UNKNOWN, PARTIAL, COMPLETE }
/** previewQuality: the page offered only a reduced copy (og:image, or a smaller rendition than it declares). */
data class ImageDiscovery(val discoveredCount: Int, val completeness: DiscoveryCompleteness,
    val claimedCount: Int? = null, val previewQuality: Boolean = false,
    val unavailableCount: Int = 0, val cappedCount: Int = 0, val hasVideo: Boolean = false,
    val notPreviewedCount: Int = 0, val sound: dev.ahmed.savlino.engine.tiktok.TikTokSound? = null,
    val photoLimit: Int = 20)

interface OwnedImagePreview : Closeable {
    val width: Int
    val height: Int
    val allocationBytes: Long
    val isClosed: Boolean
}
fun interface ImagePreviewDecoder {
    /** Blocking decode; caller runs off main. The predicate covers retirement during decode. */
    fun decode(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int): ImageDecodeResult
    fun decodeThumbnail(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int): ImageDecodeResult =
        decode(bytes, mimeType, isActive, length)
}
sealed interface ImageDecodeResult {
    class Success(val preview: OwnedImagePreview) : ImageDecodeResult {
        override fun toString() = "ImageDecodeResult.Success(redacted)"
    }
    data class Failure(val reason: ImageFailure) : ImageDecodeResult
}
sealed interface ImageSaveAvailability {
    data object Available : ImageSaveAvailability
    data class Unavailable(val reason: ImageFailure) : ImageSaveAvailability
}
sealed interface MediaInspectResult {
    /** A missing bounded preview leaves evidenced photo selections available for explicit Save. */
    class Success(val inspection: MediaInspection, val preview: OwnedImagePreview?,
        val discovery: ImageDiscovery, val saveAvailability: ImageSaveAvailability,
        val previews: Map<MediaRenditionRef, OwnedImagePreview> = emptyMap(),
        val videoSource: LinkRevision? = null,
        private val release: () -> Unit) : MediaInspectResult, Closeable {
        override fun close() = release()
        override fun toString() = "MediaInspectResult.Success(redacted)"
    }
    /**
     * [fallback]: LinkedIn only, a PAGE failure before the post's media type was known — the clean public post URL the
     * read had reached, at the caller's revision, which the caller may hand once to yt-dlp. Never a media URL.
     */
    data class Failure(val reason: ImageFailure, val phase: ImagePhase,
        val statusCode: Int? = null, val fallback: LinkRevision? = null) : MediaInspectResult
    class Legacy(val result: InspectResult, val bridge: MediaLegacyBridge? = null) : MediaInspectResult {
        override fun toString() = "MediaInspectResult.Legacy(redacted)"
    }
    /**
     * The link is a public post whose media is a video: [source] is its canonical link at the caller's revision
     * (Facebook `watch/?v=`, LinkedIn's clean post URL, X and Instagram: the caller's own link unchanged), for the audiovisual
     * engine. No preview, no reference, no media URL.
     */
    class VideoRedirect(val source: LinkRevision, val photosUnavailable: Boolean = false) : MediaInspectResult {
        override fun toString() = "MediaInspectResult.VideoRedirect(redacted)"
    }
}
sealed interface ImageValidationResult {
    class Valid(val image: MediaImageMetadata) : ImageValidationResult {
        override fun toString() = "ImageValidationResult.Valid(redacted)"
    }
    data class Failure(val reason: ImageFailure) : ImageValidationResult
}
sealed interface ImageSaveResult {
    class Success(val image: MediaImageMetadata) : ImageSaveResult {
        override fun toString() = "ImageSaveResult.Success(redacted)"
    }
    data class Failure(val reason: ImageFailure, val statusCode: Int? = null) : ImageSaveResult
}
