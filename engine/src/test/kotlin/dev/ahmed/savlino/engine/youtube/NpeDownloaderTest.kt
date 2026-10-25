package dev.ahmed.savlino.engine.youtube

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.schabi.newpipe.extractor.downloader.Request as NpeRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class NpeDownloaderTest {
    private fun request(url: String = "https://www.youtube.com/fixture") = NpeRequest.newBuilder().get(url).build()
    private fun response(chain: Interceptor.Chain, code: Int = 200, body: ResponseBody = "fixture".toResponseBody()) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .header("Content-Type", "text/plain").body(body).build()
    private fun client(block: (Interceptor.Chain) -> Response) = OkHttpClient.Builder().addInterceptor(block).build()

    @Test fun allowlistedHostsPassAndResponseFieldsArePreserved() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(client { calls.incrementAndGet(); response(it).newBuilder().addHeader("X-Fixture", "yes").build() })
        for (host in listOf("youtube.com", "www.youtube.com", "music.youtube.com", "youtubei.googleapis.com", "rr1---sn-test.googlevideo.com")) {
            val url = "https://$host/fixture"
            val result = downloader.execute(request(url))
            assertEquals(200, result.responseCode()); assertEquals("Fixture", result.responseMessage())
            assertEquals("fixture", result.responseBody()); assertEquals("yes", result.getHeader("X-Fixture"))
            assertEquals(url, result.latestUrl())
        }
        assertEquals(5, calls.get())
    }

    @Test fun rejectedSchemesAndLookalikeHostsMakeZeroCalls() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(client { calls.incrementAndGet(); response(it) })
        for (url in listOf("http://youtube.com/fixture", "https://other.test/fixture", "https://youtube.com.evil.test/fixture",
            "https://evilyoutube.com/fixture", "https://googlevideo.com/fixture", "https://youtubei.googleapis.com.evil.test/fixture")) {
            val failure = assertFailsWith<IOException> { downloader.execute(request(url)) }
            assertPrivate(failure)
        }
        assertEquals(0, calls.get())
    }

    @Test fun redirectsFailWithoutASecondRequest() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(client {
            calls.incrementAndGet(); response(it, 302).newBuilder().header("Location", "https://www.youtube.com/next").build() })
        assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
        assertEquals(1, calls.get())
    }

    @Test fun derivedClientUsesNoCookieJar() {
        val jarCalls = AtomicInteger()
        val supplied = client { chain ->
            assertNull(chain.request().header("Cookie"))
            assertEquals(10_000, chain.connectTimeoutMillis())
            assertEquals(10_000, chain.readTimeoutMillis())
            response(chain)
        }.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                jarCalls.incrementAndGet()
                return listOf(Cookie.Builder().name("fixture").value("private").domain("youtube.com").build())
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { jarCalls.incrementAndGet() }
        }).followRedirects(true).followSslRedirects(true).build()
        NpeDownloader(supplied).execute(request())
        assertEquals(0, jarCalls.get())
    }

    @Test fun methodBodyAndExplicitHeadersReachTheCallerThread() {
        val caller = Thread.currentThread()
        val downloader = NpeDownloader(client { chain ->
            assertSame(caller, Thread.currentThread())
            assertEquals("POST", chain.request().method)
            assertEquals("fixture-agent", chain.request().header("User-Agent"))
            assertEquals(listOf("one", "two"), chain.request().headers.values("X-Fixture"))
            val bytes = Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()
            assertEquals("fixture-body", bytes)
            response(chain)
        })
        downloader.execute(NpeRequest.newBuilder().post("https://youtubei.googleapis.com/fixture", "fixture-body".toByteArray())
            .setHeader("User-Agent", "fixture-agent").setHeaders("X-Fixture", listOf("one", "two")).build())
    }

    @Test fun declaredAndStreamingBodiesOverCapAreRejectedAndClosed() {
        for (declared in listOf(-1L, 16_777_217L)) {
            val closed = CountDownLatch(1)
            var consumed = 0L
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = minOf(byteCount, 8192L)
                    sink.write(ByteArray(count.toInt()))
                    consumed += count
                    return count
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.countDown() }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = declared
                override fun source(): BufferedSource = source
            }
            val downloader = NpeDownloader(client { response(it, body = body) })
            assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(consumed <= 16_777_216L + 8192)
            if (declared > 0) assertEquals(0L, consumed)
        }
    }

    @Test fun bodyExactlyAtCapIsAccepted() {
        val downloader = NpeDownloader(client { response(it, body = ByteArray(16_777_216).toResponseBody()) })
        assertEquals(16_777_216, downloader.execute(request()).responseBody().length)
    }

    @Test fun cancelAbortsRegisteredCallAndLaterRequestsFailFast() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val captured = AtomicReference<Call>()
        val downloader = NpeDownloader(client { chain ->
            calls.incrementAndGet(); captured.set(chain.call()); entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            response(chain)
        })
        val executor = Executors.newSingleThreadExecutor()
        val id = Any()
        try {
            val result = executor.submit<Boolean> {
                downloader.operation(id, 5000) {
                    assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
                    assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
                    true
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            downloader.cancel(id)
            assertTrue(captured.get().isCanceled())
            release.countDown()
            assertTrue(result.get(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            downloader.operation(Any(), 5000) { downloader.execute(request()) }
            assertEquals(2, calls.get())
        } finally {
            release.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun operationTimeoutBoundsCallsAndSubsequentRequests() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(client { chain ->
            calls.incrementAndGet()
            assertTrue(chain.call().timeout().timeoutNanos() in 1..TimeUnit.SECONDS.toNanos(1))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            val wait = CountDownLatch(1)
            while (!chain.call().isCanceled() && System.nanoTime() < until) wait.await(10, TimeUnit.MILLISECONDS)
            assertTrue(chain.call().isCanceled())
            response(chain)
        })
        downloader.operation(Any(), 1000) {
            assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
            assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
        }
        assertEquals(1, calls.get())
    }

    @Test fun transportAndInvalidRequestExceptionsExposeNoPrivateTextOrCause() {
        val downloader = NpeDownloader(client { throw IOException("https://www.youtube.com/fixture?private=body") })
        assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
        assertPrivate(assertFailsWith<IOException> { downloader.execute(request("not a URL")) })
    }

    @Test fun unreachableCallbackNeedsNoOfflineArgument() {
        for (error in listOf(java.net.UnknownHostException(), java.net.ConnectException(),
            java.net.NoRouteToHostException(), java.io.InterruptedIOException())) {
            val downloader = NpeDownloader(client { throw error })
            var notifications = 0
            val unreachable: () -> Unit = { notifications++ }
            downloader.operation(Any(), 5000, unreachable) {
                assertPrivate(assertFailsWith<IOException> { downloader.execute(request()) })
            }
            assertEquals(1, notifications)
        }
    }

    private fun assertPrivate(failure: IOException) {
        val text = failure.toString()
        for (secret in listOf("http", "youtube", "googlevideo", "fixture", "private", "body", "evil"))
            assertFalse(text.contains(secret, ignoreCase = true), text)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
