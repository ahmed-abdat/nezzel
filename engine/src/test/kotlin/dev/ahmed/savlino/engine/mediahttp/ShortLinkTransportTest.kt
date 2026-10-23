package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.domain.LinkInput
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

class ShortLinkTransportTest {
    companion object {
        private lateinit var identity: MediaHttpTlsIdentity
        @JvmStatic @BeforeClass fun setup() { identity = MediaHttpTlsIdentity() }
        @JvmStatic @AfterClass fun cleanup() { identity.close() }
    }
    private val youtube = "https://www.youtube.com/watch?v=abcdefghijk"
    private fun fixture(respond: (RecordedMediaRequest, SSLSocket) -> Unit, test: (MediaHttpHarness) -> Unit) {
        MediaHttpServer(identity, respond = respond).use { server -> MediaHttpHarness(identity, server).use(test) }
    }
    private fun MediaHttpHarness.outcome(url: String) = runBlocking { ShortLinkResolver(client).resolve(LinkInput.parse(url)!!) }
    private fun MediaHttpHarness.resolve(url: String) = (outcome(url) as? ShortLinkOutcome.Resolved)?.link
    private fun redirect(location: String): (RecordedMediaRequest, SSLSocket) -> Unit =
        { _, socket -> socket.reply(301, byteArrayOf(), mapOf("Location" to location)) }

    @Test fun shortenerDestinationIsTakenFromTheRedirectWithoutRequestingIt() {
        fixture(redirect(youtube)) { h ->
            assertEquals(youtube, h.resolve("https://tinyurl.com/7synthetic")?.rawUrl)
            val request = h.server.requests.single()
            assertEquals("/7synthetic", request.target)
            assertEquals("tinyurl.com", request.headers["host"])
            assertEquals("Nezzel", request.headers["user-agent"])
            assertNull(request.headers["cookie"])
        }
    }

    @Test fun shortenerChainsAreFollowedOnlyWhileTheyStayOnShorteners() {
        fixture({ request, socket ->
            when (request.headers["host"]) {
                "bit.ly" -> socket.reply(301, byteArrayOf(), mapOf("Location" to "https://tinyurl.com/next"))
                "tinyurl.com" -> socket.reply(302, byteArrayOf(), mapOf("Location" to "https://www.instagram.com/share/reel/BAsynth01/"))
                else -> socket.reply(302, byteArrayOf(), mapOf("Location" to "https://www.instagram.com/reel/DBsynth01/?igsh=x"))
            }
        }) { h ->
            assertEquals("https://www.instagram.com/reel/DBsynth01/?igsh=x", h.resolve("https://bit.ly/synth")?.rawUrl)
            assertEquals(listOf("bit.ly", "tinyurl.com", "www.instagram.com"), h.server.requests.map { it.headers["host"] })
        }
    }

    @Test fun pinterestShortlinkPassesThroughItsRedirectApi() {
        fixture({ request, socket ->
            if (request.headers["host"] == "pin.it")
                socket.reply(308, byteArrayOf(), mapOf("Location" to "https://api.pinterest.com/url_shortener/4Synth/redirect/"))
            else socket.reply(302, byteArrayOf(), mapOf("Location" to "https://www.pinterest.com/pin/664281013778109217/"))
        }) { h ->
            assertEquals("https://www.pinterest.com/pin/664281013778109217/", h.resolve("https://pin.it/4Synth")?.rawUrl)
            assertEquals(2, h.server.requests.size)
        }
    }

    @Test fun interstitialsErrorsAndLoopsKeepTheShortLink() {
        val answers = listOf<(SSLSocket) -> Unit>(
            { it.reply(200, "<html>leaving</html>".toByteArray(), mapOf("Content-Type" to "text/html")) },
            { it.reply(404, byteArrayOf()) },
            { it.reply(301, byteArrayOf(), mapOf("Location" to "https://t.co/loop")) },
        )
        for (answer in answers) fixture({ _, socket -> answer(socket) }) { h ->
            assertEquals(ShortLinkOutcome.Unresolved, h.outcome("https://t.co/loop"))
            assertEquals(1, h.server.requests.size)
        }
    }

    @Test fun aCleartextDestinationIsReadOverHttpsLikeAPastedOne() {
        fixture(redirect("http://www.youtube.com/watch?v=abcdefghijk")) { h ->
            assertEquals(youtube, h.resolve("https://t.co/Synth01")?.rawUrl)
            assertEquals(1, h.server.requests.size)
        }
    }

    @Test fun ipLiteralAndLocalhostDestinationsAreBlocked() {
        for (location in listOf("https://127.0.0.1/x", "http://10.0.0.1/x", "https://[::1]/x", "https://localhost/x"))
            fixture(redirect(location)) { h ->
                assertEquals(ShortLinkOutcome.Blocked, h.outcome("https://t.co/Synth01"), location)
                assertEquals(1, h.server.requests.size)
            }
    }

    @Test fun aDestinationThatResolvesToLoopbackIsBlockedWithoutBeingRequested() {
        val loopback = java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        MediaHttpServer(identity, respond = redirect("https://loop.example/x")).use { server ->
            MediaHttpHarness(identity, server,
                resolver = { host -> if (host == "loop.example") listOf(loopback) else listOf(PUBLIC_V4) }).use { h ->
                assertEquals(ShortLinkOutcome.Blocked, h.outcome("https://tinyurl.com/7synthetic"))
                assertEquals(listOf("tinyurl.com"), h.server.requests.map { it.headers["host"] })
            }
        }
    }

    @Test fun theLandingHopCountsTowardTheThreeRedirectLimit() {
        val chain = mapOf("bit.ly" to "https://tinyurl.com/a", "tinyurl.com" to "https://t.co/b", "t.co" to "https://ow.ly/c",
            "ow.ly" to youtube)
        val respond: (RecordedMediaRequest, SSLSocket) -> Unit =
            { request, socket -> socket.reply(301, byteArrayOf(), mapOf("Location" to chain.getValue(request.headers["host"]!!))) }
        fixture(respond) { h ->
            assertEquals(ShortLinkOutcome.Unresolved, h.outcome("https://bit.ly/synth"))
            assertEquals(listOf("bit.ly", "tinyurl.com", "t.co", "ow.ly"), h.server.requests.map { it.headers["host"] })
        }
        fixture(respond) { h ->
            assertEquals(youtube, h.resolve("https://tinyurl.com/a")?.rawUrl)
            assertEquals(3, h.server.requests.size)
        }
    }

    @Test fun onlyKnownShortenerShapesAreRequested() {
        var resolutions = 0
        val environment = MediaHttpEnvironment(rawResolver = { resolutions++; error("No DNS allowed") },
            routeMapper = { error("No connection allowed") }, trustManager = null)
        MediaHttpClient.forTesting(environment).use { client ->
            for (url in listOf(youtube, "https://youtu.be/abcdefghijk", "https://lnkd.in/p/synthetic", "https://bit.ly/a/b",
                    "https://www.instagram.com/reel/DBsynth01/", "https://example.org/x"))
                assertEquals(ShortLinkOutcome.Unresolved, runBlocking { ShortLinkResolver(client).resolve(LinkInput.parse(url)!!) }, url)
            client.newShortLinkOperation().use { token ->
                assertFailure(client.fetchShortLink(token, youtube), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(0, resolutions)
        }
    }
}
