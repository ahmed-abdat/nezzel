package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test
import kotlin.test.*

class VideoTransportTest {
    private class Calls(private val answer: (Request) -> Response) : Call.Factory {
        private val delegate = OkHttpClient()
        val requests = mutableListOf<Request>()
        val cancelled = mutableListOf<Request>()
        override fun newCall(request: Request): Call = object : Call by delegate.newCall(request) {
            var executed = false
            var stopped = false
            override fun request() = request
            override fun execute(): Response { executed = true; requests += request; return answer(request) }
            override fun enqueue(responseCallback: Callback) = error("synchronous only")
            override fun cancel() { stopped = true; cancelled += request }
            override fun isExecuted() = executed
            override fun isCanceled() = stopped
            override fun timeout() = Timeout.NONE
            override fun clone() = newCall(request)
        }
    }
    private fun response(request: Request, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(if (location == null) 200 else 302).message("example")
        .apply { if (location != null) header("Location", location) }.body("abc".toResponseBody()).build()

    @Test fun crossHostRedirectStripsCookiesAndAuthorizationAndCallIsJobOwned() {
        val calls = Calls { request -> response(request, if (request.url.host == "example.com") "https://example.org/video.mp4" else null) }
        val wire = VideoTransport(Host.X, MediaHttpPolicy::parse, calls)
        wire.execute("https://example.com/video.mp4", mapOf("Cookie" to "secret", "Authorization" to "secret", "User-Agent" to "agent")).use {
            assertEquals("abc", it.body.bufferedReader().readText())
        }
        assertEquals(2, calls.requests.size)
        assertEquals("secret", calls.requests[0].header("Cookie"))
        assertNull(calls.requests[1].header("Cookie")); assertNull(calls.requests[1].header("Authorization"))
        assertEquals("agent", calls.requests[1].header("User-Agent"))
        wire.close()
        assertTrue(calls.cancelled.containsAll(calls.requests))
    }
    @Test fun redirectsCannotEscapeAllowlistOrExceedThreeHops() {
        for (escape in listOf(false, true)) {
            val calls = Calls { response(it, "https://example.org/video.mp4") }
            val wire = VideoTransport(Host.X, { raw -> MediaHttpPolicy.parse(raw)?.takeIf { !escape || it.host == "example.com" } }, calls)
            val failure = assertFailsWith<java.io.IOException> {
                wire.execute("https://example.com/video.mp4", emptyMap())
            }
            assertEquals(if (escape) 1 else 4, calls.requests.size)
            assertFalse(failure.toString().contains("example."))
            wire.close()
        }
    }
    @Test fun dnsRejectionAndCancelledDnsNeverOpenASocketOrExposeHost() {
        val rejected = VideoTransport(Host.X, MediaHttpPolicy::parse, resolver = {
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        })
        try {
            val error = assertFailsWith<java.io.IOException> { rejected.execute("https://example.com/video.mp4", emptyMap()) }
            assertFalse(error.toString().contains("example.com"))
            assertNull(error.cause)
        } finally { rejected.close() }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val wire = VideoTransport(Host.X, MediaHttpPolicy::parse, resolver = {
            entered.countDown(); release.await(); emptyList()
        })
        var failure: Throwable? = null
        val worker = Thread {
            try { wire.execute("https://example.com/video.mp4", emptyMap()).close() } catch (error: Throwable) { failure = error }
        }
        worker.start()
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            wire.cancel(); worker.join(2000)
            assertFalse(worker.isAlive)
            assertNotNull(failure)
            assertFalse(failure.toString().contains("example.com"))
        } finally { release.countDown(); wire.close(); worker.join(2000) }
    }
    @Test fun nonPublicDnsAndDisallowedRedirectsAreTypedPolicyFailures() {
        val rejected = VideoTransport(Host.X, MediaHttpPolicy::parse, resolver = {
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        })
        try { assertFailsWith<DirectPolicyRejected> { rejected.execute("https://example.com/video.mp4", emptyMap()) } }
        finally { rejected.close() }
        val calls = Calls { response(it, "https://example.org/video.mp4") }
        val wire = VideoTransport(Host.X, { raw -> MediaHttpPolicy.parse(raw)?.takeIf { it.host == "example.com" } }, calls)
        try { assertFailsWith<DirectPolicyRejected> { wire.execute("https://example.com/video.mp4", emptyMap()) } }
        finally { wire.close() }
    }
    @Test fun cancelBeforeRegistrationPreventsNetworkAndReportsNoLink() {
        val calls = Calls { response(it) }
        val wire = VideoTransport(Host.X, MediaHttpPolicy::parse, calls)
        wire.cancel()
        val failure = assertFailsWith<java.io.IOException> { wire.execute("https://example.com/video.mp4", emptyMap()) }
        assertTrue(calls.requests.isEmpty())
        assertFalse(failure.toString().contains("example.com"))
        wire.close()
    }
}
