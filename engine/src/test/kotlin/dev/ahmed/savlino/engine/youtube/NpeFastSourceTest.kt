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
        assertNull(NpeFastSource(downloader).infoJson(url, "exception", 1000))
        assertTrue(calls.get() > 0)
    }

    @Test fun linkageErrorBecomesNull() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw NoClassDefFoundError("fixture")
        }.build())
        NewPipe.init(downloader)
        assertNull(NpeFastSource(downloader).infoJson(url, "linkage", 1000))
        assertTrue(calls.get() > 0)
    }

    @Test fun nonStreamUrlAndSpentBudgetNeverMakeARequest() {
        val calls = AtomicInteger()
        val downloader = NpeDownloader(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw IOException("fixture")
        }.build())
        NewPipe.init(downloader)
        val source = NpeFastSource(downloader)
        assertNull(source.infoJson("https://www.youtube.com/playlist?list=fixture", "playlist", 1000))
        assertNull(source.infoJson(url, "spent", 0))
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
            val result = executor.submit<String?> { source.infoJson(url, "cancel", 5000) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            source.cancel("cancel")
            assertTrue(call.get().isCanceled())
            release.countDown()
            assertNull(result.get(3, TimeUnit.SECONDS))
            source.cancel("unknown")
        } finally {
            release.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
