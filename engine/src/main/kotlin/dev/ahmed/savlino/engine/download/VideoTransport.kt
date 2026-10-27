package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.engine.mediahttp.OperationToken
import dev.ahmed.savlino.engine.mediahttp.MediaHttpOperation
import dev.ahmed.savlino.engine.mediahttp.PublicAddressDns
import dev.ahmed.savlino.engine.mediahttp.MediaHttpException
import dev.ahmed.savlino.engine.mediahttp.MediaHttpFailure
import dev.ahmed.savlino.engine.mediahttp.VideoCdnPolicy
import java.net.Proxy
import java.net.InetAddress
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.*

/** One job owns every call and its pool. Redirects cannot broaden the provider or leak cookies. */
internal class VideoTransport(private val site: Host,
    private val permits: (String) -> HttpUrl? = { VideoCdnPolicy.allows(site, it) },
    factory: Call.Factory? = null,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) : DirectTransport {
    private val dnsToken = OperationToken(MediaHttpOperation.DIRECT_IMAGE, Any(), deadlineSeconds = 180) {}
    private val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
    private val client = if (factory == null) OkHttpClient.Builder()
        .connectionPool(pool).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .dns(PublicAddressDns(dnsToken, resolver, resolvers, resolverSlots))
        .protocols(listOf(Protocol.HTTP_1_1)).connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .fastFallback(false).build() else null
    private val calls: Call.Factory = factory ?: requireNotNull(client)
    private val lock = Any()
    private var stopped = false
    private var active: Call? = null
    override fun execute(url: String, headers: Map<String, String>): DirectResponse {
        var target = permits(url) ?: throw DirectPolicyRejected()
        val cookieHost = target.host
        repeat(4) { hop ->
            val builder = Request.Builder().url(target).get()
            headers.forEach { (name, value) ->
                if (target.host == cookieHost || name.lowercase() !in setOf("cookie", "cookie2", "authorization", "proxy-authorization"))
                    builder.header(name, value)
            }
            val call = synchronized(lock) {
                if (stopped) throw java.io.IOException("Transfer cancelled")
                calls.newCall(builder.build()).also { active = it }
            }
            val response = try { call.execute() } catch (error: Exception) {
                if (generateSequence<Throwable>(error) { it.cause }.take(16).any {
                        it is DirectPolicyRejected || it is MediaHttpException && it.reason == MediaHttpFailure.ADDRESS_REJECTED
                    }) throw DirectPolicyRejected()
                throw java.io.IOException("Transfer interrupted")
            }
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { HttpsUrl.resolve(target, it) }?.let { permits(it.toString()) }
                call.cancel(); response.close()
                if (next == null || hop >= 3) throw DirectPolicyRejected()
                target = next
            } else return object : DirectResponse {
                override val code get() = response.code
                override fun header(name: String) = response.header(name)
                override val body = response.body.byteStream()
                override fun close() { call.cancel(); response.close() }
            }
        }
        throw DirectPolicyRejected()
    }
    override fun cancel() {
        synchronized(lock) { stopped = true; active?.cancel() }
        dnsToken.close() // Interrupt the caller's DNS wait even when the OS resolver ignores interruption.
    }
    override fun close() { cancel(); pool.evictAll(); client?.dispatcher?.executorService?.shutdown() }
    private companion object {
        val resolverSlots = Semaphore(4)
        val resolvers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { work -> Thread(work, "video-dns").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
