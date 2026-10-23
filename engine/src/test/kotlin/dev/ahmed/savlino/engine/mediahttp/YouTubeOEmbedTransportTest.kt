package dev.ahmed.savlino.engine.mediahttp

import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

class YouTubeOEmbedTransportTest {
    companion object {
        private lateinit var identity: MediaHttpTlsIdentity
        @JvmStatic @BeforeClass fun setup() { identity = MediaHttpTlsIdentity() }
        @JvmStatic @AfterClass fun cleanup() { identity.close() }
    }
    private val source = "https://youtu.be/abcdefghijk"
    private fun fixture(respond: (RecordedMediaRequest, SSLSocket) -> Unit, test: (MediaHttpHarness) -> Unit) {
        MediaHttpServer(identity, respond = respond).use { server -> MediaHttpHarness(identity, server).use(test) }
    }

    @Test fun boundedJsonUsesNoCookiesAndReturnsRealMetadata() {
        val json = """{"title":"Real title","thumbnail_url":"https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"}"""
        fixture({ _, socket -> socket.reply(body = json.toByteArray(), headers = mapOf("Content-Type" to "application/json")) }) { h ->
            val preview = runBlocking { YouTubeOEmbed(h.client).load(source) }
            assertEquals("Real title", preview?.title)
            assertEquals(1, h.server.requests.size)
            assertEquals("application/json", h.server.requests.single().headers["accept"])
            assertNull(h.server.requests.single().headers["cookie"])
        }
    }

    @Test fun redirectsHttpFailuresAndOversizeAreSilentAndNeverFollowed() {
        for (status in listOf(302, 404, 200)) {
            fixture({ _, socket -> socket.reply(status, body = if (status == 200) ByteArray(32 * 1024 + 1) else byteArrayOf(),
                headers = mapOf("Location" to "https://other.test/leak")) }) { h ->
                assertNull(runBlocking { YouTubeOEmbed(h.client).load(source) })
                assertEquals(1, h.server.requests.size)
                assertEquals(1, h.dnsCalls.get())
            }
        }
    }

    @Test fun wrongOembedHostRejectedBeforeDns() {
        var resolutions = 0
        val environment = MediaHttpEnvironment(rawResolver = { resolutions++; error("No DNS allowed") },
            routeMapper = { error("No connection allowed") }, trustManager = null)
        MediaHttpClient.forTesting(environment).use { client ->
            client.newYouTubeOEmbedOperation().use { token ->
                assertFailure(client.fetchYouTubeOEmbed(token, "https://other.test/oembed"), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(0, resolutions)
        }
    }

    @Test fun stalledOembedEndsSilentlyWithinTheShortDeadline() {
        val release = CountDownLatch(1)
        try {
            fixture({ _, _ -> release.await() }) { h ->
                try {
                    assertNull(runBlocking { withTimeout(6_000) { YouTubeOEmbed(h.client).load(source) } })
                } finally { release.countDown() }
                assertEquals(1, h.server.requests.size)
            }
        } finally { release.countDown() }
    }
}
