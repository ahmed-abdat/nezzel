package dev.ahmed.savlino.engine.mediahttp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic addresses are never contacted: the connector maps these exact bytes to loopback. */
internal val PUBLIC_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
internal val SECOND_PUBLIC_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
internal fun address(text: String): InetAddress = InetAddress.getByName(text)
internal fun await(latch: CountDownLatch, message: String, seconds: Long = 3) {
    assertTrue(latch.await(seconds, TimeUnit.SECONDS), message)
}

/** Runtime-generated, local-only certificate. No keystore or new dependency is checked in. */
internal class MediaHttpTlsIdentity : Closeable {
    private val directory = Files.createTempDirectory("media-http-tls-").toFile()
    private val storeFile = File(directory, "fixture.p12")
    val trustManager: X509TrustManager
    val context: SSLContext

    init {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        check(keytool.isFile) { "Installed JDK keytool required" }
        val output = File(directory, "keytool-output.txt")
        val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "fixture",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", storeFile.absolutePath, "-storepass", "fixture-password",
            "-keypass", "fixture-password", "-dname", "CN=media.test", "-noprompt",
            "-ext", "SAN=dns:media.test,dns:other.test,dns:www.youtube.com,dns:www.linkedin.com,dns:media.licdn.com,dns:lnkd.in,dns:www.facebook.com,dns:web.facebook.com,dns:fb.watch,dns:scontent.example.fbcdn.net,dns:z-p3-scontent.example.fbcdn.net,dns:external.example.fbcdn.net,dns:cdn.syndication.twimg.com,dns:pbs.twimg.com,dns:video.twimg.com,dns:www.instagram.com,dns:instagram.com,dns:instagram.fxyz1-1.fna.fbcdn.net,dns:scontent-xyz1-1.cdninstagram.com,dns:scontent.example.cdninstagram.com,dns:t.me,dns:telegram.me,dns:cdn1.telesco.pe,dns:public.api.bsky.app,dns:cdn.bsky.app,dns:tinyurl.com,dns:bit.ly,dns:t.co,dns:pin.it,dns:api.pinterest.com,dns:ow.ly,dns:www.tiktok.com,dns:vm.tiktok.com,dns:vt.tiktok.com,dns:p16-sign.example.tiktokcdn.com,dns:p19-sign.example.tiktokcdn-us.com,dns:www.reddit.com,dns:redd.it,dns:i.redd.it,dns:preview.redd.it,dns:www.pinterest.com,dns:i.pinimg.com")
            .redirectErrorStream(true).redirectOutput(output).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            directory.deleteRecursively()
            error("Installed keytool timed out")
        }
        check(process.exitValue() == 0) { "Installed keytool failed" }
        val store = KeyStore.getInstance("PKCS12").apply {
            storeFile.inputStream().use { load(it, "fixture-password".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-password".toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trustManager), null) }
    }

    override fun close() { directory.deleteRecursively() }
}

internal data class RecordedMediaRequest(val target: String, val headers: Map<String, String>) {
    override fun toString() = "RecordedMediaRequest(redacted)"
}

/** Real HTTP/1.1 over JDK TLS. Each response closes its connection for deterministic close evidence,
 *  unless [keepAlive] serves further requests on the same socket. */
internal class MediaHttpServer(private val identity: MediaHttpTlsIdentity, private val keepAlive: Boolean = false,
    handlerThreads: Int = 2,
    private val respond: (RecordedMediaRequest, SSLSocket) -> Unit = { _, socket -> socket.reply() }) : Closeable {
    private val listener = identity.context.serverSocketFactory.createServerSocket(0, 8,
        InetAddress.getLoopbackAddress()) as SSLServerSocket
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val handlers = Executors.newFixedThreadPool(handlerThreads)
    private val accepting = Executors.newSingleThreadExecutor()
    private val stopped = CountDownLatch(1)
    val accepted = AtomicInteger()
    val completed = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedMediaRequest>()
    val handlerFailures = CopyOnWriteArrayList<String>()
    val acceptedOne = CountDownLatch(1)
    val requestOne = CountDownLatch(1)
    val port: Int get() = listener.localPort

    init {
        accepting.execute {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept() as SSLSocket
                    socket.soTimeout = 20_000
                    sockets += socket
                    accepted.incrementAndGet()
                    acceptedOne.countDown()
                    handlers.execute {
                        try {
                            socket.use {
                                val input = it.inputStream
                                do {
                                    val first = input.readHttpLine() ?: break
                                    val headers = linkedMapOf<String, String>()
                                    while (true) {
                                        val line = input.readHttpLine() ?: break
                                        if (line.isEmpty()) break
                                        val split = line.indexOf(':')
                                        if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                                    }
                                    val target = first.substringAfter(' ').substringBefore(' ')
                                    val request = RecordedMediaRequest(target, headers)
                                    requests += request
                                    requestOne.countDown()
                                    respond(request, socket)
                                } while (keepAlive)
                            }
                        } catch (_: java.io.IOException) {
                            // Expected for TLS rejection, cancellation, read limits and closed fixture sockets.
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (failure: Exception) {
                            handlerFailures += failure.javaClass.simpleName // Never capture request/exception text.
                        } finally {
                            completed.incrementAndGet()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Listener closed by fixture owner.
            } finally { stopped.countDown() }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        accepting.shutdownNow()
        handlers.shutdownNow()
        assertTrue(stopped.await(3, TimeUnit.SECONDS), "fixture accept thread stopped")
        assertTrue(accepting.awaitTermination(3, TimeUnit.SECONDS), "fixture accept executor stopped")
        assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS), "fixture response executor stopped")
        assertTrue(handlerFailures.isEmpty(), "fixture handler failed")
    }
}

private fun InputStream.readHttpLine(): String? {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < 16_384) {
        val value = read()
        if (value == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
        if (value == 10) return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
        bytes.write(value)
    }
    throw java.io.IOException("fixture header limit")
}

internal fun SSLSocket.reply(status: Int = 200, body: ByteArray = byteArrayOf(10, 20, 30),
    headers: Map<String, String> = emptyMap(), chunked: Boolean = false, declared: Long? = body.size.toLong()) {
    val output = outputStream
    val all = linkedMapOf("Content-Type" to "image/jpeg", "Connection" to "close")
    if (chunked) all["Transfer-Encoding"] = "chunked"
    else if (declared != null) all["Content-Length"] = declared.toString()
    all.putAll(headers)
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(" Fixture\r\n")
        all.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.ISO_8859_1))
    if (chunked) {
        body.asList().chunked(8192).forEach { chunk ->
            output.write("${chunk.size.toString(16)}\r\n".toByteArray())
            output.write(chunk.toByteArray())
            output.write("\r\n".toByteArray())
        }
        output.write("0\r\n\r\n".toByteArray())
    } else output.write(body)
    output.flush()
}

