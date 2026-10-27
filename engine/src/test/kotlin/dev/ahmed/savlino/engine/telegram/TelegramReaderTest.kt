package dev.ahmed.savlino.engine.telegram

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class TelegramReaderTest : PublicImageTestBase() {
    private val source = LinkInput.parse("https://telegram.me/synthetic_channel/123?single")!!
    private val reader = TelegramReader(source)
    private fun body(name: String): MediaHttpBody {
        val bytes = javaClass.getResourceAsStream("/telegram/$name.html")!!.use { it.readBytes() }
        // Spare capacity is not part of the transferred page.
        return MediaHttpBody(bytes + ByteArray(32) { 120 }, bytes.size)
    }
    private fun failure(plan: ImageReadPlan, reason: ImageFailure, phase: ImagePhase = ImagePhase.PAGE) {
        val failed = assertIs<ImageReadPlan.Failure>(plan)
        assertEquals(reason, failed.reason)
        assertEquals(phase, failed.phase)
        assertNull(failed.statusCode)
        assertNull(failed.fallback)
    }
    private fun privateStatus(status: Int) = failure(assertNotNull(reader.pageFailure(
        MediaHttpResult.Failure(MediaHttpFailure.HTTP_STATUS, status))), ImageFailure.PRIVATE)

    @Test fun refusedIdentityNeverParsesAnotherPostsPhotos() {
        fixture { f ->
            f.http.client.newOperation(TelegramReader.operation).use { token ->
                val refused = TelegramReader(LinkInput.parse("https://t.me/c/123/456")!!)
                failure(refused.read(f.http.client, token) { error("Refused identity reached page reader") },
                    ImageFailure.SOURCE_REFUSED)
                assertTrue(f.server.requests.isEmpty())
            }
        }
    }
    @Test fun unauthorizedIsPrivate() = privateStatus(401)
    @Test fun forbiddenIsPrivate() = privateStatus(403)
    @Test fun missingIsPrivate() = privateStatus(404)
    @Test fun goneIsPrivate() = privateStatus(410)
    @Test fun otherTransportFailuresUseTheSharedMapping() {
        assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.HTTP_STATUS, 500)))
        assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.NETWORK, 403)))
        assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.CANCELLED)))
    }
    @Test fun parserFailuresKeepTheirReasonAndPhase() {
        failure(reader.parse(body("private")), ImageFailure.PRIVATE)
        failure(reader.parse(body("no-media")), ImageFailure.NO_MEDIA)
        val unsafe = body("single").let { String(it.bytes, 0, it.length).replace("cdn1.telesco.pe", "example.org").toByteArray() }
        failure(reader.parse(MediaHttpBody(unsafe, unsafe.size)), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
    }
    @Test fun videoKeepsTheSourceLinkWithoutClaimingUnavailablePhotos() {
        val video = assertIs<ImageReadPlan.Video>(reader.parse(body("video")))
        assertSame(source, video.target)
        assertFalse(video.photosUnavailable)
        assertNull(video.infoJson)
    }
    @Test fun photosKeepOriginalsPreviewsDiscoveryAndTitle() {
        for ((name, candidates) in listOf(
            "single" to listOf("https://cdn1.telesco.pe/file/syntheticPhoto1"),
            "album" to listOf("https://cdn1.telesco.pe/file/syntheticPhoto1", "https://cdn1.telesco.pe/file/syntheticPhoto2"))) {
            val photos = assertIs<ImageReadPlan.Photos>(reader.parse(body(name)))
            assertEquals(candidates, photos.candidates)
            assertEquals(candidates, photos.previewCandidates)
            assertEquals(ImageDiscovery(candidates.size, DiscoveryCompleteness.COMPLETE), photos.discovery)
            assertEquals("Synthetic caption", photos.metadata.title)
            assertNull(photos.metadata.uploader)
            assertNull(photos.metadata.durationSeconds)
            assertNull(photos.metadata.thumbnailUrl)
            assertNull(photos.pageContext)
            assertNull(photos.videoTarget)
        }
    }
}
