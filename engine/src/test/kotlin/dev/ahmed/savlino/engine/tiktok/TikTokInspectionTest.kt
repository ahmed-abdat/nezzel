package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class TikTokInspectionTest : PublicImageTestBase() {
    private val post = "https://www.tiktok.com/@synthetic/photo/900000001"
    private fun PublicImageFixture.inspectPost(): MediaInspectResult = runBlocking {
        MediaSourceInspector(inspector, LegacyMediaInspectionAdapter(FakeEngine())).inspect(imageSource(post), "post", MediaSourceRoute.TIKTOK)
    }

    @Test fun mixedMirrorErrorsKeepTheNonExpiryReasonInEitherOrder() {
        for (expiredFirst in listOf(true, false)) {
            val saving = AtomicBoolean()
            fixture(respond = { request, socket -> when {
                request.headers["host"] == "www.tiktok.com" -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
                !saving.get() -> socket.reply(body = raster(40, 30, "jpg"))
                request.headers["host"].orEmpty().startsWith("p16-") == expiredFirst -> socket.reply(403)
                else -> socket.close()
            } }) { f -> inspected(f.inspectPost()).use { result ->
                saving.set(true)
                val sink = ByteArrayOutputStream()
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                assertEquals(ImageFailure.NETWORK,
                    assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }).reason)
                assertEquals(0, sink.size())
            } }
        }
    }
    @Test fun samePostRedirectsFetchTheCanonicalPageAgain() {
        for (hop in listOf("/@synthetic/video/900000001?lang=en", "/@synthetic/video/900000001/", "/@synthetic/photo/900000001")) {
            val pages = java.util.concurrent.atomic.AtomicInteger()
            fixture(respond = { request, socket -> when {
                request.headers["host"] == "www.tiktok.com" && pages.incrementAndGet() == 1 ->
                    socket.reply(302, headers = mapOf("Location" to hop))
                request.headers["host"] == "www.tiktok.com" -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
                else -> socket.reply(body = raster(40, 30, "jpg"))
            } }) { f -> inspected(f.inspectPost()).use { result ->
                assertEquals(2, result.inspection.items.size)
                assertEquals(listOf("/@synthetic/video/900000001", "/@synthetic/video/900000001"),
                    f.server.requests.filter { it.headers["host"] == "www.tiktok.com" }.map { it.target })
            } }
        }
    }

    @Test fun fetchesOneRewrittenDesktopPageAndSoundNeverTransfersDuringInspection() = fixture(respond = { request, socket ->
        if (request.headers["host"] == "www.tiktok.com") socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
        else socket.reply(body = raster(40, 30, "jpg"), headers = mapOf("Content-Type" to "image/jpeg"))
    }) { f -> inspected(f.inspectPost()).use { result ->
        assertEquals(2, result.inspection.items.size)
        assertNotNull(result.discovery.sound)
        assertEquals("https://www.tiktok.com/@synthetic/video/900000001", result.videoSource?.link?.rawUrl)
        assertEquals(Size.Unknown, firstRendition(result).image.size)
        assertNull(firstRendition(result).image.proof)
        val pages = f.server.requests.filter { it.headers["host"] == "www.tiktok.com" }
        assertEquals(1, pages.size)
        assertEquals("/@synthetic/video/900000001", pages.single().target)
        assertTrue(pages.single().headers["user-agent"].orEmpty().contains("Chrome/"))
        assertTrue(f.server.requests.none { it.target.contains("synthetic-sound") })
    } }

    @Test fun aWafChallengeIsAnsweredOnceWithItsCookieAndTheRefetchedPageIsRead() = fixture(respond = { request, socket ->
        when {
            request.headers["host"] != "www.tiktok.com" -> socket.reply(body = raster(40, 30, "jpg"), headers = mapOf("Content-Type" to "image/jpeg"))
            request.headers["cookie"] == null -> socket.reply(body = wafPage(), headers = mapOf("Content-Type" to "text/html"))
            else -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
        }
    }) { f -> inspected(f.inspectPost()).use { result ->
        assertEquals(2, result.inspection.items.size)
        val pages = f.server.requests.filter { it.headers["host"] == "www.tiktok.com" }
        assertEquals(listOf("/@synthetic/video/900000001", "/@synthetic/video/900000001"), pages.map { it.target })
        assertNull(pages[0].headers["cookie"])
        assertTrue(pages[1].headers["cookie"].orEmpty().startsWith("_wafchallengeid="))
        // The answer is for this page only: photo requests never carry it.
        assertTrue(f.server.requests.filter { it.headers["host"] != "www.tiktok.com" }.all { it.headers["cookie"] == null })
    } }

    @Test fun aSecondChallengeIsNotAnsweredAgain() = fixture(respond = { request, socket ->
        socket.reply(body = if (request.headers["host"] == "www.tiktok.com") wafPage() else raster(40, 30, "jpg"),
            headers = mapOf("Content-Type" to "text/html"))
    }) { f ->
        failed(f.inspectPost(), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
        assertEquals(2, f.server.requests.count { it.headers["host"] == "www.tiktok.com" })
    }

    @Test fun saveTriesTheSecondMirrorAndExpiredMirrorsAreTypedWithoutPublishingBytes() {
        for (bothExpired in listOf(false, true)) {
            val saving = AtomicBoolean()
            fixture(respond = { request, socket ->
                when {
                    request.headers["host"] == "www.tiktok.com" -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
                    saving.get() && (request.headers["host"].orEmpty().startsWith("p16-") || bothExpired) -> socket.reply(if (bothExpired) 410 else 403)
                    else -> socket.reply(body = raster(40, 30, "jpg"), headers = mapOf("Content-Type" to "image/jpeg"))
                }
            }) { f -> inspected(f.inspectPost()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                saving.set(true)
                val before = f.server.requests.size
                val sink = ByteArrayOutputStream()
                val saved = runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }
                if (bothExpired) {
                    assertEquals(ImageFailure.EXPIRED, assertIs<ImageSaveResult.Failure>(saved).reason)
                    assertEquals(0, sink.size())
                } else {
                    assertIs<ImageSaveResult.Success>(saved)
                    assertContentEquals(raster(40, 30, "jpg"), sink.toByteArray())
                }
                assertEquals(listOf("p16-sign.example.tiktokcdn.com", "p19-sign.example.tiktokcdn-us.com"),
                    f.server.requests.drop(before).map { it.headers["host"] })
            } }
        }
    }

    @Test fun cancelledSaveNeverStartsTheOriginalOrItsMirror() = fixture(respond = { request, socket ->
        if (request.headers["host"] == "www.tiktok.com") socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
        else socket.reply(body = raster(40, 30, "jpg"), headers = mapOf("Content-Type" to "image/jpeg"))
    }) { f -> inspected(f.inspectPost()).use { result ->
        val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
        val save = f.saver.newOperation(selection)
        val before = f.server.requests.size
        f.saver.beforeAdmission = { f.saver.cancel(save) }
        val sink = ByteArrayOutputStream()
        assertEquals(ImageFailure.CANCELLED,
            assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(save, sink) }).reason)
        assertEquals(before, f.server.requests.size)
        assertEquals(0, sink.size())
    } }

    @Test fun aPartialOriginalNeverGetsAppendedToItsSuccessfulMirror() {
        val saving = AtomicBoolean()
        val jpeg = raster(40, 30, "jpg")
        fixture(respond = { request, socket -> when {
            request.headers["host"] == "www.tiktok.com" -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
            saving.get() && request.headers["host"].orEmpty().startsWith("p16-") ->
                socket.reply(body = jpeg.copyOf(jpeg.size - 2), declared = jpeg.size.toLong(), headers = mapOf("Content-Type" to "image/jpeg"))
            else -> socket.reply(body = jpeg, headers = mapOf("Content-Type" to "image/jpeg"))
        } }) { f -> inspected(f.inspectPost()).use { result ->
            saving.set(true)
            val sink = ByteArrayOutputStream()
            val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
            assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
            assertContentEquals(jpeg, sink.toByteArray())
        } }
    }

    @Test fun refusedResizeFallsBackToOriginalAndAnExhaustedBudgetKeepsEveryPhotoSelectable() {
        // Hand-made repeated attachment data, not a real post. Each original is about 256 KiB.
        val item = """{"imageURL":{"urlList":["https://p16-sign.example.tiktokcdn.com/synthetic%s~tplv-photomode-image.jpeg?sig=fake"]}}"""
        val images = (1..41).joinToString(",") { item.format(it) }
        val json = """{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"900000001","imagePost":{"images":[$images]}}}}}}"""
        val jpeg = raster(40, 30, "jpg")
        val comment = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(65533)
        val large = jpeg.copyOfRange(0, 2) + comment + comment + comment + comment + jpeg.copyOfRange(2, jpeg.size)
        fixture(respond = { request, socket -> when {
            request.headers["host"] == "www.tiktok.com" -> socket.reply(body = tikTokPage(json), headers = mapOf("Content-Type" to "text/html"))
            request.target.contains("zoomcover") -> socket.reply(403)
            else -> socket.reply(body = large, headers = mapOf("Content-Type" to "image/jpeg"))
        } }) { f -> inspected(f.inspectPost()).use { result ->
            assertEquals(40, result.inspection.items.size)
            assertEquals(1, result.discovery.cappedCount)
            assertTrue(result.discovery.notPreviewedCount > 0)
            assertTrue(result.inspection.items.all { it.renditions.size == 1 })
            assertTrue(result.previews.size < 40)
            assertTrue(f.server.requests.any { it.target.contains("zoomcover") })
            assertTrue(f.server.requests.any { it.target.contains("photomode-image") })
            val ref = assertIs<MediaRendition.Image>(result.inspection.items.last().renditions.single()).ref
            assertNotNull(result.inspection.selection(ref))
        } }
    }

    @Test fun arbitraryPageAndCdnRedirectsAndPrivateAddressesAreNeverRequested() {
        for (pageRedirect in listOf(false, true)) fixture(respond = { request, socket ->
            if (pageRedirect || request.headers["host"] != "www.tiktok.com")
                socket.reply(302, headers = mapOf("Location" to "https://evil.example/photo.jpg"))
            else socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html"))
        }) { f ->
            val result = f.inspectPost()
            if (result is MediaInspectResult.Success) result.close() else assertIs<MediaInspectResult.Failure>(result)
            assertTrue(f.server.requests.none { it.headers["host"] == "evil.example" })
        }
        fixture(resolver = { host -> if (host == "www.tiktok.com") listOf(PUBLIC_V4) else listOf(InetAddress.getByName("127.0.0.1")) },
            respond = { _, socket -> socket.reply(body = tikTokPage(), headers = mapOf("Content-Type" to "text/html")) }) { f ->
            inspected(f.inspectPost()).use { result ->
                assertTrue(result.previews.isEmpty())
                val sink = ByteArrayOutputStream()
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                assertEquals(ImageFailure.SOURCE_REFUSED,
                    assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }).reason)
                assertEquals(0, sink.size())
                assertTrue(f.server.requests.all { it.headers["host"] == "www.tiktok.com" })
            }
        }
    }

    @Test fun tikTokShortLinksUseExistingBoundedResolver() = fixture(respond = { _, socket ->
        socket.reply(302, headers = mapOf("Location" to post))
    }) { f ->
        val result = runBlocking { ShortLinkResolver(f.http.client).resolve(assertNotNull(LinkInput.parse("https://vm.tiktok.com/Synthetic/"))) }
        assertEquals(post, assertIs<ShortLinkOutcome.Resolved>(result).link.rawUrl)
        assertEquals(1, f.server.requests.size)
    }
}
