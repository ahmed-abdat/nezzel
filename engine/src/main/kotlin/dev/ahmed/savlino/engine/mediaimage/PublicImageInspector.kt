package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.facebook.FacebookImageCandidate
import dev.ahmed.savlino.engine.facebook.FacebookLinkKind
import dev.ahmed.savlino.engine.facebook.FacebookPageParser
import dev.ahmed.savlino.engine.facebook.FacebookParseResult
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramParseResult
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramPostParser
import dev.ahmed.savlino.engine.linkedin.LinkedInParseResult
import dev.ahmed.savlino.engine.linkedin.LinkedInPostAttachmentParser
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.telegram.TelegramPostIdentity
import dev.ahmed.savlino.engine.telegram.TelegramPostParser
import dev.ahmed.savlino.engine.telegram.TelegramParseResult
import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.bluesky.BlueskyPostParser
import dev.ahmed.savlino.engine.bluesky.BlueskyParseResult
import dev.ahmed.savlino.engine.x.XParseResult
import dev.ahmed.savlino.engine.x.XPostIdentity
import dev.ahmed.savlino.engine.x.XPostParser
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ImageInspectionOperation internal constructor(val source: LinkRevision,
    val identity: MediaAdapterIdentity, internal val token: OperationToken) {
    internal enum class State { ACTIVE, CANCELLED, REPLACED }
    @Volatile internal var state = State.ACTIVE
    internal val started = AtomicBoolean()
    internal var fetchPhase = ImagePhase.IMAGE
    override fun toString() = "ImageInspectionOperation(opaque)"
}

/**
 * One current inspection per inspector. One U5a token spans page, asset, validation and decode;
 * a newer operation retires the previous one (late results are discarded and recycled), and only
 * one pixel decode runs in the process at a time. Blocking work runs on the IO dispatcher.
 */
