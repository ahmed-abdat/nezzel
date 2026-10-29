package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class InstagramReaderTest : PublicImageTestBase() {
    private val source = LinkInput.parse(IG_POST)!!
    private val reader = InstagramReader(source)
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
            f.http.client.newOperation(InstagramReader.operation).use { token ->
                failure(InstagramReader(LinkInput.parse("https://www.instagram.com/synthetic_user/")!!).read(f.http.client, token) {
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
            f.http.client.newOperation(InstagramReader.operation).use { token ->
                failure(reader.read(f.http.client, token) { result ->
                    val failed = assertIs<MediaHttpResult.Failure>(result)
                    assertEquals(MediaHttpFailure.HTTP_STATUS, failed.reason)
                    ImagePageRead.Failed(ImageReadPlan.Failure(mapTransport(failed.reason), ImagePhase.PAGE, failed.statusCode))
                }, ImageFailure.HTTP, status = status)
            }
        }
    }
    @Test fun pageReadFailureIsReturnedUnchanged() {
        fixture { f ->
            f.http.client.newOperation(InstagramReader.operation).use { token ->
                val failed = ImageReadPlan.Failure(ImageFailure.CANCELLED, ImagePhase.PAGE)
                assertSame(failed, reader.read(f.http.client, token) { result ->
                    assertIs<MediaHttpResult.Success>(result).response.close()
                    ImagePageRead.Failed(failed)
                })
            }
        }
    }
    @Test fun typedPostReadParsesOnlyTheRequestedPage() {
        fixture(respond = { _, socket -> socket.reply(body = igPage(), headers = mapOf("Content-Type" to "text/html")) }) { f ->
            f.http.client.newOperation(InstagramReader.operation).use { token ->
                val photos = assertIs<ImageReadPlan.Photos>(reader.read(f.http.client, token) { result ->
                    assertIs<MediaHttpResult.Success>(result).response.use { ImagePageRead.Body(it.takeBody()) }
                })
                assertEquals(listOf(igImageUrl(1)), photos.candidates)
                assertEquals(listOf(IG_PAGE_TARGET), f.server.requests.map { it.target })
            }
        }
    }
    @Test fun cancellationAfterParsingPhotosKeepsPageFailurePhase() {
        fixture { f ->
            val operation = f.inspector.newOperation(imageSource(source.rawUrl), IMAGE_IDENTITY)
            operation.token.use {
                val plan = assertIs<ImageReadPlan.Photos>(reader.parse(body(igPage())))
                f.inspector.cancel(operation)
                // Exercise the private mapper at this precise boundary without adding production test hooks.
                val mapper = PublicImageInspector::class.java.declaredMethods.single { it.name == "mapPlan" }
                    .apply { isAccessible = true }
                val mapped = mapper.invoke(f.inspector, operation, plan, InstagramReader.imageRule, true)
                val result = mapped.javaClass.getDeclaredMethod("getResult").apply { isAccessible = true }.invoke(mapped)
                failed(assertIs<MediaInspectResult.Failure>(result), ImageFailure.CANCELLED, ImagePhase.PAGE)
                assertTrue(f.server.requests.isEmpty())
            }
        }
    }
    @Test fun parserFailuresKeepPageAndImagePhases() {
        failure(reader.parse(body(igShell())), ImageFailure.SOURCE_REFUSED)
        failure(reader.parse(body(igPage(igPolaris(media = null)))), ImageFailure.PRIVATE)
        val unsafe = igPage(igPolaris(media = igMedia(candidates = igCandidates(host = "evil.example"))))
        failure(reader.parse(body(unsafe)), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
    }
    @Test fun singlePhotoKeepsOriginalPreviewDiscoveryAndMetadata() {
        val photos = assertIs<ImageReadPlan.Photos>(reader.parse(body(igPage())))
        assertEquals(listOf(igImageUrl(1)), photos.candidates)
        assertEquals(listOf(igImageUrl(1, "_s640x640")), photos.previewCandidates)
        assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), photos.discovery)
        assertEquals("Synthetic caption line", photos.metadata.title, "metadata.title")
        assertEquals("Synthetic Author", photos.metadata.uploader, "metadata.uploader")
        assertNull(photos.metadata.durationSeconds, "metadata.durationSeconds")
        assertTrue(photos.metadata.thumbnailUrl == null, "metadata.thumbnailUrl must be absent")
        assertNull(photos.pageContext)
        assertNull(photos.videoTarget)
    }
    @Test fun mixedCarouselKeepsExactPartialDiscoveryAndPhotoOrder() {
        val mixed = igPage(igPolaris(media = igMedia(8,
            children = listOf(igVideo(), igPhoto(1), igPhoto(2, host = "evil.example"), igPhoto(3)))))
        val photos = assertIs<ImageReadPlan.Photos>(reader.parse(body(mixed)))
        assertEquals(listOf(igImageUrl(1), igImageUrl(3)), photos.candidates)
        assertEquals(listOf(igImageUrl(1, "_s640x640"), igImageUrl(3, "_s640x640")), photos.previewCandidates)
        assertEquals(ImageDiscovery(2, DiscoveryCompleteness.PARTIAL, unavailableCount = 1, hasVideo = true), photos.discovery)
        assertEquals("Synthetic caption line", photos.metadata.title, "metadata.title")
        assertEquals("Synthetic Author", photos.metadata.uploader, "metadata.uploader")
        assertNull(photos.metadata.durationSeconds, "metadata.durationSeconds")
        assertTrue(photos.metadata.thumbnailUrl == null, "metadata.thumbnailUrl must be absent")
        assertNull(photos.pageContext)
        assertNull(photos.videoTarget)
    }
    @Test fun videoAndUnavailableMixedPhotosKeepExactSourceAndFlag() {
        for ((media, unavailable) in listOf(igMedia(2) to false, igMedia(8,
            children = listOf(igPhoto(1, host = "evil.example"), igVideo())) to true)) {
            val video = assertIs<ImageReadPlan.Video>(reader.parse(body(igPage(igPolaris(media = media)))))
            assertSame(source, video.target)
            assertEquals(unavailable, video.photosUnavailable)
            assertNull(video.infoJson)
        }
    }
}
