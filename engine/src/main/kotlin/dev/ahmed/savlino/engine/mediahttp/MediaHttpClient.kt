package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity

import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.domain.MediaAdapterIdentity
import dev.ahmed.savlino.domain.MediaInspectionValidity
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import dev.ahmed.savlino.engine.facebook.FacebookLinkKind
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramLinkKind
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.telegram.TelegramPostIdentity
import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.x.XPostIdentity
import dev.ahmed.savlino.engine.pinterest.PinterestPostIdentity
import dev.ahmed.savlino.engine.reddit.RedditJsChallenge
import dev.ahmed.savlino.engine.reddit.RedditPostIdentity
import dev.ahmed.savlino.engine.tiktok.TikTokPostIdentity
import dev.ahmed.savlino.engine.tiktok.TikTokWafChallenge
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Internal seam below both guards. Production factory has no trust/routing/resolver overrides. */
internal class MediaHttpEnvironment(val rawResolver: (String) -> List<InetAddress>,
    val routeMapper: (InetSocketAddress) -> InetSocketAddress, val trustManager: X509TrustManager?)

/** Memory-only binding supplied by the inspection reference store, never serialized. */
internal class MediaHttpSaveBinding(val source: LinkRevision, val identity: MediaAdapterIdentity,
    val validity: MediaInspectionValidity) {
    override fun toString() = "MediaHttpSaveBinding(redacted)"
}

/** Possessing this object alone does not authorize a transfer: issuer admission is also required. */
internal class MediaHttpPostContext(internal val canonicalPost: String, internal val binding: MediaHttpSaveBinding) {
    override fun toString() = "MediaHttpPostContext(redacted)"
}

/** Complete HTTP transfer evidence only; the image consumer still validates the representation. */
internal sealed interface MediaHttpImageTransferResult {
    data class Success(val mimeType: String?, val measuredBytes: Long, val completeBodySha256: String) :
        MediaHttpImageTransferResult
    data class Failure(val reason: MediaHttpFailure, val statusCode: Int? = null) : MediaHttpImageTransferResult
}

private enum class PageSite { LINKEDIN, FACEBOOK, X, INSTAGRAM, TELEGRAM, BLUESKY, TIKTOK, REDDIT, PINTEREST,
    PINTEREST_WIDGET, YOUTUBE_OEMBED, SHORT_LINK }
private class LinkedInPageAdmission(val operationId: OperationId, val entry: String, val canonicalPost: String)
private class ImageSaveState {
    val writeLock = Any()
    val contexts = IdentityHashMap<MediaHttpTarget, MediaHttpPostContext>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    fun trackSocket(socket: Socket) { sockets.add(socket) }
    fun abortSockets() { sockets.forEach { socket -> runCatching { socket.close() } } }
}

/** One reusable boundary; blocking fetch is off-main-thread work, with at most two active tokens. */
class MediaHttpClient private constructor(environment: MediaHttpEnvironment?) : Closeable {
    private val environment = environment ?: MediaHttpEnvironment(
        rawResolver = { InetAddress.getAllByName(it).toList() }, routeMapper = { it }, trustManager = null)
    private val lock = Any()
    private var closed = false
    private val active = linkedMapOf<OperationId, OperationToken>()
    private val linkedInPages = IdentityHashMap<MediaHttpTarget, LinkedInPageAdmission>()
    // Weak keys do not keep the reference store's discarded URL capabilities alive.
    private val storedPosts = WeakHashMap<MediaHttpPostContext, Unit>()
    private val imageSaves = linkedMapOf<OperationId, ImageSaveState>()
    // Live lookups hold a slot; a cancelled or timed-out lookup whose OS resolver ignores the interrupt keeps only
    // its thread. Twice the slots in threads, then BUSY: a stuck network still cannot pile up threads.
    private val resolverSlots = Semaphore(DNS_LIVE_LOOKUPS)
    private val resolverExecutor = ThreadPoolExecutor(0, 2 * DNS_LIVE_LOOKUPS, 30, TimeUnit.SECONDS,
        SynchronousQueue(), mediaThreads("media-http-dns"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, mediaThreads("media-http-deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    companion object {
        fun create(): MediaHttpClient = MediaHttpClient(null)
        internal fun forTesting(environment: MediaHttpEnvironment): MediaHttpClient = MediaHttpClient(environment)
    }

    internal fun newSaveOperation(): OperationToken = createOperation(MediaHttpOperation.DIRECT_IMAGE, save = true)

    internal fun fetchLinkedInPage(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.LINKEDIN)

    /**
     * Desktop-browser page GET of an already-normalised Facebook link. Redirect hops stay on accepted
     * Facebook shapes (www or regional web); a login wall is unwrapped once through its next= target,
     * otherwise it ends as LOGIN_REQUIRED.
     */
    internal fun fetchFacebookPage(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.FACEBOOK,
            stopAt = if (FacebookPostIdentity.parse(url)?.isShortlink == true) ::isFacebookVideoHop else null)

    /**
     * A shortlink's accepted reel/watch/video hop names the video: it ends as [MediaHttpFailure.LANDED] with that hop,
     * unrequested and with no body read. Login-wall unwraps never qualify; they keep the full page read.
     */
    private fun isFacebookVideoHop(located: HttpUrl, next: HttpUrl): Boolean = !FacebookPostIdentity.isLoginWall(located) &&
        FacebookPostIdentity.parse(next.toString())?.let { !it.isShortlink && it.videoId != null } == true

    /**
     * Optional second Facebook page (the attachment's own /photo/ page) on the same operation. Its own
     * failure (status, policy, login wall, size) does not retire the token, so the caller can still fetch
     * the smaller copy it already has; cancellation, retirement and the deadline still apply.
     */
    internal fun fetchFacebookPhotoPage(token: OperationToken, url: String): MediaHttpResult =
        if (FacebookPostIdentity.parse(url)?.kind != FacebookLinkKind.PHOTO) MediaHttpResult.Failure(MediaHttpFailure.INVALID_CONTEXT)
        else fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.FACEBOOK, optional = true)

    /**
     * The one public syndication read of an X post (`cdn.syndication.twimg.com/tweet-result`, this post's id and
     * token only). No redirect is followed, no cookie is sent; the JSON body is capped at [MediaPageLimits.X].
     */
    internal fun fetchXPost(token: OperationToken, identity: XPostIdentity): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(identity.syndicationUrl, MediaHttpPurpose.PAGE), PageSite.X)