class PublicImageInspector(private val http: MediaHttpClient, private val decoder: ImagePreviewDecoder,
    private val references: MediaReferenceStore) : Closeable {
    private val lock = Any()
    private var current: ImageInspectionOperation? = null
    private val validator = ImageRepresentationValidator()
    private val parser = LinkedInPostAttachmentParser()
    private val facebookParser = FacebookPageParser()
    private val xParser = XPostParser()
    private val instagramParser = InstagramPostParser()

    private companion object {
        /** Process-wide: a retired decode still owns its raster until it returns and is recycled. */
        val decodeLock = Mutex()
    }

    fun newOperation(source: LinkRevision, identity: MediaAdapterIdentity): ImageInspectionOperation {
        val kind = when {
            LinkedInPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.LINKEDIN_PAGE_AND_CAROUSEL
            FacebookPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.PAGES_AND_CAROUSEL
            XPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.PAGE_AND_CAROUSEL
            InstagramPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.PAGE_AND_CAROUSEL
            TelegramPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.PAGE_AND_CAROUSEL
            BlueskyPostIdentity.parse(source.link.rawUrl) != null -> MediaHttpOperation.PAGES_AND_CAROUSEL
            else -> MediaHttpOperation.DIRECT_IMAGE
        }
        val previous = synchronized(lock) {
            val old = current
            if (old != null && old.state == ImageInspectionOperation.State.ACTIVE)
                old.state = ImageInspectionOperation.State.REPLACED
            current = null
            references.retire()
            old
        }
        // Free the retired token's transport slot before registering the replacement.
        previous?.let { http.cancel(it.token.id) }
        val operation = ImageInspectionOperation(source, identity,
            http.newOperation(kind))
        val superseded = synchronized(lock) {
            val raced = current
            if (raced != null && raced.state == ImageInspectionOperation.State.ACTIVE)
                raced.state = ImageInspectionOperation.State.REPLACED
            current = operation
            raced
        }
        superseded?.let { http.cancel(it.token.id) }
        return operation
    }

    suspend fun inspect(operation: ImageInspectionOperation, type: ImageSourceType = ImageSourceType.DIRECT): MediaInspectResult {
        if (!operation.started.compareAndSet(false, true)) return failure(ImageFailure.UNAVAILABLE, ImagePhase.IMAGE)
        try {
            terminal(operation)?.let { return failure(it, ImagePhase.IMAGE) }
            val wantsPage = type != ImageSourceType.DIRECT
            if (wantsPage != (operation.token.operation != MediaHttpOperation.DIRECT_IMAGE))
                return failure(ImageFailure.SOURCE_REFUSED, if (wantsPage) ImagePhase.PAGE else ImagePhase.IMAGE)
            operation.fetchPhase = if (wantsPage) ImagePhase.PAGE else ImagePhase.IMAGE
            val fetched = withContext(Dispatchers.IO) {
                try { when (type) {
                    ImageSourceType.LINKEDIN -> fetchLinkedIn(operation)
                    ImageSourceType.FACEBOOK -> fetchFacebook(operation)
                    ImageSourceType.X -> fetchX(operation)
                    ImageSourceType.INSTAGRAM -> fetchInstagram(operation)
                    ImageSourceType.TELEGRAM -> fetchTelegram(operation)
                    ImageSourceType.BLUESKY -> fetchBluesky(operation)
                    ImageSourceType.DIRECT -> fetchDirect(operation)
                } } catch (_: OutOfMemoryError) {
                    Fetched.Failed(failure(ImageFailure.BUDGET, operation.fetchPhase))
                }
            }
            val asset = when (fetched) {
                is Fetched.Failed -> return fetched.result
                is Fetched.Video -> return fetched.result
                is Fetched.Asset -> fetched
                is Fetched.Carousel -> fetched
            }
            var result: MediaInspectResult? = null
            try {
                result = decodeLock.withLock { withContext(Dispatchers.IO) { decodeAndAdmit(operation, asset) } }
                return result
            } finally {
                // A context whose inspection was never admitted must not keep occupying a transport slot.
                if (result !is MediaInspectResult.Success) {
                    val contexts = when (asset) {
                        is Fetched.Asset -> listOfNotNull(asset.postContext)
                        is Fetched.Carousel -> asset.assets.mapNotNull { it.postContext }.distinct()
                        else -> emptyList()
                    }
                    contexts.forEach(http::forgetLinkedInPost)
                }
            }
        } finally {
            operation.token.close()
        }
    }

    fun cancel(operation: ImageInspectionOperation) {
        synchronized(lock) {
            if (operation.state == ImageInspectionOperation.State.ACTIVE)
                operation.state = ImageInspectionOperation.State.CANCELLED
        }
        http.cancel(operation.token.id)
    }

    override fun close() {
        val previous = synchronized(lock) {
            current?.also { if (it.state == ImageInspectionOperation.State.ACTIVE) it.state = ImageInspectionOperation.State.REPLACED }
                .also { current = null }
        }
        previous?.let { http.cancel(it.token.id) }
        references.retire()
    }

    private sealed interface Fetched {
        class Failed(val result: MediaInspectResult.Failure, val declaredFormat: MediaImageFormat? = null) : Fetched
        class Video(val result: MediaInspectResult.VideoRedirect) : Fetched
        class Carousel(val assets: List<Asset>, val discovery: ImageDiscovery) : Fetched
        class Asset(val bytes: MediaHttpBody?, val image: MediaImageMetadata, val url: String, val candidates: Int,
            val discovery: ImageDiscovery, val validity: MediaInspectionValidity,
            val postContext: MediaHttpPostContext?, val linkedIn: Boolean,
            val hostRule: MediaHostRule = MediaHostRule.ANY, val metadata: MediaMetadata = MediaMetadata(),
            val position: Int = 0, val previewOnly: Boolean = false, val videoSource: LinkRevision? = null) : Fetched {
            override fun toString() = "Fetched.Asset(redacted)"
        }
    }

    private fun fetchDirect(operation: ImageInspectionOperation): Fetched {
        val url = operation.source.link.rawUrl
        val validity = MediaInspectionValidity.processLocal()
        return fetchAsset(operation, PublicMediaRequest(url, MediaHttpPurpose.IMAGE)) { bytes, image ->
            Fetched.Asset(bytes, image, url, 1, ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), validity, null, false)
        }
    }

    private fun fetchLinkedIn(operation: ImageInspectionOperation): Fetched {
        val page = when (val result = http.fetchLinkedInPage(operation.token, operation.source.link.rawUrl)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(
                withFallback(operation, transportFailure(operation, result, ImagePhase.PAGE), result.landing))
            is MediaHttpResult.Success -> result.response
        }
        val target = page.target
        val landing = MediaHttpPolicy.parse(target.url())?.let(MediaHttpPolicy::withoutQuery)
        val (html, resolved) = try {
            val bytes = page.takeBody()
            bytes to (landing ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)))
        } catch (_: java.io.IOException) {
            return Fetched.Failed(withFallback(operation,
                failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE), landing))
        } finally { page.close() }
        val parsed = when (val result = parser.parse(html.bytes, resolved, html.length)) {
            // Unrecognised layout, budget or conflicting markup: the post's media type is still unknown.
            is LinkedInParseResult.Failure -> return Fetched.Failed(withFallback(operation,
                failure(result.reason, ImagePhase.PAGE), resolved))
            is LinkedInParseResult.Video -> {
                operation.fetchPhase = ImagePhase.IMAGE
                return redirectTo(operation, LinkInput.parse(result.postUrl))
            }
            is LinkedInParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        if (parsed.candidates.size > 1) return fetchCarousel(operation, parsed.candidates.map { it.url },
            parsed.discovery, MediaHostRule.ANY, MediaMetadata(parsed.title, parsed.author),
            previewUrls = emptyList(), pageContext = target)
        val first = parsed.candidates.first().url
        val validity = MediaInspectionValidity.processLocal()
        return fetchAsset(operation, PublicMediaRequest(first, MediaHttpPurpose.IMAGE, target)) { bytes, image ->
            references.evictForNewContext().forEach(http::forgetLinkedInPost)
            val context = http.rememberLinkedInPost(operation.token, target,
                MediaHttpSaveBinding(operation.source, operation.identity, validity))
            Fetched.Asset(bytes, image, first, parsed.candidates.size, parsed.discovery, validity, context, true,
                metadata = MediaMetadata(parsed.title, parsed.author))
        }
    }

    private fun fetchFacebook(operation: ImageInspectionOperation): Fetched {
        val requested = FacebookPostIdentity.parse(operation.source.link.rawUrl)
            ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        val page = when (val result = http.fetchFacebookPage(operation.token, requested.fetchUrl)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(transportFailure(operation, result, ImagePhase.PAGE))
            is MediaHttpResult.Success -> result.response
        }
        val (html, resolved) = try {
            val bytes = page.takeBody()
            val identity = FacebookPostIdentity.parse(page.target.url())
                ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
            bytes to identity
        } catch (_: java.io.IOException) {
            return Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE))
        } finally { page.close() }
        // Only a shortlink (share/, share/{p,v,r}, fb.watch) carries no id of its own, so only its landing reel/watch/video
        // URL may name the video. An id-bearing link always needs its own bound story's attachment below.
        if (requested.isShortlink) resolved.videoId?.let { return videoRedirect(operation, it) }
        val parsed = when (val result = facebookParser.parse(html.bytes, resolved, requested, html.length)) {
            is FacebookParseResult.Failure -> return Fetched.Failed(failure(result.reason, ImagePhase.PAGE))
            is FacebookParseResult.Video -> return videoRedirect(operation, result.videoId)
            is FacebookParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        val videoSource = parsed.videoId?.let(::facebookWatchLink)?.let { LinkRevision(it, operation.source.revision) }
        if (parsed.candidates.size > 1 || parsed.discovery.cappedCount > 0 || parsed.discovery.unavailableCount > 0)
            return fetchCarousel(operation, parsed.candidates.map { it.url }, parsed.discovery,
                MediaHostRule.FACEBOOK_CDN, MediaMetadata(parsed.title, parsed.author),
                parsed.candidates.map { it.previewUrl }, videoSource = videoSource)
        var first = parsed.candidates.first()
        var discovery = parsed.discovery
        if (discovery.previewQuality && first.photoId != null && resolved.kind != FacebookLinkKind.PHOTO) {
            (try { largerFromPhotoPage(operation, first) } catch (_: OutOfMemoryError) { null })?.let { (larger, preview) ->
                first = larger
                discovery = discovery.copy(previewQuality = preview)
            }
            terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        }
        val url = first.url
        val validity = MediaInspectionValidity.processLocal()
        return fetchAsset(operation, PublicMediaRequest(url, MediaHttpPurpose.IMAGE, hostRule = MediaHostRule.FACEBOOK_CDN)) { bytes, image ->
            Fetched.Asset(bytes, image, url, parsed.candidates.size, discovery, validity, null, false,
                MediaHostRule.FACEBOOK_CDN, MediaMetadata(parsed.title, parsed.author),
                videoSource = videoSource)
        }
    }

    /**
     * X: one syndication JSON read, then bounded real photos on pbs.twimg.com/media. Mixed posts
     * expose a video switch; video-only posts hand back to yt-dlp unchanged.
     */
    private fun fetchX(operation: ImageInspectionOperation): Fetched {
        val identity = XPostIdentity.parse(operation.source.link.rawUrl)
            ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        val page = when (val result = http.fetchXPost(operation.token, identity)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(transportFailure(operation, result, ImagePhase.PAGE))
            is MediaHttpResult.Success -> result.response
        }
        val body = try { page.takeBody() }
            catch (_: java.io.IOException) { return Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE)) }
            finally { page.close() }
        val parsed = when (val result = xParser.parse(body.bytes, identity.postId, body.length)) {
            is XParseResult.Failure -> return Fetched.Failed(failure(result.reason, result.phase))
            XParseResult.Video -> return redirectTo(operation, operation.source.link)
            XParseResult.PhotosUnavailableVideo -> return redirectTo(operation, operation.source.link, photosUnavailable = true)
            is XParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        val carousel = parsed.candidates.size > 1 || parsed.discovery.unavailableCount > 0 || parsed.discovery.cappedCount > 0
        val originals = if (carousel) parsed.candidates.map {
            MediaHttpPolicy.parse(it)!!.newBuilder().setQueryParameter("name", "orig").build().toString()
        } else parsed.candidates
        return fetchCarousel(operation, originals,
            if (carousel) parsed.discovery.copy(previewQuality = false) else parsed.discovery, MediaHostRule.X_MEDIA,
            MediaMetadata(parsed.title, parsed.author), parsed.candidates.map {
                MediaHttpPolicy.parse(it)!!.newBuilder().setQueryParameter("name", "small").build().toString()
            })
    }

    /**
     * Instagram: one logged-out post page, then bounded real photos on its image CDN.
     * Mixed posts expose a video switch; video-only posts hand back to yt-dlp.
     */
    private fun fetchInstagram(operation: ImageInspectionOperation): Fetched {
        val identity = InstagramPostIdentity.parse(operation.source.link.rawUrl)
            ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        val page = when (val result = http.fetchInstagramPost(operation.token, identity)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(transportFailure(operation, result, ImagePhase.PAGE))
            is MediaHttpResult.Success -> result.response
        }
        val body = try { page.takeBody() }
            catch (_: java.io.IOException) { return Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE)) }
            finally { page.close() }
        val parsed = when (val result = instagramParser.parse(body.bytes, identity.code, body.length)) {
            is InstagramParseResult.Failure -> return Fetched.Failed(failure(result.reason, result.phase))
            InstagramParseResult.Video -> return redirectTo(operation, operation.source.link)
            InstagramParseResult.PhotosUnavailableVideo -> return redirectTo(operation, operation.source.link, photosUnavailable = true)
            is InstagramParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        return fetchCarousel(operation, parsed.candidates, parsed.discovery, MediaHostRule.INSTAGRAM_CDN,
            MediaMetadata(parsed.title, parsed.author), parsed.previewCandidates)
    }

    private fun fetchTelegram(operation: ImageInspectionOperation): Fetched {
        val identity = TelegramPostIdentity.parse(operation.source.link.rawUrl)
            ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        val page = when (val result = http.fetchTelegramPost(operation.token, identity)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(
                if (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(401, 403, 404, 410))
                    failure(ImageFailure.PRIVATE, ImagePhase.PAGE)
                else transportFailure(operation, result, ImagePhase.PAGE))
            is MediaHttpResult.Success -> result.response
        }
        val body = try { page.takeBody() }
            catch (_: java.io.IOException) { return Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE)) }
            finally { page.close() }
        val parsed = when (val result = TelegramPostParser().parse(body.bytes, identity, body.length)) {
            is TelegramParseResult.Failure -> return Fetched.Failed(failure(result.reason, result.phase))
            TelegramParseResult.Video -> return redirectTo(operation, operation.source.link)
            is TelegramParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        return fetchCarousel(operation, parsed.candidates, parsed.discovery, MediaHostRule.TELEGRAM_CDN,
            MediaMetadata(title = parsed.title), parsed.candidates)
    }

    private fun fetchBluesky(operation: ImageInspectionOperation): Fetched {
        val identity = BlueskyPostIdentity.parse(operation.source.link.rawUrl)
            ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        val parser = BlueskyPostParser()
        // Two exact API reads at most, on the same deadline and request budget. No arbitrary PDS requests.
        fun read(url: String): Pair<MediaHttpBody?, Fetched.Failed?> {
            val page = when (val result = http.fetchBlueskyApi(operation.token, url)) {
                is MediaHttpResult.Failure -> return null to Fetched.Failed(
                    if (result.reason == MediaHttpFailure.HTTP_STATUS && result.statusCode in setOf(400, 404, 410))
                        failure(ImageFailure.PRIVATE, ImagePhase.PAGE)
                    else transportFailure(operation, result, ImagePhase.PAGE))
                is MediaHttpResult.Success -> result.response
            }
            return try { page.takeBody() to null }
                catch (_: java.io.IOException) { null to Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.PAGE)) }
                finally { page.close() }
        }
        val did = identity.did ?: run {
            val (body, failed) = read(identity.resolveUrl)
            if (failed != null) return failed
            parser.resolveDid(body!!.bytes, body.length) ?: return Fetched.Failed(failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE))
        }
        val (body, failed) = read(identity.threadUrl(did))
        if (failed != null) return failed
        val parsed = when (val result = parser.parse(body!!.bytes, identity.atUri(did), body.length)) {
            is BlueskyParseResult.Failure -> return Fetched.Failed(failure(result.reason, result.phase))
            BlueskyParseResult.Video -> return redirectTo(operation, operation.source.link)
            is BlueskyParseResult.Success -> result
        }
        operation.fetchPhase = ImagePhase.IMAGE
        return fetchCarousel(operation, parsed.candidates, parsed.discovery, MediaHostRule.BLUESKY_CDN,
            MediaMetadata(parsed.title, parsed.author), parsed.previewCandidates)
    }

    /** Small CDN renditions share 2 MiB; LinkedIn validates its evidenced images with the full per-image limit. */
    private fun fetchCarousel(operation: ImageInspectionOperation, urls: List<String>, discovery: ImageDiscovery,
        rule: MediaHostRule, metadata: MediaMetadata, previewUrls: List<String>, pageContext: MediaHttpTarget? = null,
        videoSource: LinkRevision? = null): Fetched {
        val validity = MediaInspectionValidity.processLocal()
        val assets = ArrayList<Fetched.Asset>()
        val linkedIn = pageContext != null
        val optionalPreviews = rule == MediaHostRule.TELEGRAM_CDN || rule == MediaHostRule.BLUESKY_CDN
        val thumbnails = !linkedIn && (optionalPreviews || urls.size > 1 || discovery.cappedCount > 0 || discovery.unavailableCount > 0)
        val candidates = urls.take(operation.token.operation.maxImages)
        var postContext: MediaHttpPostContext? = null
        var contextAttempted = false
        var completed = false
        var last: Fetched.Failed? = null
        var notPreviewed = 0
        var unavailable = discovery.unavailableCount
        try {
            for ((position, url) in candidates.withIndex()) {
                val retired = terminal(operation)
                if (retired != null) {
                    if (retired == ImageFailure.NETWORK && operation.token.checkActive() == MediaHttpFailure.TIMEOUT && assets.isNotEmpty()) {
                        notPreviewed += candidates.size - position
                        break
                    }
                    return Fetched.Failed(failure(retired, ImagePhase.IMAGE))
                }
                if (operation.token.budget.imageLimit() <= 0) {
                    notPreviewed += candidates.size - position
                    break
                }
                val previewUrl = if (thumbnails) previewUrls[position] else url
                val fetched = fetchAsset(operation, PublicMediaRequest(previewUrl, MediaHttpPurpose.IMAGE, pageContext, rule),
                    thumbnail = thumbnails) { bytes, image ->
                    if (pageContext != null && !contextAttempted) {
                        contextAttempted = true
                        references.evictForNewContext().forEach(http::forgetLinkedInPost)
                        postContext = http.rememberLinkedInPost(operation.token, pageContext,
                            MediaHttpSaveBinding(operation.source, operation.identity, validity))
                    }
                    Fetched.Asset(bytes, image, url, 1, discovery, validity, postContext, linkedIn, rule, metadata, position,
                        previewOnly = previewUrl != url, videoSource = videoSource)
                }
                when (fetched) {
                    is Fetched.Asset -> assets += fetched
                    is Fetched.Failed -> {
                        last = fetched
                        if (fetched.result.reason == ImageFailure.BUDGET) {
                            notPreviewed++
                            // The page proves the attachment; only its preview exceeded the byte cap.
                            // Keep a Save capability with declared type and no dimensions/size/proof.
                            if (optionalPreviews && fetched.declaredFormat != null) assets += Fetched.Asset(null,
                                originalImageMetadata(url, MediaImageMetadata(fetched.declaredFormat)), url, 1,
                                discovery, validity, null, false, rule, metadata, position, previewOnly = true, videoSource = videoSource)
                        } else unavailable++
                    }
                    else -> Unit
                }
            }
            if (assets.isEmpty()) return if (discovery.hasVideo && operation.state == ImageInspectionOperation.State.ACTIVE)
                Fetched.Video(MediaInspectResult.VideoRedirect(videoSource ?: operation.source, photosUnavailable = true))
                else if (thumbnails && notPreviewed > 0) Fetched.Failed(failure(ImageFailure.PREVIEW_LIMIT, ImagePhase.IMAGE))
                else last ?: Fetched.Failed(failure(ImageFailure.BUDGET, ImagePhase.IMAGE))
            val capped = discovery.cappedCount + (urls.size - candidates.size)
            val resultDiscovery = discovery.copy(discoveredCount = assets.size, unavailableCount = unavailable,
                cappedCount = capped,
                notPreviewedCount = notPreviewed,
                completeness = if (unavailable + notPreviewed + capped > 0) DiscoveryCompleteness.PARTIAL else discovery.completeness)
            val result = if (!optionalPreviews && assets.size == 1 && unavailable == 0 && notPreviewed == 0 && discovery.cappedCount == 0)
                assets.single() else Fetched.Carousel(assets, resultDiscovery)
            completed = true
            return result
        } finally {
            if (!completed) postContext?.let(http::forgetLinkedInPost)
        }
    }

    /** Canonical `watch/?v={id}` at the caller's revision; no media URL, preview or reference is produced. */
    private fun videoRedirect(operation: ImageInspectionOperation, videoId: String): Fetched {
        operation.fetchPhase = ImagePhase.IMAGE
        return redirectTo(operation, facebookWatchLink(videoId))
    }

    private fun facebookWatchLink(videoId: String): ValidatedLink? =
        videoId.takeIf { it.length <= 25 && it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.let { LinkInput.parse("https://www.facebook.com/watch/?v=$it") }

    /** A canonical post/watch link for yt-dlp at the caller's revision; no media URL, preview or reference. */
    private fun redirectTo(operation: ImageInspectionOperation, link: ValidatedLink?, photosUnavailable: Boolean = false): Fetched {
        operation.fetchPhase = ImagePhase.IMAGE
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.PAGE)) }
        link ?: return Fetched.Failed(failure(ImageFailure.UNSUPPORTED, ImagePhase.PAGE))
        return Fetched.Video(MediaInspectResult.VideoRedirect(LinkRevision(link, operation.source.revision), photosUnavailable))
    }

    /**
     * The attachment's own /photo/?fbid= page (second and last Facebook page of this operation): its
     * currMedia matched by id, taken only when it declares a larger copy. Any failure keeps the smaller one.
     */
    private fun largerFromPhotoPage(operation: ImageInspectionOperation,
        smaller: FacebookImageCandidate): Pair<FacebookImageCandidate, Boolean>? {
        val identity = FacebookPostIdentity.parse("https://www.facebook.com/photo/?fbid=${smaller.photoId}") ?: return null
        val page = (http.fetchFacebookPhotoPage(operation.token, identity.fetchUrl) as? MediaHttpResult.Success)?.response
            ?: return null
        val (html, resolved) = try {
            page.takeBody() to (FacebookPostIdentity.parse(page.target.url()) ?: return null)
        } catch (_: java.io.IOException) { return null } finally { page.close() }
        val parsed = facebookParser.parse(html.bytes, resolved, identity, html.length) as? FacebookParseResult.Success ?: return null
        val larger = parsed.candidates.singleOrNull()?.takeIf { it.photoId == smaller.photoId } ?: return null
        val bigger = larger.declaredWidth * larger.declaredHeight > smaller.declaredWidth * smaller.declaredHeight
        return if (bigger) larger to parsed.discovery.previewQuality else null
    }

    private fun fetchAsset(operation: ImageInspectionOperation, request: PublicMediaRequest, thumbnail: Boolean = false,
        complete: (MediaHttpBody, MediaImageMetadata) -> Fetched.Asset): Fetched {
        val response = when (val result = if (operation.token.operation.isCarousel)
            http.fetchCarouselImage(operation.token, request, thumbnail) else http.fetch(operation.token, request)) {
            is MediaHttpResult.Failure -> return Fetched.Failed(transportFailure(operation, result, ImagePhase.IMAGE),
                imageFormatFor(result.imageMimeType))
            is MediaHttpResult.Success -> result.response
        }
        val bytes = try { response.takeBody() }
            catch (_: java.io.IOException) { return Fetched.Failed(failure(terminal(operation) ?: ImageFailure.NETWORK, ImagePhase.IMAGE)) }
            catch (_: OutOfMemoryError) { return Fetched.Failed(failure(ImageFailure.BUDGET, ImagePhase.IMAGE)) }
            finally { response.close() }
        val validationResult = try { validator.validate(bytes.bytes, response.mimeType.orEmpty(), bytes.length) }
            catch (_: OutOfMemoryError) { return Fetched.Failed(failure(ImageFailure.BUDGET, ImagePhase.IMAGE)) }
        val image = when (val validation = validationResult) {
            is ImageValidationResult.Failure -> return Fetched.Failed(failure(validation.reason, ImagePhase.IMAGE))
            is ImageValidationResult.Valid -> validation.image
        }
        terminal(operation)?.let { return Fetched.Failed(failure(it, ImagePhase.IMAGE)) }
        return complete(bytes, image)
    }

    private suspend fun decodeAndAdmit(operation: ImageInspectionOperation, fetched: Fetched): MediaInspectResult {
        val assets = when (fetched) {
            is Fetched.Asset -> listOf(fetched)
            is Fetched.Carousel -> fetched.assets
            else -> return failure(ImageFailure.NO_MEDIA, ImagePhase.IMAGE)
        }
        val carousel = fetched is Fetched.Carousel
        val previews = LinkedHashMap<MediaRenditionRef, OwnedImagePreview>()
        val stored = ArrayList<StoredImageReference>()
        val items = ArrayList<MediaItem>()
        var admitted = false
        var lastFailure = ImageFailure.DECODE
        // Already complete/validated bounded bodies survive a later sibling's fetch deadline.
        // Only local bounded decoding remains; cancellation and replacement still reject every late result.
        fun decodeTerminal(): ImageFailure? = terminal(operation).takeUnless {
            carousel && operation.state == ImageInspectionOperation.State.ACTIVE && operation.token.checkActive() == MediaHttpFailure.TIMEOUT
        }
        val active = { decodeTerminal() == null }
        try {
            for (asset in assets) {
                decodeTerminal()?.let { return failure(it, ImagePhase.DECODE) }
                val bytes = asset.bytes
                val decoded = if (bytes == null) null else try {
                    if (carousel) decoder.decodeThumbnail(bytes.bytes, asset.image.format.mimeType, active, bytes.length)
                    else decoder.decode(bytes.bytes, asset.image.format.mimeType, active, bytes.length)
                } catch (_: Exception) { ImageDecodeResult.Failure(ImageFailure.DECODE) }
                catch (_: OutOfMemoryError) { ImageDecodeResult.Failure(ImageFailure.DECODE) }
                if (decoded is ImageDecodeResult.Failure) { lastFailure = decoded.reason; continue }
                val preview = (decoded as? ImageDecodeResult.Success)?.preview
                // Also bound injected decoders, independently of compressed bytes. Native thumbnails use <=256².
                val axis = if (carousel) 256 else 1024
                if (preview != null && (preview.width !in 1..axis || preview.height !in 1..axis || preview.allocationBytes > axis * axis * 4L)) {
                    preview.close(); continue
                }
                val item = MediaItemRef(operation.identity.adapterId, opaqueId())
                val rendition = MediaRenditionRef(item, opaqueId())
                if (preview != null) previews[rendition] = preview
                // A small rendition proves its preview only; original dimensions/size/proof wait for Save.
                val original = if (asset.previewOnly) originalImageMetadata(asset.url, asset.image) else asset.image
                items += MediaItem(item, asset.position, MediaKind.IMAGE, asset.metadata,
                    listOf(MediaRendition.Image(rendition, original)))
                stored += StoredImageReference(operation.source, operation.identity, asset.validity, rendition,
                    asset.url, original, asset.linkedIn, asset.postContext, asset.hostRule)
                if (!carousel) for (position in 1 until asset.candidates) {
                    items += MediaItem(MediaItemRef(operation.identity.adapterId, opaqueId()), position,
                        MediaKind.IMAGE, asset.metadata, emptyList())
                }
            }
            decodeTerminal()?.let { return failure(it, ImagePhase.DECODE) }
            if (items.isEmpty()) return if (assets.first().discovery.hasVideo)
                MediaInspectResult.VideoRedirect(assets.first().videoSource ?: operation.source, photosUnavailable = true) else failure(lastFailure, ImagePhase.DECODE)
            val first = assets.first()
            val inspection = MediaInspection(operation.source, operation.identity, items, first.validity)
            synchronized(lock) {
                decodeTerminal()?.let { return failure(it, ImagePhase.DECODE) }
                if (current !== operation) return failure(ImageFailure.STALE, ImagePhase.DECODE)
                references.admit(stored, previews.values.firstOrNull(), previews.values.toList())?.forEach(http::forgetLinkedInPost)
                    ?: return failure(ImageFailure.STALE, ImagePhase.DECODE)
                admitted = true
            }
            val save = if (first.linkedIn && first.postContext == null)
                ImageSaveAvailability.Unavailable(ImageFailure.SAVE_UNAVAILABLE) else ImageSaveAvailability.Available
            val discovery = if (fetched is Fetched.Carousel) {
                val dropped = assets.size - items.size
                fetched.discovery.copy(discoveredCount = items.size,
                    unavailableCount = fetched.discovery.unavailableCount + dropped,
                    completeness = if (dropped > 0) DiscoveryCompleteness.PARTIAL else fetched.discovery.completeness)
            } else first.discovery
            return MediaInspectResult.Success(inspection, previews.values.firstOrNull(), discovery, save, previews.toMap(), first.videoSource) {
                previews.values.forEach { runCatching { it.close() } }
            }
        } finally {
            if (!admitted) previews.values.forEach { runCatching { it.close() } }
        }
    }

    /** Retirement wins over the transport's own terminal reason. */
    private fun terminal(operation: ImageInspectionOperation): ImageFailure? = when (operation.state) {
        ImageInspectionOperation.State.REPLACED -> ImageFailure.STALE
        ImageInspectionOperation.State.CANCELLED -> ImageFailure.CANCELLED
        ImageInspectionOperation.State.ACTIVE -> operation.token.checkActive()?.let(::mapTransport)
    }

    private fun transportFailure(operation: ImageInspectionOperation, result: MediaHttpResult.Failure,
        phase: ImagePhase): MediaInspectResult.Failure {
        val retired = when (operation.state) {
            ImageInspectionOperation.State.REPLACED -> ImageFailure.STALE
            ImageInspectionOperation.State.CANCELLED -> ImageFailure.CANCELLED
            ImageInspectionOperation.State.ACTIVE -> null
        }
        if (retired != null) return failure(retired, phase)
        val reason = mapTransport(result.reason)
        return MediaInspectResult.Failure(reason, phase, if (reason == ImageFailure.HTTP) result.statusCode else null)
    }

    private fun failure(reason: ImageFailure, phase: ImagePhase) = MediaInspectResult.Failure(reason, phase)

    /**
     * A LinkedIn PAGE failure before any media verdict carries the clean post URL the read reached ([landing]) at the
     * caller's revision, for one yt-dlp attempt. Retirement, a shortlink-only read and every IMAGE failure carry none.
     */
    private fun withFallback(operation: ImageInspectionOperation, result: MediaInspectResult.Failure,
        landing: String?): MediaInspectResult.Failure {
        if (result.phase != ImagePhase.PAGE || result.reason == ImageFailure.STALE || result.reason == ImageFailure.CANCELLED ||
            terminal(operation) in setOf(ImageFailure.STALE, ImageFailure.CANCELLED)) return result
        val clean = landing?.let(LinkedInPostIdentity::parse)?.cleanUrl?.let(LinkInput::parse) ?: return result
        return result.copy(fallback = LinkRevision(clean, operation.source.revision))
    }
    private fun opaqueId() = UUID.randomUUID().toString()
}

