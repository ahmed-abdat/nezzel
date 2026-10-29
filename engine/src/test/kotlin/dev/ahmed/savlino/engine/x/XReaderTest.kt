package dev.ahmed.savlino.engine.x

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class XReaderTest : PublicImageTestBase() {
    private val source = LinkInput.parse(X_POST)!!
    private val reader = XReader(source)
    private fun body(bytes: ByteArray) = MediaHttpBody(bytes + ByteArray(32) { 120 }, bytes.size)
    private fun failure(plan: ImageReadPlan, reason: ImageFailure, phase: ImagePhase = ImagePhase.PAGE,
        status: Int? = null) {
        val failed = assertIs<ImageReadPlan.Failure>(plan)
        assertEquals(reason, failed.reason)
        assertEquals(phase, failed.phase)
        assertEquals(status, failed.statusCode)
        assertNull(failed.fallback)
    }

    @Test fun refusedIdentityNeverRequestsOrReadsAPage() {
        fixture { f ->
            f.http.client.newOperation(XReader.operation).use { token ->
                failure(XReader(LinkInput.parse("https://x.com/someone")!!).read(f.http.client, token) {
                    error("Refused identity reached page reader")
                }, ImageFailure.SOURCE_REFUSED)
                assertTrue(f.server.requests.isEmpty())
            }
        }
    }
    @Test fun everyHttpStatusKeepsTheSharedHttpFailure() {
        for (status in listOf(400, 401, 403, 404, 410, 429, 500)) fixture(respond = { _, socket ->
            socket.reply(status = status)
        }) { f ->
            f.http.client.newOperation(XReader.operation).use { token ->
                val plan = reader.read(f.http.client, token) { result ->
                    val failed = assertIs<MediaHttpResult.Failure>(result)
                    assertEquals(MediaHttpFailure.HTTP_STATUS, failed.reason)
                    ImagePageRead.Failed(ImageReadPlan.Failure(mapTransport(failed.reason), ImagePhase.PAGE, failed.statusCode))
                }
                failure(plan, ImageFailure.HTTP, status = status)
            }
        }
    }
    @Test fun pageReadFailureIsReturnedUnchanged() {
        fixture { f ->
            f.http.client.newOperation(XReader.operation).use { token ->
                val failed = ImageReadPlan.Failure(ImageFailure.STALE, ImagePhase.PAGE)
                assertSame(failed, reader.read(f.http.client, token) { result ->
                    assertIs<MediaHttpResult.Success>(result).response.close()
                    ImagePageRead.Failed(failed)
                })
            }
        }
    }
    @Test fun typedSyndicationReadParsesOnlyTheRequestedPost() {
        fixture(respond = { _, socket -> socket.reply(body = xTweet(), headers = mapOf("Content-Type" to "application/json")) }) { f ->
            f.http.client.newOperation(XReader.operation).use { token ->
                val photos = assertIs<ImageReadPlan.Photos>(reader.read(f.http.client, token) { result ->
                    assertIs<MediaHttpResult.Success>(result).response.use { ImagePageRead.Body(it.takeBody()) }
                })
                assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=orig"), photos.candidates)
                assertEquals(listOf(X_SYNDICATION_TARGET), f.server.requests.map { it.target })
            }
        }
    }
    @Test fun cancellationAfterParsingPhotosKeepsPageFailurePhase() {
        fixture { f ->
            val operation = f.inspector.newOperation(imageSource(source.rawUrl), IMAGE_IDENTITY)
            operation.token.use {
                val plan = assertIs<ImageReadPlan.Photos>(reader.parse(body(xTweet())))
                f.inspector.cancel(operation)
                // Exercise the private mapper at this precise boundary without adding production test hooks.
                val mapper = PublicImageInspector::class.java.declaredMethods.single { it.name == "mapPlan" }
                    .apply { isAccessible = true }
                val mapped = mapper.invoke(f.inspector, operation, plan, XReader.imageRule, true)
                val result = mapped.javaClass.getDeclaredMethod("getResult").apply { isAccessible = true }.invoke(mapped)
                failed(assertIs<MediaInspectResult.Failure>(result), ImageFailure.CANCELLED, ImagePhase.PAGE)
                assertTrue(f.server.requests.isEmpty())
            }
        }
    }
    @Test fun parserFailuresKeepPageAndImagePhases() {
        failure(reader.parse(body("not json".toByteArray())), ImageFailure.SOURCE_REFUSED)
        failure(reader.parse(body(xTombstone())), ImageFailure.PRIVATE)
        failure(reader.parse(body(xTweet(listOf("""{"type":"photo"}""")))), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
    }
    @Test fun singleLargePhotoKeepsLargeSaveCandidateAndPreviewQuality() {
        val photos = assertIs<ImageReadPlan.Photos>(reader.parse(body(xTweet(listOf(xPhoto(width = 4096))))))
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=large"), photos.candidates)
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=small"), photos.previewCandidates)
        assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE, previewQuality = true), photos.discovery)
        assertEquals("Synthetic caption line", photos.metadata.title, "metadata.title")
        assertEquals("Synthetic Author", photos.metadata.uploader, "metadata.uploader")
        assertNull(photos.metadata.durationSeconds, "metadata.durationSeconds")
        assertTrue(photos.metadata.thumbnailUrl == null, "metadata.thumbnailUrl must be absent")
        assertNull(photos.pageContext)
        assertNull(photos.videoTarget)
    }
    @Test fun carouselUsesOriginalsSmallPreviewsAndExactDiscovery() {
        val photos = assertIs<ImageReadPlan.Photos>(reader.parse(body(xTweet(listOf(
            xPhoto(width = 4096), xPhoto("SyntheticPhoto2", "png"), """{"type":"photo"}""", xVideo())))))
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=orig",
            "https://pbs.twimg.com/media/SyntheticPhoto2?format=png&name=orig"), photos.candidates)
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=small",
            "https://pbs.twimg.com/media/SyntheticPhoto2?format=png&name=small"), photos.previewCandidates)
        assertEquals(ImageDiscovery(2, DiscoveryCompleteness.PARTIAL, unavailableCount = 1, hasVideo = true), photos.discovery)
        assertEquals("Synthetic caption line", photos.metadata.title, "metadata.title")
        assertEquals("Synthetic Author", photos.metadata.uploader, "metadata.uploader")
        assertNull(photos.metadata.durationSeconds, "metadata.durationSeconds")
        assertTrue(photos.metadata.thumbnailUrl == null, "metadata.thumbnailUrl must be absent")
        assertNull(photos.pageContext)
        assertNull(photos.videoTarget)
    }
    @Test fun videoAndUnavailableMixedPhotosKeepSourceAndInfo() {
        for ((media, unavailable) in listOf(listOf(xVideo()) to false,
            listOf("""{"type":"photo"}""", xVideo()) to true)) {
            val video = assertIs<ImageReadPlan.Video>(reader.parse(body(xTweet(media))))
            assertSame(source, video.target)
            assertEquals(unavailable, video.photosUnavailable)
            assertTrue(assertNotNull(video.infoJson).contains("\"id\":\"$X_ID\""))
            assertEquals(X_ID, assertNotNull(video.xIdentity).postId)
        }
    }
    @Test fun indexedVideoKeepsItsRequestedMediaIndex() {
        val indexed = XReader(LinkInput.parse("$X_POST/video/2")!!)
        val video = assertIs<ImageReadPlan.Video>(indexed.parse(body(xTweet(listOf("""{"type":"photo"}""",
            xVideo().replace("Synthetic.mp4", "Second.mp4"))))))
        assertTrue(assertNotNull(video.infoJson).contains("Second.mp4"))
        assertFalse(video.infoJson.contains("Synthetic.mp4"))
        assertEquals(2, assertNotNull(video.xIdentity).mediaIndex)
    }
}