internal class MediaHttpHarness(val identity: MediaHttpTlsIdentity,
    val server: MediaHttpServer,
    resolver: (String) -> List<InetAddress> = { listOf(PUBLIC_V4) },
    mapped: Set<InetAddress> = setOf(PUBLIC_V4, SECOND_PUBLIC_V4)) : Closeable {
    val dnsCalls = AtomicInteger()
    val connects = AtomicInteger()
    val connectedAddresses = CopyOnWriteArrayList<InetAddress>()
    val environment = MediaHttpEnvironment(
        rawResolver = { host -> dnsCalls.incrementAndGet(); resolver(host) },
        routeMapper = { endpoint ->
            // This is BELOW both production guards. A missed policy guard cannot cause real egress.
            if (endpoint.address !in mapped) throw java.io.IOException("unmapped fixture address")
            connects.incrementAndGet()
            connectedAddresses += endpoint.address
            InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)
        }, trustManager = identity.trustManager)
    val client: MediaHttpClient = MediaHttpClient.forTesting(environment)

    fun control(purpose: MediaHttpPurpose = MediaHttpPurpose.IMAGE) {
        client.newOperation(if (purpose == MediaHttpPurpose.PAGE) MediaHttpOperation.PAGE_AND_IMAGE
            else MediaHttpOperation.DIRECT_IMAGE).use { token ->
            val before = server.requests.size
            val result = client.fetch(token, PublicMediaRequest("https://media.test/control", purpose))
            assertTrue(result is MediaHttpResult.Success, "paired public control must succeed")
            result.response.use { assertTrue(it.takeBody().length > 0, "control body consumed") }
            assertEquals(null, token.checkActive(), "successful operation remains usable until retired")
            assertEquals(before + 1, server.requests.size, "control made one real request")
        }
    }

    override fun close() { client.close() }
}

internal class MediaHttpFetchTask(client: MediaHttpClient, token: OperationToken,
    request: PublicMediaRequest) : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val worker = AtomicReference<Thread>()
    val interruptRestored = AtomicBoolean()
    val result: Future<MediaHttpResult> = executor.submit<MediaHttpResult> {
        worker.set(Thread.currentThread())
        try { client.fetch(token, request) }
        finally { interruptRestored.set(Thread.currentThread().isInterrupted) }
    }
    fun interruptWorker() { checkNotNull(worker.get()).interrupt() }
    fun get(seconds: Long = 3): MediaHttpResult = result.get(seconds, TimeUnit.SECONDS)
    override fun close() {
        result.cancel(true)
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "fetch thread released")
    }
}

internal fun assertFailure(result: MediaHttpResult, reason: MediaHttpFailure, status: Int? = null) {
    assertTrue(result is MediaHttpResult.Failure, "expected typed transport failure")
    assertEquals(reason, result.reason)
    if (status != null) assertEquals(status, result.statusCode)
}

internal fun assertSuccess(result: MediaHttpResult, bytes: ByteArray? = null): MediaHttpResponse {
    assertTrue(result is MediaHttpResult.Success, "expected completed transport success")
    val response = result.response
    if (bytes != null) {
        val body = response.takeBody()
        assertEquals(bytes.size, body.length)
        assertTrue(body.bytes.copyOf(body.length).contentEquals(bytes), "complete response bytes")
    }
    assertTrue(response.measuredBytes >= 0, "measured length is nonnegative")
    return response
}
