package dev.ahmed.savlino.engine.mediahttp

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

private enum class PageSite { LINKEDIN, FACEBOOK, X, INSTAGRAM, TELEGRAM, BLUESKY }
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
    private val resolverExecutor = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
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
     * Facebook shapes (normalised to www); a login wall is unwrapped once through its next= target,
     * otherwise it ends as LOGIN_REQUIRED.
     */
    internal fun fetchFacebookPage(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.FACEBOOK)

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

    internal fun fetchBlueskyApi(token: OperationToken, url: String): MediaHttpResult =
        fetchInternal(token, PublicMediaRequest(url, MediaHttpPurpose.PAGE), PageSite.BLUESKY)

    /** A bad sibling must not retire valid carousel photos; policy, count, byte and deadline checks still apply. */
    internal fun fetchCarouselImage(token: OperationToken, request: PublicMediaRequest, thumbnail: Boolean = false): MediaHttpResult =
        if (!token.operation.isCarousel || request.purpose != MediaHttpPurpose.IMAGE ||
            (if (token.operation == MediaHttpOperation.LINKEDIN_PAGE_AND_CAROUSEL)
                request.pageContext == null || request.hostRule != MediaHostRule.ANY
            else request.hostRule !in setOf(MediaHostRule.X_MEDIA, MediaHostRule.INSTAGRAM_CDN, MediaHostRule.TELEGRAM_CDN, MediaHostRule.BLUESKY_CDN)))
            MediaHttpResult.Failure(MediaHttpFailure.INVALID_CONTEXT)
        else fetchInternal(token, request, null, optional = true, imageCap = if (thumbnail) token.budget.imageLimit(thumbnail = true) else null)

    internal fun rememberLinkedInPost(token: OperationToken, page: MediaHttpTarget,
        binding: MediaHttpSaveBinding): MediaHttpPostContext? {
        if (token.owner !== this || token.checkActive() != null) return null
        return synchronized(lock) {
            if (closed || token.id.terminal != null) return@synchronized null
            val admission = linkedInPages[page] ?: return@synchronized null
            if (admission.operationId !== token.id) return@synchronized null
            val source = MediaHttpPolicy.parse(binding.source.link.rawUrl) ?: return@synchronized null
            if (MediaHttpPolicy.withoutQuery(source) != admission.entry || storedPosts.size >= 64) return@synchronized null
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
            val post = MediaHttpPolicy.parse(stored.canonicalPost) ?: return@synchronized null
            if (!MediaHttpPolicy.isLinkedInPost(post) || post.encodedQuery != null || post.fragment != null ||
                save.contexts.isNotEmpty()) return@synchronized null
            MediaHttpTarget(stored.canonicalPost, token.id, MediaHttpPurpose.PAGE).also { save.contexts[it] = stored }
        }
    }

    /** Streams HTTP bytes into private caller staging. Success is not permission to publish an image. */
    internal fun transferImage(token: OperationToken, request: PublicMediaRequest,
        sink: OutputStream): MediaHttpImageTransferResult {
        if (token.owner !== this) return MediaHttpImageTransferResult.Failure(MediaHttpFailure.CLOSED)
        var entered = false
        try {
            token.requireActive()
            val save = synchronized(lock) { imageSaves[token.id] }
                ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (request.purpose != MediaHttpPurpose.IMAGE) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            var url = MediaHttpPolicy.parse(request.url) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
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
                            val next = MediaHttpPolicy.resolve(url, response.header("Location").orEmpty())
                                ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                            if (referer != null && next.host != "media.licdn.com")
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            requireHostRule(request, next)
                            if (!seen.add(next.toString())) throw MediaHttpException(MediaHttpFailure.REDIRECT_LOOP)
                            token.budget.redirect()
                            url = next
                        } else {
                            if (response.code != 200) throw MediaHttpException(MediaHttpFailure.HTTP_STATUS, response.code)
                            return streamSaveBody(token, save, response, sink)
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
        sink: OutputStream): MediaHttpImageTransferResult.Success {
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

    private fun createOperation(operation: MediaHttpOperation, save: Boolean): OperationToken = synchronized(lock) {
        val rejection = if (closed) MediaHttpFailure.CLOSED else if (active.size >= 2) MediaHttpFailure.BUSY else null
        val token = OperationToken(operation, this, rejection, if (save) 120 else 15) { retired ->
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
        optional: Boolean = false, imageCap: Int? = null): MediaHttpResult {
        val linkedInPage = site == PageSite.LINKEDIN
        val facebookPage = site == PageSite.FACEBOOK
        val xPost = site == PageSite.X
        val instagramPage = site == PageSite.INSTAGRAM
        val telegramPage = site == PageSite.TELEGRAM
        val blueskyPage = site == PageSite.BLUESKY
        val browserPage = facebookPage || instagramPage || telegramPage
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
            var url = MediaHttpPolicy.parse(request.url) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
            if (linkedInPage && !MediaHttpPolicy.isLinkedInPageEntry(url))
                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
            if (linkedInPage) url = MediaHttpPolicy.normalizeLinkedInEntry(url)
            if (facebookPage && (request.purpose != MediaHttpPurpose.PAGE ||
                    token.operation != MediaHttpOperation.PAGES_AND_IMAGE ||
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
            requireHostRule(request, url)
            val entry = if (linkedInPage) MediaHttpPolicy.withoutQuery(url) else null
            validateContext(token, request, url)
            token.begin(request.purpose)
            entered = true
            val configured = configuredClient(token) ?: throw MediaHttpException(MediaHttpFailure.CLOSED)
            val seen = hashSetOf(url.toString())
            while (true) {
                if (linkedInPage && MediaHttpPolicy.isLinkedInPost(url)) landing = MediaHttpPolicy.withoutQuery(url)
                token.requireActive()
                token.budget.requireRequestRoom()
                val wireRequest = Request.Builder().url(url).get()
                    .header("User-Agent", if (browserPage) DESKTOP_BROWSER_AGENT else PUBLIC_USER_AGENT)
                    .header("Accept", if (browserPage) BROWSER_PAGE_ACCEPT else if (xPost || blueskyPage) JSON_ACCEPT
                        else if (request.purpose == MediaHttpPurpose.PAGE) PAGE_ACCEPT else IMAGE_ACCEPT)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        // Facebook answers 400 without Sec-Fetch-Mode and a data-less shell without text/html.
                        if (browserPage) BROWSER_NAVIGATION_HEADERS.forEach { (name, value) -> header(name, value) }
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
                            if (telegramPage) throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
                            if (xPost || blueskyPage) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (instagramPage) throw MediaHttpException(
                                if (MediaHttpPolicy.resolve(url, response.header("Location").orEmpty())
                                        ?.let(InstagramPostIdentity::isLoginWall) == true) MediaHttpFailure.LOGIN_REQUIRED
                                else MediaHttpFailure.INVALID_CONTEXT)
                            val located = MediaHttpPolicy.resolve(url, response.header("Location").orEmpty())
                                ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
                            val next = if (facebookPage) facebookHop(located, seen) else located
                            if (linkedInPage && !MediaHttpPolicy.allowsLinkedInPageHop(url, next))
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            requireHostRule(request, next)
                            if (request.pageContext != null && next.host != "media.licdn.com")
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (!seen.add(next.toString())) throw MediaHttpException(MediaHttpFailure.REDIRECT_LOOP)
                            token.budget.redirect()
                            url = next
                        } else {
                            if (response.code != 200) throw MediaHttpException(MediaHttpFailure.HTTP_STATUS, response.code)
                            if (linkedInPage && !MediaHttpPolicy.isLinkedInPost(url))
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (facebookPage && FacebookPostIdentity.parse(url.toString()).let { it == null || it.isShortlink })
                                throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
                            if (request.purpose == MediaHttpPurpose.IMAGE) imageMimeType = response.header("Content-Type")
                            val complete = readComplete(token, request.purpose, url, response, call,
                                if (facebookPage) MediaPageLimits.FACEBOOK else if (xPost) MediaPageLimits.X
                                else if (instagramPage) MediaPageLimits.INSTAGRAM
                                else if (telegramPage) MediaPageLimits.TELEGRAM else if (blueskyPage) MediaPageLimits.BLUESKY
                                else if (linkedInPage) MediaPageLimits.LINKEDIN
                                else imageCap?.let { minOf(it, token.budget.imageLimit()) })
                            try {
                                token.admit(complete)
                                token.requireActive()
                                if (linkedInPage) synchronized(lock) {
                                    token.id.terminal?.let { throw MediaHttpException(it) }
                                    linkedInPages[complete.target] = LinkedInPageAdmission(token.id, checkNotNull(entry),
                                        MediaHttpPolicy.withoutQuery(url))
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
        }
        if (!allowed) throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
    }

    /** Normalised next Facebook page, the once-unwrapped login next=, or a typed refusal. */
    private fun facebookHop(next: HttpUrl, seen: Set<String>): HttpUrl {
        if (FacebookPostIdentity.isLoginWall(next)) {
            val target = next.queryParameterValues("next").singleOrNull()?.let(FacebookPostIdentity::parse)
                ?.takeUnless { it.isShortlink || it.fetchUrl in seen } ?: throw MediaHttpException(MediaHttpFailure.LOGIN_REQUIRED)
            return MediaHttpPolicy.parse(target.fetchUrl) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
        }
        // A shortlink (share or fb.watch) resolves to a post; it never resolves to another shortlink.
        val identity = FacebookPostIdentity.parse(next.toString())?.takeUnless { it.isShortlink }
            ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        return MediaHttpPolicy.parse(identity.fetchUrl) ?: throw MediaHttpException(MediaHttpFailure.INVALID_URL)
    }

    private fun validateContext(token: OperationToken, request: PublicMediaRequest, url: HttpUrl) {
        val context = request.pageContext ?: return
        if (context.operationId !== token.id || context.purpose != MediaHttpPurpose.PAGE ||
            request.purpose != MediaHttpPurpose.IMAGE || url.host != "media.licdn.com")
            throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
        val page = MediaHttpPolicy.parse(context.url()) ?: throw MediaHttpException(MediaHttpFailure.INVALID_CONTEXT)
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
            body.byteStream().use { stream ->
                try {
                    while (true) {
                        token.requireActive()
                        if (count == limit) {
                            if (stream.read() != -1) {
                                token.budget.consumed(1, purpose == MediaHttpPurpose.IMAGE)
                                throw MediaHttpException(if (count == cap) MediaHttpFailure.BODY_LIMIT else MediaHttpFailure.PARTIAL_BODY)
                            }
                            break
                        }
                        if (count == buffer.size) buffer = buffer.copyOf(minOf(cap, buffer.size * 2))
                        val read = stream.read(buffer, count, minOf(8192, buffer.size - count))
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
                    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                    .proxy(Proxy.NO_PROXY)
                    .dns(PublicAddressDns(token, environment.rawResolver, resolverExecutor))
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