    /**
     * The one logged-out page read of an Instagram post: `https://www.instagram.com/p/<code>/` exactly, desktop-browser
     * headers, no cookie. No redirect is followed; a login/challenge redirect ends as LOGIN_REQUIRED. Cap [MediaPageLimits.INSTAGRAM].
     */
    internal fun fetchInstagramPost(token: OperationToken, identity: InstagramPostIdentity): MediaHttpResult =
        if (identity.kind != InstagramLinkKind.POST) MediaHttpResult.Failure(MediaHttpFailure.INVALID_CONTEXT)
        else fetchInternal(token, PublicMediaRequest(identity.fetchUrl, MediaHttpPurpose.PAGE), PageSite.INSTAGRAM)

    internal fun fetchTelegramPost(token: OperationToken, identity: TelegramPostIdentity): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(identity.fetchUrl, MediaHttpPurpose.PAGE), PageSite.TELEGRAM)

    internal fun fetchTikTokPost(token: OperationToken, identity: TikTokPostIdentity): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(identity.videoUrl, MediaHttpPurpose.PAGE), PageSite.TIKTOK)

    internal fun fetchBlueskyApi(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.BLUESKY)

    internal fun newRedditOperation(): OperationToken = createOperation(MediaHttpOperation.PAGE_AND_IMAGE, save = false)

    /**
     * The logged-out page read of a Reddit post: desktop-browser headers, no cookie. Redirects stay on www post pages
     * of the same post (share and video links name it on their first hop); a sign-in redirect ends as LOGIN_REQUIRED.
     * Reddit's page check is answered by refetching the same page, at most twice per read. Cap [MediaPageLimits.REDDIT].
     */
    internal fun fetchRedditPost(token: OperationToken, identity: RedditPostIdentity): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(identity.fetchUrl, MediaHttpPurpose.PAGE), PageSite.REDDIT)

    /**
     * The logged-out page of one pin: `https://www.pinterest.com/pin/<id>/`, mobile browser headers, no cookie.
     * A same-pin redirect may drop its query; a login redirect is not followed. Cap [MediaPageLimits.PINTEREST].
     */
    internal fun fetchPinterestPost(token: OperationToken, identity: PinterestPostIdentity): MediaHttpResult =
        // A login wall or HTTP 401/403 may be answered by the public widget using the same live budget.
        fetchInternal(token, PublicMediaRequest(identity.fetchUrl, MediaHttpPurpose.PAGE), PageSite.PINTEREST, optional = true)

    /** Same-pin public JSON only; no redirects/cookies, with the page's cap, DNS guards and operation budget. */
    internal fun fetchPinterestPidgets(token: OperationToken, identity: PinterestPostIdentity): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(identity.pidgetsUrl, MediaHttpPurpose.PAGE), PageSite.PINTEREST_WIDGET,
            optional = true)

    internal fun newYouTubeOEmbedOperation(): OperationToken =
        createOperation(MediaHttpOperation.PAGE_AND_IMAGE, save = false, deadlineSeconds = 4)

    internal fun fetchYouTubeOEmbed(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.YOUTUBE_OEMBED)

    internal fun newShortLinkOperation(): OperationToken =
        createOperation(MediaHttpOperation.PAGE_AND_IMAGE, save = false, deadlineSeconds = 4)

    /**
     * Redirects of a known shortener, never a body: the first hop off the shortener ends as [MediaHttpFailure.LANDED]
     * without being requested. Any non-redirect answer (interstitial, 404) fails, so the caller keeps the short link.
     */
    internal fun fetchShortLink(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.SHORT_LINK,
            stopAt = { _, next -> !ShortLinks.isShortLink(next) })

    /** The same public-address DNS check a request would make, without requesting [url]; throws ADDRESS_REJECTED. */
    internal fun requirePublicHost(token: OperationToken, url: HttpUrl) {
        PublicAddressDns(token, environment.rawResolver, resolverExecutor, resolverSlots).lookup(url.host)
    }

    /**
     * A bad sibling must not retire valid carousel photos; policy, count, byte and deadline checks still apply.
     * [cap] is this body's [OperationBudget.reserveImage] share of the carousel's image bytes.
     */
    internal fun fetchCarouselImage(token: OperationToken, request: PublicMediaRequest, cap: Int): MediaHttpResult =
        if (!token.operation.isCarousel || request.purpose != MediaHttpPurpose.IMAGE ||
            (if (token.operation == MediaHttpOperation.LINKEDIN_PAGE_AND_CAROUSEL)
                request.pageContext == null || request.hostRule != MediaHostRule.ANY
            else request.hostRule !in setOf(MediaHostRule.FACEBOOK_CDN, MediaHostRule.X_MEDIA, MediaHostRule.INSTAGRAM_CDN,
                MediaHostRule.TELEGRAM_CDN, MediaHostRule.BLUESKY_CDN, MediaHostRule.TIKTOK_CDN, MediaHostRule.REDDIT_MEDIA,
                MediaHostRule.PINTEREST_CDN)))
            MediaHttpResult.Failure(MediaHttpFailure.INVALID_CONTEXT)
        else fetchInternal(token, request, null, optional = true, imageCap = cap)

    internal fun rememberLinkedInPost(token: OperationToken, page: MediaHttpTarget,
        binding: MediaHttpSaveBinding): MediaHttpPostContext? {
        if (token.owner !== this || token.checkActive() != null) return null
        return synchronized(lock) {
            if (closed || token.id.terminal != null) return@synchronized null
            val admission = linkedInPages[page] ?: return@synchronized null
            if (admission.operationId !== token.id) return@synchronized null
            val source = HttpsUrl.parse(binding.source.link.rawUrl) ?: return@synchronized null
            if (HttpsUrl.withoutQuery(source) != admission.entry || storedPosts.size >= 64) return@synchronized null
            MediaHttpPostContext(admission.canonicalPost, binding).also { storedPosts[it] = Unit }
        }
    }

    internal fun forgetLinkedInPost(context: MediaHttpPostContext) {
        synchronized(lock) { storedPosts.remove(context) }
    }

    internal fun bindSaveContext(token: OperationToken, stored: MediaHttpPostContext,
        current: MediaHttpSaveBinding): MediaHttpTarget? {
        if (token.owner !== this || token.checkActive() != null) return null
        return synchronized(lock) {
            if (closed || token.id.terminal != null || !storedPosts.containsKey(stored))
                return@synchronized null
            val save = imageSaves[token.id] ?: return@synchronized null
            if (!stored.binding.source.matches(current.source) || stored.binding.identity != current.identity ||
                stored.binding.validity !== current.validity) return@synchronized null
            val post = HttpsUrl.parse(stored.canonicalPost) ?: return@synchronized null
            if (!LinkedInPostIdentity.isLinkedInPost(post) || post.encodedQuery != null || post.fragment != null ||
                save.contexts.isNotEmpty()) return@synchronized null
            MediaHttpTarget(stored.canonicalPost, token.id, MediaHttpPurpose.PAGE).also { save.contexts[it] = stored }
        }
    }

    /** Streams HTTP bytes into private caller staging. Success is not permission to publish an image. */
    internal fun transferImage(token: OperationToken, request: PublicMediaRequest,
        sink: OutputStream, prepareBody: (Long) -> Unit = {}): MediaHttpImageTransferResult {
        if (token.owner !== this) return MediaHttpImageTransferResult.Failure(MediaHttpFailure.CLOSED)
        var entered = false
        try {
            token.requireActive()
            val save = synchronized(lock) { imageSaves[token.id] }
                ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (request.purpose != MediaHttpPurpose.IMAGE) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            var url = HttpsUrl.parse(request.url) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
            requireHostRule(request, url)
            val context = request.pageContext
            val referer = if (context == null) null else synchronized(lock) {
                if (context.operationId !== token.id || context.purpose != MediaHttpPurpose.PAGE ||
                    url.host != "media.licdn.com") throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                save.contexts[context]?.canonicalPost ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            }
            token.begin(MediaHttpPurpose.IMAGE)
            entered = true
            val configured = configuredClient(token) ?: throw MediaHttpException(MediaHttpFailure.CLOSED)
            val seen = hashSetOf(url.toString())
            while (true) {
                token.requireActive()
                token.budget.requireRequestRoom()
                val wire = Request.Builder().url(url).get().header("User-Agent", PUBLIC_USER_AGENT)
                    .header("Accept", IMAGE_ACCEPT).header("Accept-Encoding", "identity")
                    .tag(MediaHttpAttempt::class.java, MediaHttpAttempt())
                    .apply { if (referer != null) header("Referer", referer) }.build()
                val call = configured.newCall(wire)
                call.timeout().timeout(token.remainingNanos().coerceAtLeast(1), TimeUnit.NANOSECONDS)
                val unregister = token.register(call) { call.cancel() }
                try {
                    token.requireActive()
                    val response = call.execute()
                    try {
                        token.requireActive()
                        if (response.code in REDIRECTS) {
                            val next = HttpsUrl.resolve(url, response.header("Location").orEmpty())
                                ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                            if (referer != null && next.host != "media.licdn.com")
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            requireHostRule(request, next)
                            if (!seen.add(next.toString())) throw MediaHttpException(MediaHttpFailure.REDIRECT_LOOP)
                            token.budget.redirect()
                            url = next
                        } else {
                            if (response.code != 200) throw MediaHttpException(MediaHttpFailure.HTTP_STATUS, response.code)
                            return streamSaveBody(token, save, response, sink, prepareBody)
                        }
                    } finally { call.cancel(); response.close() }
                } finally { unregister() }
            }
        } catch (failure: MediaHttpException) {
            if (entered || failure.reason != MediaHttpFailure.BUSY) token.stop(failure.reason)
            return MediaHttpImageTransferResult.Failure(token.checkActive() ?: failure.reason, failure.statusCode)
        } catch (_: SocketTimeoutException) {
            token.stop(token.checkActive() ?: MediaHttpFailure.TIMEOUT)
            return MediaHttpImageTransferResult.Failure(token.checkActive() ?: MediaHttpFailure.TIMEOUT)
        } catch (_: IOException) {
            token.stop(token.checkActive() ?: MediaHttpFailure.NETWORK)
            return MediaHttpImageTransferResult.Failure(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } catch (_: RuntimeException) {
            token.stop(token.checkActive() ?: MediaHttpFailure.NETWORK)
            return MediaHttpImageTransferResult.Failure(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } finally { if (entered) token.endFetch() }
    }

    private fun streamSaveBody(token: OperationToken, save: ImageSaveState, response: Response,
        sink: OutputStream, prepareBody: (Long) -> Unit): MediaHttpImageTransferResult.Success {
        if (response.headers.values("Content-Encoding").any { !it.trim().equals("identity", ignoreCase = true) })
            throw MediaHttpException(MediaHttpFailure.ENCODING)
        val lengths = response.headers.values("Content-Length").flatMap { it.split(',') }.map { it.trim() }
        val parsedLengths = lengths.map { value ->
            if (value.isEmpty() || value.any { it !in '0'..'9' }) throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
            value.toLongOrNull() ?: throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        }
        if (parsedLengths.distinct().size > 1) throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
        if (parsedLengths.any { it > SAVE_BODY_CAP }) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        val codings = response.headers.values("Transfer-Encoding").flatMap { it.split(',') }
        if (codings.any { !it.trim().equals("chunked", ignoreCase = true) }) throw MediaHttpException(MediaHttpFailure.ENCODING)
        val declared = response.body.contentLength()
        if (declared > SAVE_BODY_CAP) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        token.requireActive()
        prepareBody(declared)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        // Response owner cancels the call before close, including on read/write/validation failure.
        val stream = response.body.byteStream()
        while (true) {
            token.requireActive()
            val read = try {
                saveIdleRead(token) {
                    stream.read(buffer, 0, minOf(buffer.size.toLong(), SAVE_BODY_CAP - count + 1).toInt())
                }
            }
            catch (_: SocketTimeoutException) { throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.TIMEOUT) }
            catch (_: IOException) { throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.PARTIAL_BODY) }
            if (read < 0) break
            if (read == 0) continue
            count += read
            token.budget.consumed(read)
            if (count > SAVE_BODY_CAP) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
            synchronized(save.writeLock) {
                token.requireActive()
                sink.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                token.requireActive()
            }
        }
        if (declared >= 0 && count != declared) throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
        val rawMime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val mime = rawMime?.takeIf { it.length <= 128 && MIME.matches(it) }
        return synchronized(save.writeLock) {
            token.requireActive()
            MediaHttpImageTransferResult.Success(mime, count,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }

    private fun <T> saveIdleRead(token: OperationToken, read: () -> T): T {
        val waiting = AtomicBoolean(true)
        val alarm = deadlines.schedule({
            if (waiting.compareAndSet(true, false)) token.stop(MediaHttpFailure.TIMEOUT)
        }, 5, TimeUnit.SECONDS)
        val unregister = token.register(alarm) { alarm.cancel(false) }
        return try {
            token.requireActive()
            read()
        } finally {
            waiting.set(false)
            unregister()
            alarm.cancel(false)
        }
    }

    fun newOperation(operation: MediaHttpOperation): OperationToken = createOperation(operation, save = false)

    private fun createOperation(operation: MediaHttpOperation, save: Boolean,
        deadlineSeconds: Long = if (save) 120 else 15): OperationToken = synchronized(lock) {
        val rejection = if (closed) MediaHttpFailure.CLOSED else if (active.size >= 2) MediaHttpFailure.BUSY else null
        val token = OperationToken(operation, this, rejection, deadlineSeconds) { retired ->
            synchronized(lock) {
                active.remove(retired.id)
                imageSaves.remove(retired.id)
                linkedInPages.entries.removeAll { it.value.operationId === retired.id }
            }
        }
        if (rejection == null) {
            active[token.id] = token
            if (save) {
                val state = ImageSaveState()
                imageSaves[token.id] = state
                // stop() marks terminal first, then drains an admitted write before cancellation returns.
                token.register(state.writeLock) { synchronized(state.writeLock) {} }
                // Abort TCP before OkHttp/JSSE cleanup can add another read timeout draining TLS.
                token.register(state) { state.abortSockets() }
            }
            val timer = deadlines.schedule({ token.stop(MediaHttpFailure.TIMEOUT) },
                token.remainingNanos().coerceAtLeast(1), TimeUnit.NANOSECONDS)
            token.register(timer) { timer.cancel(false) }
        }
        token
    }

    fun fetch(token: OperationToken, request: PublicMediaRequest): MediaHttpResult = fetchInternal(token, request, null)

    private fun fetchInternal(token: OperationToken, request: PublicMediaRequest, site: PageSite?,
        optional: Boolean = false, imageCap: Int? = null, stopAt: ((HttpUrl, HttpUrl) -> Boolean)? = null): MediaHttpResult {
        val linkedInPage = site == PageSite.LINKEDIN
        val facebookPage = site == PageSite.FACEBOOK
        val xPost = site == PageSite.X
        val instagramPage = site == PageSite.INSTAGRAM
        val telegramPage = site == PageSite.TELEGRAM
        val blueskyPage = site == PageSite.BLUESKY
        val tiktokPage = site == PageSite.TIKTOK
        val redditPage = site == PageSite.REDDIT
        val pinterestPage = site == PageSite.PINTEREST
        val pinterestWidget = site == PageSite.PINTEREST_WIDGET
        val oembed = site == PageSite.YOUTUBE_OEMBED
        val shortLink = site == PageSite.SHORT_LINK
        val browserPage = facebookPage || instagramPage || telegramPage || tiktokPage || redditPage
        if (token.owner !== this) return MediaHttpResult.Failure(MediaHttpFailure.CLOSED)
        var entered = false
        // LinkedIn: the last public post URL this read reached (entry or accepted hop), query/fragment stripped.
        var landing: String? = null
        var imageMimeType: String? = null
        fun failed(reason: MediaHttpFailure, statusCode: Int? = null) = MediaHttpResult.Failure(reason, statusCode, landing).also {
            if (reason == MediaHttpFailure.BODY_LIMIT) it.imageMimeType = imageMimeType
        }
        try {
            token.requireActive()
            if (synchronized(lock) { imageSaves.containsKey(token.id) })
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            var url = HttpsUrl.parse(request.url) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
            if (oembed && (request.purpose != MediaHttpPurpose.PAGE || !YouTubeOEmbed.isRequest(url)))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (shortLink && (request.purpose != MediaHttpPurpose.PAGE || !ShortLinks.isShortLink(url)))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (linkedInPage && !LinkedInPostIdentity.isLinkedInPageEntry(url))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (linkedInPage) url = LinkedInPostIdentity.normalizeLinkedInEntry(url)
            if (facebookPage && (request.purpose != MediaHttpPurpose.PAGE ||
                    token.operation !in setOf(MediaHttpOperation.PAGES_AND_IMAGE, MediaHttpOperation.PAGES_AND_CAROUSEL) ||
                    FacebookPostIdentity.parse(request.url)?.fetchUrl != request.url))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (xPost && (request.purpose != MediaHttpPurpose.PAGE || token.operation !in setOf(MediaHttpOperation.PAGE_AND_IMAGE, MediaHttpOperation.PAGE_AND_CAROUSEL) ||
                    !XPostIdentity.isSyndicationRequest(url.host, url.encodedPath, url.encodedQuery)))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            // Any other page purpose is LinkedIn's or Facebook's own entry point, never a borrowed X-shaped URL.
            if (!xPost && request.purpose == MediaHttpPurpose.PAGE && url.host == XPostIdentity.SYNDICATION_HOST)
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (instagramPage && (request.purpose != MediaHttpPurpose.PAGE || token.operation !in setOf(MediaHttpOperation.PAGE_AND_IMAGE, MediaHttpOperation.PAGE_AND_CAROUSEL) ||
                    !InstagramPostIdentity.isPageRequest(url)))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (!instagramPage && request.purpose == MediaHttpPurpose.PAGE && InstagramPostIdentity.isInstagramHost(url.host))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (telegramPage && (request.purpose != MediaHttpPurpose.PAGE || token.operation != MediaHttpOperation.PAGE_AND_CAROUSEL ||
                    !TelegramPostIdentity.isPageRequest(url))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (blueskyPage && (request.purpose != MediaHttpPurpose.PAGE || token.operation != MediaHttpOperation.PAGES_AND_CAROUSEL ||
                    !BlueskyPostIdentity.isApiRequest(url))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (request.purpose == MediaHttpPurpose.PAGE && ((!telegramPage && url.host in setOf("t.me", "telegram.me")) ||
                    (!blueskyPage && url.host == BlueskyPostIdentity.API_HOST))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (tiktokPage && (request.purpose != MediaHttpPurpose.PAGE || token.operation != MediaHttpOperation.TIKTOK_PAGE_AND_CAROUSEL ||
                    !TikTokPostIdentity.isPageRequest(url))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (!tiktokPage && request.purpose == MediaHttpPurpose.PAGE && url.host in setOf("www.tiktok.com", "tiktok.com", "m.tiktok.com"))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (redditPage && (request.purpose != MediaHttpPurpose.PAGE ||
                    token.operation !in setOf(MediaHttpOperation.PAGE_AND_IMAGE, MediaHttpOperation.PAGE_AND_CAROUSEL) ||
                    RedditPostIdentity.parse(url)?.fetchUrl != url.toString())) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (pinterestPage && (request.purpose != MediaHttpPurpose.PAGE ||
                    token.operation !in setOf(MediaHttpOperation.PAGE_AND_IMAGE, MediaHttpOperation.PAGE_AND_CAROUSEL,
                        MediaHttpOperation.PAGES_AND_CAROUSEL) ||
                    !PinterestPostIdentity.isPageRequest(url))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (pinterestWidget && (request.purpose != MediaHttpPurpose.PAGE ||
                    token.operation != MediaHttpOperation.PAGES_AND_CAROUSEL ||
                    !PinterestPostIdentity.isPidgetsRequest(url))) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (!pinterestPage && request.purpose == MediaHttpPurpose.PAGE && PinterestPostIdentity.isPinterestHost(url.host))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (!pinterestWidget && request.purpose == MediaHttpPurpose.PAGE && url.host == PinterestPostIdentity.WIDGET_HOST)
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            requireHostRule(request, url)
            val entry = if (linkedInPage) HttpsUrl.withoutQuery(url) else null
            validateContext(token, request, url)
            token.begin(request.purpose)
            entered = true
            val configured = configuredClient(token) ?: throw MediaHttpException(MediaHttpFailure.CLOSED)
            val seen = hashSetOf(url.toString())
            // TikTok's «Please wait…» answer: sent only on the one refetch of this same page read.
            var tikTokChallenge: String? = null
            // Reddit: the post a share/video link's first hop named, and how many page checks this read answered.
            var redditPost = if (redditPage) RedditPostIdentity.parse(url)?.postId else null
            var redditAnswers = 0
            while (true) {
                if (linkedInPage && LinkedInPostIdentity.isLinkedInPost(url)) landing = HttpsUrl.withoutQuery(url)
                token.requireActive()
                token.budget.requireRequestRoom()
                val wireRequest = Request.Builder().url(url).get()
                    .header("User-Agent", if (browserPage) DESKTOP_BROWSER_AGENT else if (shortLink) SHORT_LINK_AGENT else PUBLIC_USER_AGENT)
                    .header("Accept", if (browserPage) BROWSER_PAGE_ACCEPT else if (xPost || blueskyPage || oembed || pinterestWidget) JSON_ACCEPT
                        else if (request.purpose == MediaHttpPurpose.PAGE) PAGE_ACCEPT else IMAGE_ACCEPT)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        // Facebook answers 400 without Sec-Fetch-Mode and a data-less shell without text/html.
                        if (browserPage) BROWSER_NAVIGATION_HEADERS.forEach { (name, value) -> header(name, value) }
                        tikTokChallenge?.let { header("Cookie", it) }
                    }
                    .tag(MediaHttpAttempt::class.java, MediaHttpAttempt())
                    .apply {
                        if (request.pageContext != null && url.host == "media.licdn.com")
                            header("Referer", synchronized(lock) {
                                linkedInPages[request.pageContext]?.canonicalPost
                            } ?: "https://www.linkedin.com/")
                    }.build()
                val call = configured.newCall(wireRequest)
                call.timeout().timeout(token.remainingNanos().coerceAtLeast(1), TimeUnit.NANOSECONDS)
                val unregister = token.register(call) { call.cancel() }
                try {
                    token.requireActive()
                    val response = call.execute()
                    try {
                        token.requireActive()
                        if (response.code in REDIRECTS) {
                            // Keep the one-page read bound to the same post, with DNS checked again on each hop.
                            if (tiktokPage) {
                                val next = HttpsUrl.resolve(url, response.header("Location").orEmpty())
                                    ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                                val identity = TikTokPostIdentity.parse(next.toString())
                                if (identity == null || next.host != "www.tiktok.com" ||
                                    identity.postId != TikTokPostIdentity.parse(request.url)?.postId)
                                    throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            }
                            if (telegramPage) throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
                            if (xPost || blueskyPage || oembed || pinterestWidget) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (instagramPage) throw MediaHttpException(
                                if (HttpsUrl.resolve(url, response.header("Location").orEmpty())
                                        ?.let(InstagramPostIdentity::isLoginWall) == true) MediaHttpFailure.LOGIN_REQUIRED
                                else MediaHttpFailure.INVALID_CONTEXT)
                            val location = response.header("Location").orEmpty()
                            val located = HttpsUrl.resolve(url, location)
                                // A shortener's http destination is read over HTTPS, as a pasted one is; an IP literal,
                                // localhost or malformed destination is refused outright, so nothing hands it on.
                                ?: if (shortLink) HttpsUrl.resolve(url, location.replaceFirst(HTTP_SCHEME, "https://"))
                                    ?: throw MediaHttpException(MediaHttpFailure.ADDRESS_REJECTED)
                                else if (redditPage) RedditPostIdentity.asciiLocation(location)?.let { HttpsUrl.resolve(url, it) }
                                    ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                                else throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                            val next = when {
                                tiktokPage -> HttpsUrl.parse(checkNotNull(TikTokPostIdentity.parse(located.toString())).videoUrl)
                                    ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                                facebookPage -> facebookHop(url, located, seen)
                                redditPage -> {
                                    if (RedditPostIdentity.isLoginWall(located)) throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
                                    val hop = RedditPostIdentity.postHop(located)
                                    if (hop == null || (redditPost != null && hop.postId != redditPost))
                                        throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                                    redditPost = hop.postId
                                    HttpsUrl.parse(hop.fetchUrl) ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                                }
                                pinterestPage -> {
                                    if (PinterestPostIdentity.isLoginWall(located)) throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
                                    val hop = PinterestPostIdentity.parse(located.toString())
                                    val requested = PinterestPostIdentity.parse(request.url)?.pinId
                                    if (hop == null || hop.pinId != requested || located.host != PinterestPostIdentity.PAGE_HOST)
                                        throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                                    located.newBuilder().query(null).fragment(null).build()
                                }
                                linkedInPage -> LinkedInPostIdentity.linkedInHopTarget(located)
                                else -> located
                            }
                            // A members-only post sends logged-out visitors to sign up or log in (2026-10-21).
                            if (linkedInPage && LinkedInPostIdentity.isLinkedInLoginWall(next))
                                throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
                            if (linkedInPage && !MediaHttpPolicy.allowsLinkedInPageHop(url, next))
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (request.purpose == MediaHttpPurpose.PAGE && next.host == PinterestPostIdentity.WIDGET_HOST)
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            requireHostRule(request, next)
                            if (request.pageContext != null && next.host != "media.licdn.com")
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (!seen.add((if (tiktokPage) located else next).toString()))
                                throw MediaHttpException(MediaHttpFailure.REDIRECT_LOOP)
                            // The landing hop counts too: a stop never stretches the three-redirect limit.
                            token.budget.redirect()
                            if (stopAt?.invoke(located, next) == true)
                                return MediaHttpResult.Failure(MediaHttpFailure.LANDED, null, next.toString())
                            url = next
                        } else {
                            if (response.code != 200) throw MediaHttpException(MediaHttpFailure.HTTP_STATUS, response.code)
                            if (shortLink) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (linkedInPage && !LinkedInPostIdentity.isLinkedInPost(url))
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (facebookPage && FacebookPostIdentity.parse(url.toString()).let { it == null || it.isShortlink })
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (request.purpose == MediaHttpPurpose.IMAGE) imageMimeType = response.header("Content-Type")
                            val complete = readComplete(token, request.purpose, url, response, call,
                                if (oembed) YouTubeOEmbed.MAX_BYTES else if (facebookPage) MediaPageLimits.FACEBOOK else if (xPost) MediaPageLimits.X
                                else if (instagramPage) MediaPageLimits.INSTAGRAM
                                else if (tiktokPage) MediaPageLimits.TIKTOK
                                else if (redditPage) MediaPageLimits.REDDIT
                                else if (pinterestPage || pinterestWidget) MediaPageLimits.PINTEREST
                                else if (telegramPage) MediaPageLimits.TELEGRAM else if (blueskyPage) MediaPageLimits.BLUESKY
                                else if (linkedInPage) MediaPageLimits.LINKEDIN
                                else imageCap?.let { minOf(it, token.budget.imageLimit()) })
                            if (tiktokPage && tikTokChallenge == null) {
                                val answer = try {
                                    complete.peek { bytes, length -> TikTokWafChallenge.solve(bytes, length, token::requireActive) }
                                } catch (failure: Exception) { complete.close(); throw failure }
                                if (answer != null) {
                                    complete.close()
                                    tikTokChallenge = answer
                                    continue
                                }
                            }
                            if (redditPage && redditAnswers < 2) {
                                val answer = try {
                                    complete.peek { bytes, length -> RedditJsChallenge.answer(bytes, length, url) }
                                } catch (failure: Exception) { complete.close(); throw failure }
                                if (answer != null) {
                                    complete.close()
                                    redditAnswers++
                                    url = answer
                                    continue
                                }
                            }
                            if (redditPage && RedditPostIdentity.postHop(url)?.postId.let { it == null || (redditPost != null && it != redditPost) }) {
                                complete.close()
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            }
                            try {
                                token.admit(complete)
                                token.requireActive()
                                if (linkedInPage) synchronized(lock) {
                                    token.id.terminal?.let { throw MediaHttpException(it) }
                                    linkedInPages[complete.target] = LinkedInPageAdmission(token.id, checkNotNull(entry),
                                        HttpsUrl.withoutQuery(url))
                                }
                                return MediaHttpResult.Success(complete)
                            } catch (failure: Exception) {
                                complete.close()
                                throw failure
                            }
                        }
                    } finally {
                        // Stop unread redirects/errors before OkHttp close() can drain their bodies.
                        call.cancel()
                        response.close()
                    }
                } finally { unregister() }
            }
        } catch (failure: MediaHttpException) {
            // A concurrent call rejected as BUSY must not cancel the operation's existing fetch.
            if (!optional && (entered || failure.reason != MediaHttpFailure.BUSY)) token.stop(failure.reason)
            return failed(token.checkActive() ?: failure.reason, failure.statusCode)
        } catch (_: OutOfMemoryError) {
            if (!optional) token.stop(MediaHttpFailure.BODY_LIMIT)
            return failed(token.checkActive() ?: MediaHttpFailure.BODY_LIMIT)
        } catch (_: SocketTimeoutException) {
            if (!optional) token.stop(token.checkActive() ?: MediaHttpFailure.TIMEOUT)
            return failed(token.checkActive() ?: MediaHttpFailure.TIMEOUT)
        } catch (_: IOException) {
            val reason = token.checkActive() ?: MediaHttpFailure.NETWORK
            if (!optional) token.stop(reason)
            return failed(token.checkActive() ?: reason)
        } catch (_: RuntimeException) {
            if (!optional) token.stop(token.checkActive() ?: MediaHttpFailure.NETWORK)
            return failed(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } finally { if (entered) token.endFetch() }
    }

    private fun requireHostRule(request: PublicMediaRequest, url: HttpUrl) {
        val allowed = when (request.hostRule) {
            MediaHostRule.ANY -> true
            MediaHostRule.FACEBOOK_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isFacebookImageCdn(url)
            MediaHostRule.X_MEDIA -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isXImage(url)
            MediaHostRule.INSTAGRAM_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isInstagramImage(url)
            MediaHostRule.TELEGRAM_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isTelegramImage(url)
            MediaHostRule.BLUESKY_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isBlueskyImage(url)
            MediaHostRule.TIKTOK_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isTikTokImage(url)
            MediaHostRule.REDDIT_MEDIA -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isRedditImage(url)
            MediaHostRule.PINTEREST_CDN -> request.purpose == MediaHttpPurpose.IMAGE && MediaHttpPolicy.isPinterestImage(url)
        }
        if (!allowed) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
    }

    /** Normalised next Facebook page, the once-unwrapped login next=, or a typed refusal. */
    private fun facebookHop(current: HttpUrl, next: HttpUrl, seen: Set<String>): HttpUrl {
        if (FacebookPostIdentity.isStoryLink(next.toString())) throw MediaHttpException(MediaHttpFailure.FACEBOOK_STORY)
        if (FacebookPostIdentity.isLoginWall(next)) {
            val nextUrl = next.queryParameterValues("next").singleOrNull()
            if (nextUrl != null && FacebookPostIdentity.isStoryLink(nextUrl))
                throw MediaHttpException(MediaHttpFailure.FACEBOOK_STORY)
            val target = nextUrl?.let(FacebookPostIdentity::parse)
                ?.takeUnless { it.isShortlink || it.fetchUrlOn(next.host) in seen } ?: throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
            return HttpsUrl.parse(target.fetchUrlOn(next.host)) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
        }
        val identity = FacebookPostIdentity.parse(next.toString())
            ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        if (identity.isShortlink) {
            val previous = FacebookPostIdentity.parse(current.toString())
            // One regional hop may keep the same shortlink; the web page must then resolve it.
            if (current.host == "web.facebook.com" || next.host != "web.facebook.com" ||
                previous == null || previous.kind != identity.kind || previous.fetchUrl.trimEnd('/') != identity.fetchUrl.trimEnd('/'))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        }
        return HttpsUrl.parse(identity.fetchUrlOn(next.host)) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
    }

    private fun validateContext(token: OperationToken, request: PublicMediaRequest, url: HttpUrl) {
        val context = request.pageContext ?: return
        if (context.operationId !== token.id || context.purpose != MediaHttpPurpose.PAGE ||
            request.purpose != MediaHttpPurpose.IMAGE || url.host != "media.licdn.com")
            throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        val page = HttpsUrl.parse(context.url()) ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        if (page.host != "www.linkedin.com" || !(page.encodedPath.startsWith("/posts/") ||
                page.encodedPath.startsWith("/feed/update/urn:li:activity:")))
            throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
    }

    private fun readComplete(token: OperationToken, purpose: MediaHttpPurpose, url: HttpUrl,
        response: Response, call: Call, pageCap: Int? = null): MediaHttpResponse {
        val encodings = response.headers.values("Content-Encoding")
        if (encodings.any { !it.trim().equals("identity", ignoreCase = true) })
            throw MediaHttpException(MediaHttpFailure.ENCODING)
        val cap = pageCap ?: if (purpose == MediaHttpPurpose.PAGE) 1024 * 1024 else token.budget.imageLimit()
        if (cap <= 0) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        val lengths = response.headers.values("Content-Length").flatMap { it.split(',') }.map { it.trim() }
        val parsedLengths = lengths.map { value ->
            if (value.isEmpty() || value.any { it !in '0'..'9' })
                throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
            value.toLongOrNull() ?: throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        }
        if (parsedLengths.distinct().size > 1) throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
        if (parsedLengths.any { it > cap }) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        val transferCodings = response.headers.values("Transfer-Encoding").flatMap { it.split(',') }
        if (transferCodings.any { !it.trim().equals("chunked", ignoreCase = true) })
            throw MediaHttpException(MediaHttpFailure.ENCODING)
        val body = response.body
        val declared = body.contentLength()
        if (declared > cap) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
        // Known lengths allocate once; unknown lengths grow from one read block and trim once below.
        val limit = if (declared >= 0) declared.toInt() else cap
        var buffer = ByteArray(if (declared >= 0) limit else minOf(cap, 8192))
        var count = 0
        try {
            body.source().use { source ->
                try {
                    while (true) {
                        token.requireActive()
                        if (count == limit) {
                            // Peek, don't take: an over-cap body consumes exactly its cap, so parallel carousel caps hold.
                            if (!source.exhausted())
                                throw MediaHttpException(if (count == cap) MediaHttpFailure.BODY_LIMIT else MediaHttpFailure.PARTIAL_BODY)
                            break
                        }
                        if (count == buffer.size) buffer = buffer.copyOf(minOf(cap, buffer.size * 2))
                        val read = source.read(buffer, count, minOf(8192, buffer.size - count))
                        if (read == -1) break
                        if (read == 0) continue
                        count += read
                        token.budget.consumed(read, purpose == MediaHttpPurpose.IMAGE)
                        if (count > cap) throw MediaHttpException(MediaHttpFailure.BODY_LIMIT)
                    }
                } catch (failure: Throwable) {
                    call.cancel() // Prevent Source.close() from draining an over-budget remainder.
                    throw failure
                }
            }
        } catch (failure: MediaHttpException) { throw failure }
        catch (_: SocketTimeoutException) { throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.TIMEOUT) }
        catch (_: IOException) { throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.PARTIAL_BODY) }
        token.requireActive()
        if (declared >= 0 && count.toLong() != declared) throw MediaHttpException(MediaHttpFailure.PARTIAL_BODY)
        val rawMime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val mime = rawMime?.takeIf { it.length <= 128 && MIME.matches(it) }
        if (buffer.size > count) buffer = buffer.copyOf(count)
        return MediaHttpResponse(mime, count.toLong(), MediaHttpTarget(url.toString(), token.id, purpose), buffer)
    }

    fun cancel(operationId: OperationId) {
        if (operationId.owner !== this) return
        val token = synchronized(lock) { active[operationId] }
        token?.stop(MediaHttpFailure.CANCELLED)
    }

    internal fun configuredClient(token: OperationToken): OkHttpClient? {
        if (token.owner !== this || token.checkActive() != null) return null
        val save = synchronized(lock) { imageSaves[token.id] }
        return token.client {
            val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
                mediaThreads("media-http-call"), ThreadPoolExecutor.AbortPolicy())
            try {
                val builder = OkHttpClient.Builder()
                    .dispatcher(Dispatcher(executor))
                    // Keep-alive stays inside this token: the client and its pool are evicted when the operation retires.
                    .connectionPool(ConnectionPool(CAROUSEL_PARALLEL_FETCHES, 10, TimeUnit.SECONDS))
                    .proxy(Proxy.NO_PROXY)
                    .dns(PublicAddressDns(token, environment.rawResolver, resolverExecutor, resolverSlots))
                    .socketFactory(GuardedMediaSocketFactory(token, environment.routeMapper, save?.let { it::trackSocket }))
                    .protocols(listOf(Protocol.HTTP_1_1)).connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                    .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                    .cache(null).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).fastFallback(false)
                    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(token.remainingNanos().coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    .addNetworkInterceptor { chain ->
                        token.requireActive()
                        val attempt = chain.request().tag(MediaHttpAttempt::class.java)
                            ?: throw MediaHttpException(MediaHttpFailure.NETWORK)
                        if (attempt.exchanges.incrementAndGet() != 1)
                            throw MediaHttpException(MediaHttpFailure.HTTP_STATUS, attempt.status)
                        token.budget.request()
                        val response = chain.proceed(chain.request())
                        attempt.status = response.code
                        // OkHttp5.5 follows 503/Retry-After:0 even with retryOnConnectionFailure=false.
                        if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
                    }
                environment.trustManager?.let { trust ->
                    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                    builder.sslSocketFactory(context.socketFactory, trust)
                }
                builder.build()
            } catch (failure: Exception) { executor.shutdownNow(); throw failure }
        }
    }

    override fun close() {
        val tokens = synchronized(lock) {
            if (closed) return
            closed = true
            storedPosts.clear()
            active.values.toList()
        }
        tokens.forEach { it.close() }
        deadlines.shutdownNow()
        resolverExecutor.shutdownNow()
    }
}

private class MediaHttpAttempt {
    val exchanges = AtomicInteger()
    @Volatile var status: Int? = null
}

private fun mediaThreads(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
private val REDIRECTS = setOf(301, 302, 303, 307, 308)
private val MIME = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
private const val JSON_ACCEPT = "application/json"
private const val PAGE_ACCEPT = "text/html,application/xhtml+xml;q=0.9"
private const val BROWSER_PAGE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
private const val DESKTOP_BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
private val BROWSER_NAVIGATION_HEADERS = listOf("Accept-Language" to "en-US,en;q=0.9", "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none", "Sec-Fetch-Dest" to "document", "Sec-Fetch-User" to "?1", "Upgrade-Insecure-Requests" to "1")
private const val IMAGE_ACCEPT = "image/jpeg,image/png,image/gif,image/webp;q=0.9"
private const val SAVE_BODY_CAP = 20L * 1024 * 1024
/** Shorteners (t.co, Instagram /share/) answer a browser with an HTML interstitial and anything else with the redirect. */
private const val SHORT_LINK_AGENT = "Nezzel"
private val HTTP_SCHEME = Regex("^http://", RegexOption.IGNORE_CASE)
private const val PUBLIC_USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** Checks the exact route immediately before Socket.connect; fixture mapping is strictly below this guard. */
internal class GuardedMediaSocketFactory(private val token: OperationToken,
    private val routeMapper: (InetSocketAddress) -> InetSocketAddress,
    private val onSocketCreated: ((Socket) -> Unit)? = null) : SocketFactory() {
    override fun createSocket(): Socket {
        token.requireActive()
        var unregister: () -> Unit = {}
        val socket = object : Socket() {
            private val released = AtomicBoolean()
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                token.requireActive()
                val route = endpoint as? InetSocketAddress
                    ?: throw MediaHttpException(MediaHttpFailure.ADDRESS_REJECTED)
                val address = route.address ?: throw MediaHttpException(MediaHttpFailure.ADDRESS_REJECTED)
                if (route.port != 443 || !MediaHttpPolicy.allows(address))
                    throw MediaHttpException(MediaHttpFailure.ADDRESS_REJECTED)
                val mapped = routeMapper(route)
                token.requireActive()
                super.connect(mapped, timeout)
            }
            override fun close() {
                try { super.close() }
                finally {
                    if (released.compareAndSet(false, true)) { token.budget.socketClosed(); unregister() }
                }
            }
        }
        token.budget.socketCreated()
        unregister = token.register(socket) { socket.close() }
        onSocketCreated?.invoke(socket)
        token.requireActive()
        return socket
    }
    override fun createSocket(host: String, port: Int): Socket = throw IOException("media_http_connect_overload_rejected")
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        throw IOException("media_http_connect_overload_rejected")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("media_http_connect_overload_rejected")
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        throw IOException("media_http_connect_overload_rejected")
}
