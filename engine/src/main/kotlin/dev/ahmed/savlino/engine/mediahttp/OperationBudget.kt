package dev.ahmed.savlino.engine.mediahttp

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Identity and its terminal state survive retirement without an evictable cancellation tombstone. */
class OperationId internal constructor(internal val owner: Any) {
    @Volatile internal var terminal: MediaHttpFailure? = null
    override fun toString() = "OperationId(opaque)"
}

class OperationBudget internal constructor(private val operation: MediaHttpOperation) {
    private var requests = 0
    private var redirects = 0
    private var bytes = 0L
    private var imageBytes = 0L
    private var created = 0
    private var closed = 0

    @Synchronized internal fun requireRequestRoom() {
        val limit = when (operation) {
            MediaHttpOperation.DIRECT_IMAGE -> 4
            MediaHttpOperation.PAGE_AND_IMAGE -> 5
            MediaHttpOperation.PAGES_AND_IMAGE -> 7
            MediaHttpOperation.PAGE_AND_CAROUSEL, MediaHttpOperation.PAGES_AND_CAROUSEL -> 24
        }
        if (requests >= limit)
            throw MediaHttpException(MediaHttpFailure.REQUEST_LIMIT)
    }
    @Synchronized internal fun request() { requireRequestRoom(); requests++ }
    @Synchronized internal fun redirect() {
        if (redirects >= 3) throw MediaHttpException(MediaHttpFailure.REDIRECT_LIMIT)
        redirects++
    }
    @Synchronized internal fun consumed(count: Int, image: Boolean = false) {
        bytes += count
        if (image) imageBytes += count
    }
    /** Same 2 MiB image inspection budget, shared across every carousel body (including failed bodies). */
    @Synchronized internal fun imageLimit(thumbnail: Boolean = false): Int {
        val remaining = if (operation.isCarousel)
            (2 * 1024 * 1024 - imageBytes).coerceAtLeast(0).toInt() else 2 * 1024 * 1024
        return minOf(remaining, if (thumbnail) 192 * 1024 else 2 * 1024 * 1024)
    }
    @Synchronized internal fun socketCreated() { created++ }
    @Synchronized internal fun socketClosed() { closed++ }
    @Synchronized internal fun snapshot() = MediaHttpCounters(requests, redirects, bytes, created, closed)
    override fun toString() = "OperationBudget(redacted)"
}

internal data class MediaHttpCounters(val requests: Int, val redirects: Int, val consumedBytes: Long,
    val socketsCreated: Int, val socketsClosed: Int)

internal class MediaHttpException(val reason: MediaHttpFailure, val statusCode: Int? = null) :
    IOException("media_http_${reason.name.lowercase()}")

/** Blocking fetches must run off the UI thread. The same token spans page, asset and consumer work. */
class OperationToken internal constructor(val operation: MediaHttpOperation, internal val owner: Any,
    initial: MediaHttpFailure? = null, private val deadlineSeconds: Long = 15,
    private val retired: (OperationToken) -> Unit) : Closeable {
    val id = OperationId(owner)
    val budget = OperationBudget(operation)
    private val lock = Any()
    private val started = System.nanoTime()
    private val cleanups = linkedMapOf<Any, () -> Unit>()
    private var fetching = false
    private var pageClaims = 0
    private var imageClaims = 0
    private var client: OkHttpClient? = null

    init { id.terminal = initial }

    fun checkActive(): MediaHttpFailure? {
        if (id.terminal == null && remainingNanos() <= 0) stop(MediaHttpFailure.TIMEOUT)
        return id.terminal
    }
    internal fun requireActive() { checkActive()?.let { throw MediaHttpException(it) } }
    internal fun remainingNanos(): Long = TimeUnit.SECONDS.toNanos(deadlineSeconds) - (System.nanoTime() - started)

    /** Returns a removal callback. Registration and terminal transition share this lock. */
    internal fun register(key: Any, cleanup: () -> Unit): () -> Unit {
        val rejected = synchronized(lock) {
            if (id.terminal != null) true else { cleanups[key] = cleanup; false }
        }
        if (rejected) cleanup()
        return { synchronized(lock) { cleanups.remove(key) }; Unit }
    }

    internal fun begin(purpose: MediaHttpPurpose) = synchronized(lock) {
        requireActive()
        if (fetching) throw MediaHttpException(MediaHttpFailure.BUSY)
        when (purpose) {
            MediaHttpPurpose.PAGE -> {
                val pages = when (operation) {
                    MediaHttpOperation.DIRECT_IMAGE -> 0
                    MediaHttpOperation.PAGE_AND_IMAGE, MediaHttpOperation.PAGE_AND_CAROUSEL -> 1
                    MediaHttpOperation.PAGES_AND_IMAGE, MediaHttpOperation.PAGES_AND_CAROUSEL -> 2
                }
                if (pageClaims >= pages || imageClaims > 0) throw MediaHttpException(MediaHttpFailure.RESOURCE_LIMIT)
                pageClaims++
            }
            MediaHttpPurpose.IMAGE -> {
                if (imageClaims >= if (operation.isCarousel) 20 else 1)
                    throw MediaHttpException(MediaHttpFailure.RESOURCE_LIMIT)
                imageClaims++
            }
        }
        fetching = true
    }
    internal fun endFetch() { synchronized(lock) { fetching = false } }

    internal fun client(factory: () -> OkHttpClient): OkHttpClient = synchronized(lock) {
        requireActive()
        client ?: factory().also { made ->
            client = made
            register(made) {
                made.dispatcher.cancelAll()
                made.connectionPool.evictAll()
                made.dispatcher.executorService.shutdownNow()
            }
        }
    }

    /** The response is registered before admission, so cancellation also disposes completed late bodies. */
    internal fun admit(response: MediaHttpResponse): MediaHttpResponse = synchronized(lock) {
        requireActive()
        response.onClose = register(response) { response.close() }
        response
    }

    internal fun stop(reason: MediaHttpFailure) {
        val actions = synchronized(lock) {
            if (id.terminal != null) return
            id.terminal = reason
            cleanups.values.toList().also { cleanups.clear() }
        }
        // Cancellation must not wait for a synchronous OS DNS call to finish.
        actions.forEach { action -> runCatching { action() } }
        retired(this)
    }
    override fun close() { stop(MediaHttpFailure.CLOSED) }
    override fun toString() = "OperationToken(opaque)"
}