internal fun mapTransport(reason: MediaHttpFailure): ImageFailure = when (reason) {
    MediaHttpFailure.CANCELLED -> ImageFailure.CANCELLED
    MediaHttpFailure.HTTP_STATUS -> ImageFailure.HTTP
    MediaHttpFailure.BODY_LIMIT, MediaHttpFailure.REDIRECT_LIMIT, MediaHttpFailure.REQUEST_LIMIT,
    MediaHttpFailure.RESOURCE_LIMIT -> ImageFailure.BUDGET
    MediaHttpFailure.INVALID_URL, MediaHttpFailure.INVALID_CONTEXT, MediaHttpFailure.ADDRESS_REJECTED,
    MediaHttpFailure.REDIRECT_LOOP, MediaHttpFailure.ENCODING -> ImageFailure.SOURCE_REFUSED
    MediaHttpFailure.PARTIAL_BODY, MediaHttpFailure.NETWORK, MediaHttpFailure.TIMEOUT -> ImageFailure.NETWORK
    MediaHttpFailure.UNAVAILABLE, MediaHttpFailure.BUSY, MediaHttpFailure.CLOSED -> ImageFailure.UNAVAILABLE
    MediaHttpFailure.LOGIN_REQUIRED -> ImageFailure.PRIVATE
    MediaHttpFailure.FACEBOOK_STORY -> ImageFailure.FACEBOOK_STORY
}

/** Original candidates have already passed the CDN policy; their format may differ from the thumbnail. */
internal fun originalImageMetadata(url: String, preview: MediaImageMetadata): MediaImageMetadata {
    val original = MediaHttpPolicy.parse(url)
    val encoding = if (original?.host == "pbs.twimg.com") original.queryParameter("format")
        else original?.encodedPath?.substringAfterLast('.')
    val format = when (encoding?.lowercase()) {
        "jpg", "jpeg" -> MediaImageFormat.JPEG
        "png" -> MediaImageFormat.PNG
        "webp" -> MediaImageFormat.WEBP
        "gif" -> MediaImageFormat.GIF
        "avif" -> MediaImageFormat.AVIF
        else -> preview.format
    }
    // Original dimensions, size and proof are established only by the complete Save validation.
    return MediaImageMetadata(format)
}
