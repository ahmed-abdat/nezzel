package dev.ahmed.savlino.engine.youtube

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.schabi.newpipe.extractor.NewPipe
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class NpeFastSourceTest {
    private val url = "https://www.youtube.com/watch?v=fixture0000"

    @Test fun extractorRequestExceptionBecomesNull() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw IOException("private fixture")
        }.build())
        // NewPipe's singleton may already have been installed by another test. Always use an
        // interceptor-only downloader here; neither test order nor extractor retries can hit the network.
        NewPipe.init(downloader)
        assertEquals(FastResult.Miss, NpeFastSource(downloader).infoJson(url, "exception", 1000))
        assertTrue(calls.get() > 0)
    }

    @Test fun linkageErrorBecomesNull() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw NoClassDefFoundError("fixture")
        }.build())
        NewPipe.init(downloader)
        assertEquals(FastResult.Miss, NpeFastSource(downloader).infoJson(url, "linkage", 1000))
        assertTrue(calls.get() > 0)
    }

    private fun source(intercept: (okhttp3.Interceptor.Chain, Int) -> Response): NpeFastSource {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor { intercept(it, calls.incrementAndGet()) }.build())
        NewPipe.init(downloader)
        return NpeFastSource(downloader)
    }
    private fun ok(chain: okhttp3.Interceptor.Chain, code: Int = 200) = Response.Builder().request(chain.request())
        .protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body("fixture".toResponseBody()).build()

    @Test fun noResponseWithTransportFailuresIsUnreachable() {
        assertEquals(FastResult.Unreachable(offline = true),
            source { _, _ -> throw java.net.UnknownHostException("private fixture") }.infoJson(url, "dns", 1000))
        for (error in listOf(java.net.ConnectException("private"), java.net.NoRouteToHostException("private"),
            java.net.SocketTimeoutException("private"))) {
            assertEquals(FastResult.Unreachable(offline = false), source { _, _ -> throw error }.infoJson(url, "connect", 1000))
        }
    }

    @Test fun anyResponseMeansTheNetworkWorksSoLaterFailuresStayAMiss() {
        assertEquals(FastResult.Miss, source { chain, call ->
            if (call == 1) ok(chain) else throw java.net.ConnectException("private")
        }.infoJson(url, "partial", 1000))
        for (code in listOf(403, 503))
            assertEquals(FastResult.Miss, source { chain, _ -> ok(chain, code) }.infoJson(url, "status", 1000))
    }

    @Test fun budgetSpentWhileConnectingWithNoResponseIsUnreachable() {
        assertEquals(FastResult.Unreachable(offline = false), source { _, _ ->
            Thread.sleep(400); throw java.io.InterruptedIOException("timeout")
        }.infoJson(url, "slow", 300))
    }

    @Test fun nonStreamUrlAndSpentBudgetNeverMakeARequest() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw IOException("fixture")
        }.build())
        NewPipe.init(downloader)
        val source = NpeFastSource(downloader)
        assertEquals(FastResult.Miss, source.infoJson("https://www.youtube.com/playlist?list=fixture", "playlist", 1000))
        assertEquals(FastResult.Miss, source.infoJson(url, "spent", 0))
        assertEquals(0, calls.get())
    }

    @Test fun cancelDelegatesToDownloaderAndCancelsItsRegisteredCall() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call()); entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Fixture").body("fixture".toResponseBody()).build()
        }.build())
        NewPipe.init(downloader)
        val source = NpeFastSource(downloader)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<FastResult> { source.infoJson(url, "cancel", 5000) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            source.cancel("cancel")
            assertTrue(call.get().isCanceled())
            release.countDown()
            assertEquals(FastResult.Miss, result.get(3, TimeUnit.SECONDS))
            source.cancel("unknown")
        } finally {
            release.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
