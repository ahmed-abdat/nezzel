package dev.ahmed.savlino.engine.youtube

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal class NpeDownloader(client: OkHttpClient) : Downloader() {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val operations = mutableMapOf<Any, Token>()
    private val current = ThreadLocal<Token>()

    private class Token(timeoutMs: Long) {
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var cancelled = false
        val calls = mutableSetOf<Call>()
        var requests = 0
        var responses = 0
        var offline = false
        var unreachable = false
        fun remaining() = budget - (System.nanoTime() - started)
        fun check() { if (cancelled || remaining() <= 0) throw IOException("Extraction stopped") }
        /** Exception classes only; nothing about the failure is kept. */
        fun failed(error: Throwable) {
            generateSequence(error) { it.cause }.take(8).forEach {
                if (it is UnknownHostException) offline = true
                if (it is ConnectException || it is NoRouteToHostException || it is InterruptedIOException) unreachable = true
            }
        }
        /** Null once any response arrived (the network works) or after cancellation; true = no DNS. */
        fun offline(): Boolean? = when {
            cancelled || responses > 0 || requests == 0 -> null
            offline -> true
            unreachable || remaining() <= 0 -> false
            else -> null
        }
    }

    companion object {
        private val production by lazy { NpeDownloader(OkHttpClient()) }
        fun create(): NpeDownloader = production
        private const val MAX_BODY = 16L * 1024 * 1024
    }

    fun install() {
        synchronized(NewPipe::class.java) {
            if (NewPipe.getDownloader() == null) NewPipe.init(this)
        }
    }

    /** [unreachable] receives the run's verdict when no request got any response (see [Token.offline]). */
    fun <T> operation(id: Any, callTimeoutMs: Long, unreachable: (offline: Boolean) -> Unit = {}, block: () -> T): T {
        if (callTimeoutMs <= 0) throw IOException("Extraction stopped")
        val token = Token(callTimeoutMs)
        synchronized(lock) {
            if (operations.containsKey(id)) throw IOException("Extraction already active")
            operations[id] = token
        }
        val previous = current.get()
        current.set(token)
        try { return block() }
        finally {
            if (previous == null) current.remove() else current.set(previous)
            synchronized(lock) { operations.remove(id); token.offline() }?.let(unreachable)
        }
    }

    fun cancel(id: Any) {
        val calls = synchronized(lock) {
            val token = operations[id] ?: return
            token.cancelled = true
            token.calls.toList()
        }
        calls.forEach { it.cancel() }
    }

    override fun execute(request: Request): Response {
        val token = current.get()
        var call: Call? = null
        try {
            synchronized(lock) { token?.check() }
            val url = request.url().toHttpUrlOrNull() ?: throw IOException("Extraction request rejected")
            val host = url.host
            if (!url.isHttps || !(host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtubei.googleapis.com" || host.endsWith(".googlevideo.com")))
                throw IOException("Extraction request rejected")
            val builder = okhttp3.Request.Builder().url(url).header("User-Agent", NPE_BROWSER_USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            builder.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            val active = client.newCall(builder.build())
            call = active
            synchronized(lock) {
                token?.check()
                if (token != null) {
                    active.timeout().timeout(token.remaining().coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    token.calls.add(active)
                    token.requests++
                }
            }
            val received = try { active.execute() }
                catch (error: IOException) { synchronized(lock) { token?.failed(error) }; throw error }
            synchronized(lock) { if (token != null) token.responses++ }
            received.use { response ->
                if (response.code in 300..399) throw IOException("Extraction redirect rejected")
                val body = response.body
                if (body.contentLength() > MAX_BODY) throw IOException("Extraction response too large")
                val source = body.source()
                source.request(MAX_BODY + 1)
                if (source.buffer.size > MAX_BODY) throw IOException("Extraction response too large")
                val text = body.string()
                synchronized(lock) { token?.check() }
                if (active.isCanceled()) throw IOException("Extraction stopped")
                return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
            }
        } catch (_: IOException) {
            throw IOException("Extraction request failed")
        } catch (_: IllegalArgumentException) {
            throw IOException("Extraction request rejected")
        } finally {
            synchronized(lock) { call?.let { token?.calls?.remove(it) } }
        }
    }
}
