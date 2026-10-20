package dev.ahmed.savlino.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal open class UpdateHttp(private val version: String) : Closeable {
    private val clientState = lazy { OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp can retry 503/Retry-After:0 even with connection retries off.
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }.build() }
    private val client get() = clientState.value
    @Volatile private var active: Call? = null
    @Volatile private var closed = false
    private fun response(initial: String, json: Boolean, metadata: Boolean = false): Response {
        var url = initial
        repeat(6) { hop ->
            if (closed || !UpdatePolicy.allowed(url)) throw IOException()
            val request = Request.Builder().url(url.toHttpUrl()).header("User-Agent", "Nezzel/$version")
                .header("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
                .header("Accept-Encoding", "identity").build()
            val call = client.newCall(request)
            if (metadata) call.timeout().timeout(15, TimeUnit.SECONDS)
            active = call
            if (closed) call.cancel()
            val response = call.execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { UpdatePolicy.redirect(url, it) }
                call.cancel(); response.close()
                url = next?.takeIf { hop < 5 } ?: throw IOException()
            } else {
                if (response.code != 200 || response.header("Content-Encoding")?.let { it != "identity" } == true) {
                    call.cancel(); response.close(); throw IOException()
                }
                return response
            }
        }
        throw IOException()
    }
    open suspend fun manifest(): ByteArray = metadata(Release.MANIFEST, false, Release.MANIFEST_CAP)
    open suspend fun latest(): ByteArray = metadata(Release.API, true, Release.BODY_CAP)
    /** One preferred asset attempt, then one API fallback; cancellation never starts another request. */
    suspend fun latestRelease(): Triple<Release, ByteArray, String> {
        try {
            val bytes = manifest()
            val release = Release.parseManifest(bytes) ?: throw IOException()
            return Triple(release, bytes, "manifest")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* An older release may not have the asset; try the API once. */ }
        currentCoroutineContext().ensureActive()
        val bytes = latest()
        return Triple(Release.parse(bytes) ?: throw IOException(), bytes, "api")
    }
    private suspend fun metadata(url: String, json: Boolean, cap: Int): ByteArray = response(url, json, metadata = true).use { response ->
        val length = response.body.contentLength()
        if (length > cap) { active?.cancel(); throw IOException() }
        val out = ByteArrayOutputStream()
        read(response, cap.toLong()) { bytes, count -> out.write(bytes, 0, count) }
        out.toByteArray()
    }
    open suspend fun download(release: Release, file: File, progress: (Long) -> Unit) {
        response(release.url, false).use { response ->
            val length = response.body.contentLength()
            if (length >= 0 && length != release.size) { active?.cancel(); throw IOException() }
            file.outputStream().use { out ->
                var copied = 0L
                read(response, release.size) { bytes, count -> out.write(bytes, 0, count); copied += count; progress(copied) }
                if (copied != release.size) throw IOException()
                out.fd.sync()
            }
        }
    }
    private suspend fun read(response: Response, limit: Long, consume: (ByteArray, Int) -> Unit) {
        var total = 0L
        val buffer = ByteArray(8192)
        try {
            response.body.byteStream().use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
                    if (count < 0) break
                    if (count.toLong() > limit - total) throw IOException()
                    total += count; consume(buffer, count)
                }
            }
        } catch (failure: Exception) { active?.cancel(); throw failure }
    }
    open override fun close() {
        closed = true; active?.cancel()
        if (clientState.isInitialized()) {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
