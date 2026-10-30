package dev.ahmed.savlino.engine.pinterest

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class PinterestReaderTest : PublicImageTestBase() {
    private val pin = "100000000000000001"
    private val source = LinkInput.parse("https://co.pinterest.com/pin/synthetic-title--$pin/sent/?trk=1")!!
    private val reader = PinterestReader(source)
    private val image = "https://i.pinimg.com/originals/01/23/45/0123456789abcdef0123456789abcdef.png"
    private val html = mapOf("Content-Type" to "text/html")
    private fun page(name: String) = javaClass.getResourceAsStream("/pinterest/$name.html")!!.readBytes()
    private fun readPage(result: MediaHttpResult): ImagePageRead = when (result) {
        is MediaHttpResult.Failure -> ImagePageRead.Failed(ImageReadPlan.Failure(
            mapTransport(result.reason), ImagePhase.PAGE, result.statusCode))
        is MediaHttpResult.Success -> result.response.use { ImagePageRead.Body(it.takeBody()) }
    }
    private fun failure(plan: ImageReadPlan, reason: ImageFailure, phase: ImagePhase = ImagePhase.PAGE) {
        val failed = assertIs<ImageReadPlan.Failure>(plan)
        assertEquals(reason, failed.reason)
        assertEquals(phase, failed.phase)
        assertNull(failed.statusCode)
    }

    @Test fun aBoardNeverRequestsThePinPage() = fixture { f ->
        f.http.client.newOperation(PinterestReader.operation).use { token ->
            val refused = PinterestReader(LinkInput.parse("https://www.pinterest.com/synthetic/board/")!!)
            failure(refused.read(f.http.client, token) { error("board reached the page reader") }, ImageFailure.SOURCE_REFUSED)
            assertTrue(f.server.requests.isEmpty())
        }
    }

    @Test fun theCanonicalPinIsReadWithTheMobileAgent() = fixture(respond = { _, socket ->
        socket.reply(body = page("redux-image"), headers = html)
    }) { f ->
        f.http.client.newOperation(PinterestReader.operation).use { token ->
            val photos = assertIs<ImageReadPlan.Photos>(reader.read(f.http.client, token, ::readPage))
            assertEquals(listOf(image), photos.candidates)
            assertEquals(listOf("https://i.pinimg.com/236x/01/23/45/0123456789abcdef0123456789abcdef.jpg"), photos.previewCandidates)
            val request = f.server.requests.single()
            assertEquals("/pin/$pin/", request.target)
            assertEquals("www.pinterest.com", request.headers["host"])
            assertTrue(request.headers.getValue("user-agent").contains("Mobile"))
            assertFalse(request.headers.getValue("user-agent").contains("Windows"))
        }
    }

    @Test fun aWrongPinAnExternalHostOrLoginIsNotFollowed() {
        val cases = listOf(
            "https://www.pinterest.com/pin/200000000000000002/" to ImageFailure.SOURCE_REFUSED,
            "https://evil.example/pin/$pin/" to ImageFailure.SOURCE_REFUSED,
            "https://www.pinterest.com/login/" to ImageFailure.PRIVATE)
        for ((location, reason) in cases) fixture(respond = { _, socket ->
            socket.reply(302, ByteArray(0), mapOf("Location" to location))
        }) { f ->
            f.http.client.newOperation(PinterestReader.operation).use { token ->
                failure(reader.read(f.http.client, token, ::readPage), reason)
                assertEquals(listOf("/pin/$pin/"), f.server.requests.map { it.target }, location)
            }
        }
    }

    @Test fun anImageRedirectOffThePinimgHostIsRefused() {
        val locations = listOf(
            "https://evil.example/stolen.jpg",
            "https://s.pinimg.com/originals/01/23/45/0123456789abcdef0123456789abcdef.jpg",
            "https://www.pinterest.com/pin/$pin/",
            "$image?x=1")
        for (location in locations) fixture(respond = { _, socket ->
            socket.reply(302, ByteArray(0), mapOf("Location" to location))
        }) { f ->
            f.http.client.newOperation(PinterestReader.operation).use { token ->
                val result = f.http.client.fetchCarouselImage(token, PublicMediaRequest(image,
                    MediaHttpPurpose.IMAGE, hostRule = MediaHostRule.PINTEREST_CDN), 2 * 1024 * 1024)
                assertEquals(MediaHttpFailure.INVALID_CONTEXT, assertIs<MediaHttpResult.Failure>(result).reason, location)
                assertEquals(1, f.server.requests.size, location)
            }
        }
    }

    @Test fun aDeclaredOrChunkedPageOverTheCapIsABudgetFailure() {
        fixture(respond = { _, socket ->
            socket.reply(declared = MediaPageLimits.PINTEREST.toLong() + 1, headers = html)
        }) { f ->
            f.http.client.newOperation(PinterestReader.operation).use { token ->
                failure(reader.read(f.http.client, token, ::readPage), ImageFailure.BUDGET)
                assertEquals(0L, token.budget.snapshot().consumedBytes)
                assertEquals(1, f.server.requests.size)
            }
        }
        fixture(respond = { _, socket ->
            socket.reply(body = ByteArray(MediaPageLimits.PINTEREST + 1), chunked = true, headers = html)
        }) { f ->
            f.http.client.newOperation(PinterestReader.operation).use { token ->
                failure(reader.read(f.http.client, token, ::readPage), ImageFailure.BUDGET)
                assertTrue(token.budget.snapshot().consumedBytes <= MediaPageLimits.PINTEREST.toLong())
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun aVideoPageHandsBackTheCallersLink() {
        val body = page("video")
        val video = assertIs<ImageReadPlan.Video>(reader.parse(MediaHttpBody(body, body.size)))
        assertEquals(source.rawUrl, video.target.rawUrl)
        assertFalse(video.photosUnavailable)
    }
}
