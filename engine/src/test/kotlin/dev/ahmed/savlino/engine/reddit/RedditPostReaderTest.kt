package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.mediahttp.RecordedMediaRequest
import dev.ahmed.savlino.engine.mediahttp.reply
import dev.ahmed.savlino.engine.mediaimage.PublicImageFixture
import dev.ahmed.savlino.engine.mediaimage.PublicImageTestBase
import javax.net.ssl.SSLSocket
import org.junit.Test
import kotlin.test.*

class RedditPostReaderTest : PublicImageTestBase() {
    private val html = mapOf("Content-Type" to "text/html")
    private val answered = "solution=0123456789abcdef0123456789abcdef&js_challenge=1"
    private fun moved(to: String) = mapOf("Location" to to, "Content-Type" to "text/html")

    /** The logged-out flow: every page first answers with the check, the answered refetch with [page]. */
    private fun SSLSocket.checked(request: RecordedMediaRequest, page: () -> Unit) =
        if (answered in request.target) page() else reply(body = challengePage(), headers = html)

    private fun PublicImageFixture.read(raw: String): RedditTarget? = http.client.newRedditOperation().use { token ->
        RedditPostReader(http.client).read(token, assertNotNull(RedditPostIdentity.parse(raw)))
    }
    private fun PublicImageFixture.targets() = server.requests.map { it.target }

    @Test fun aCheckedVideoPostHandsYtDlpItsDashManifestAndTitle() = fixture(respond = { request, socket ->
        socket.checked(request) { socket.reply(body = postPage(), headers = html) }
    }) { f ->
        val read = assertIs<RedditTarget.Read>(f.read("https://www.reddit.com$PERMALINK?utm_source=share"))
        assertEquals("https://v.redd.it/$VIDEO_ID/DASHPlaylist.mpd", read.url)
        assertEquals("A synthetic & quoted \"video\"", read.video?.title)
        assertEquals(listOf(PERMALINK, PERMALINK), f.targets().map { it.substringBefore('?') })
        assertNull(f.server.requests[0].headers["cookie"])
        assertTrue(f.targets()[1].endsWith("?$answered&jsc_token=${"ab".repeat(32)}&jsc_orig_r="))
        assertTrue(f.server.requests.all { it.headers["host"] == "www.reddit.com" })
    }

    @Test fun shortAndShareLinksFollowTheirRedirectToTheSamePost() {
        for (raw in listOf("https://redd.it/$POST_ID", "https://www.reddit.com/r/synthetic/s/AbC123xyz"))
            fixture(respond = { request, socket -> when (request.target.substringBefore('?')) {
                PERMALINK -> socket.checked(request) { socket.reply(body = postPage(), headers = html) }
                else -> socket.reply(301, ByteArray(0), moved("https://www.reddit.com$PERMALINK?share_id=synthetic"))
            } }) { f ->
                assertIs<RedditTarget.Read>(f.read(raw), raw)
                assertEquals(3, f.server.requests.size, raw)
                // The share id never reaches the post page.
                assertTrue(f.targets().none { "share_id" in it }, raw)
            }
    }

    @Test fun textPostsHoldNoVideoAndSignInWallsAskForSignIn() {
        fixture(respond = { request, socket ->
            socket.checked(request) { socket.reply(body = postPage("text"), headers = html) }
        }) { f -> assertEquals(RedditTarget.Failed(DownloadError.NoVideo), f.read("https://www.reddit.com$PERMALINK")) }
        fixture(respond = { _, socket -> socket.reply(302, ByteArray(0), moved("https://www.reddit.com/login/?dest=x")) }) { f ->
            assertEquals(RedditTarget.Failed(DownloadError.LoginRequired), f.read("https://www.reddit.com$PERMALINK"))
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun removedAndRefusedPostsKeepHonestErrors() {
        for ((status, error) in listOf(404 to DownloadError.Unavailable, 410 to DownloadError.Unavailable,
            403 to DownloadError.SourceRefused, 429 to DownloadError.SourceRefused))
            fixture(respond = { request, socket ->
                socket.checked(request) { socket.reply(status, "gone".toByteArray(), html) }
            }) { f -> assertEquals(RedditTarget.Failed(error), f.read("https://www.reddit.com$PERMALINK"), "$status") }
    }

    @Test fun anotherPostARepeatedCheckOrAnUnknownPageKeepTheOldPath() {
        fixture(respond = { _, socket -> socket.reply(301, ByteArray(0), moved("https://www.reddit.com/r/other/comments/9zzzzzz/other/")) }) { f ->
            assertNull(f.read("https://www.reddit.com$PERMALINK"))
            assertEquals(1, f.server.requests.size)
        }
        // Two answers at most: a check that keeps coming back is a page this reader can't place.
        fixture(respond = { _, socket -> socket.reply(body = challengePage(), headers = html) }) { f ->
            assertNull(f.read("https://www.reddit.com$PERMALINK"))
            assertEquals(3, f.server.requests.size)
        }
        fixture(respond = { request, socket ->
            socket.checked(request) { socket.reply(body = postPage("crosspost"), headers = html) }
        }) { f -> assertNull(f.read("https://www.reddit.com$PERMALINK")) }
    }
}
