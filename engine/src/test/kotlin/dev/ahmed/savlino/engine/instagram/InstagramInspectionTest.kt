package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class InstagramInspectionTest : PublicImageTestBase() {
    private val html = mapOf("Content-Type" to "text/html; charset=utf-8")
    private val jpeg = mapOf("Content-Type" to "image/jpeg")
    private val photoTarget = igImagePath(1)

    /** The post page at its canonical /p/<code>/ path, photos on the IG CDN; everything else is 404. */
    private fun ig(page: ByteArray = igPage(), image: ByteArray = raster(40, 30, "jpg"),
        override: (RecordedMediaRequest, SSLSocket) -> Boolean = { _, _ -> false }): (RecordedMediaRequest, SSLSocket) -> Unit =
        { request, socket ->
            val host = request.headers["host"].orEmpty()
            if (!override(request, socket)) when {
                host == "www.instagram.com" && request.target == IG_PAGE_TARGET -> socket.reply(body = page, headers = html)
                (host == IG_CDN || host.endsWith(".cdninstagram.com")) && request.target.startsWith("/v/") ->
                    socket.reply(body = image, headers = jpeg)
                else -> socket.reply(404)
            }
        }

    private fun PublicImageFixture.inspectIg(url: String = IG_POST, revision: Long = 1) = runBlocking {
        inspector.inspect(inspector.newOperation(imageSource(url, revision), IMAGE_IDENTITY), ImageSourceType.INSTAGRAM)
    }

    @Test fun gifCarouselOffersAndSavesBothOriginalAttachments() {
        val page = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(1), igPhoto(2)))))
            .toString(Charsets.UTF_8).replace(".jpg", ".gif").toByteArray()
        val bytes = animatedGif()
        fixture(respond = ig(page = page, image = bytes)) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(2, result.inspection.items.size)
                for (item in result.inspection.items) {
                    val rendition = assertIs<MediaRendition.Image>(item.renditions.single())
                    assertEquals("gif", rendition.image.format.extension)
                    val sink = ByteArrayOutputStream()
                    val selection = assertNotNull(result.inspection.selection(rendition.ref))
                    val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                    assertEquals("image/gif", saved.image.format.mimeType)
                    assertContentEquals(bytes, sink.toByteArray())
                }
            }
        }
    }

    @Test fun photoPostIsOneCanonicalPageReadAndOneOriginalPhotoWithoutCookies() {
        fixture(respond = ig()) { f ->
            inspected(f.inspectIg("https://instagram.com/p/$IG_CODE?igsh=Synthetic")).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals(40, firstRendition(result).image.width)
                val metadata = result.inspection.items.single().metadata
                assertEquals("Synthetic caption line", metadata.title)
                assertEquals("Synthetic Author", metadata.uploader)
                assertEquals(listOf(IG_PAGE_TARGET, photoTarget), f.server.requests.map { it.target })
                assertEquals(listOf("www.instagram.com", IG_CDN), f.server.requests.map { it.headers["host"] })
                val page = f.server.requests[0]
                assertEquals("navigate", page.headers["sec-fetch-mode"])
                assertTrue(page.headers["user-agent"].orEmpty().contains("Windows NT"))
                assertTrue(page.headers["accept"].orEmpty().startsWith("text/html"))
                for (request in f.server.requests) {
                    assertNull(request.headers["cookie"]); assertNull(request.headers["authorization"])
                    assertNull(request.headers["referer"]); assertEquals("identity", request.headers["accept-encoding"])
                }
            }
        }
    }

    @Test fun singlePhotoKeepsTheFull1024PreviewDecoder() {
        fixture(respond = ig(image = raster(1600, 1200, "jpg"))) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(1024, assertNotNull(result.preview).width)
                assertEquals(768, assertNotNull(result.preview).height)
            }
        }
    }

    @Test fun tenPhotosUseSmallRenditionsAndSaveTheOriginal() {
        val page = igPage(igPolaris(media = igMedia(8, children = (1..10).map { igPhoto(it) })))
        val original = raster(1600, 1200, "jpg")
        val thumb = raster(200, 150, "jpg")
        fixture(respond = { request, socket ->
            when {
                request.target == IG_PAGE_TARGET -> socket.reply(body = page, headers = html)
                request.target.contains("_s640x640") -> socket.reply(body = thumb, headers = jpeg)
                else -> socket.reply(declared = 3L * 1024 * 1024, headers = jpeg)
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(10, result.previews.size)
                assertEquals(0, result.discovery.unavailableCount)
                assertTrue(f.server.requests.drop(1).all { it.target.contains("_s640x640") })
                assertNull(firstRendition(result).image.proof, "thumbnail proof is not the original's proof")
            }
        }
        fixture(respond = { request, socket ->
            when {
                request.target == IG_PAGE_TARGET -> socket.reply(body = page, headers = html)
                request.target.contains("_s640x640") -> socket.reply(body = thumb, headers = jpeg)
                else -> socket.reply(body = original, headers = jpeg)
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(original, sink.toByteArray())
                assertEquals(1600, saved.image.width)
                assertEquals(igImagePath(1), f.server.requests.last().target)
            }
        }
    }

    @Test fun webpPreviewAndJpegOriginalSaveAsJpeg() {
        val page = igPage(igPolaris(media = igMedia(8, children = (1..2).map { igPhoto(it) })))
        // Real synthetic 1x1 WebP; JDK ImageIO lacks WebP, so only pixel decoding is substituted.
        val webp = java.util.Base64.getDecoder().decode(
            "UklGRjYAAABXRUJQVlA4ICoAAABwAQCdASoBAAEAAUAmJaACdAGgAAD+EwVer0ZX/0Yf/+GH//hh/eEAAAA=")
        val original = raster(800, 600, "jpg")
        val decoder = ImagePreviewDecoder { _, mime, _, _ ->
            assertEquals("image/webp", mime)
            ImageDecodeResult.Success(JdkOwnedPreview(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)))
        }
        fixture(decoder = decoder, respond = { request, socket ->
            when {
                request.target == IG_PAGE_TARGET -> socket.reply(body = page, headers = html)
                request.target.contains("_s640x640") -> socket.reply(body = webp, headers = mapOf("Content-Type" to "image/webp"))
                else -> socket.reply(body = original, headers = jpeg)
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(original, sink.toByteArray())
                assertEquals("jpg", saved.image.format.extension)
                assertEquals("image/jpeg", saved.image.format.mimeType)
                assertEquals(800, saved.image.width)
                assertEquals(igImagePath(1), f.server.requests.last().target)
            }
        }
    }

    @Test fun photoCarouselCarriesEveryValidatedPhotoAndPreview() {
        fixture(respond = ig(page = igPage(igPolaris(media = igMedia(8, children = (1..5).map { igPhoto(it) }))))) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(ImageDiscovery(5, DiscoveryCompleteness.COMPLETE), result.discovery)
                assertEquals(5, result.inspection.items.size)
                assertEquals(1, result.inspection.items[0].renditions.size)
                assertTrue(result.inspection.items.all { it.renditions.size == 1 })
                assertEquals(5, result.previews.size)
                assertEquals(listOf(IG_PAGE_TARGET) + (1..5).map { igImagePath(it, "_s640x640") }, f.server.requests.map { it.target })
            }
        }
    }

    @Test fun firstTwentyPhotosOnlyWithHonestCapAndNoExtraFetch() {
        fixture(respond = ig(page = igPage(igPolaris(media = igMedia(8, children = (1..23).map { igPhoto(it) }))))) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(20, result.inspection.items.size)
                assertEquals(20, result.previews.size)
                assertEquals(3, result.discovery.cappedCount)
                assertEquals(21, f.server.requests.size)
            }
        }
    }

    @Test fun badSiblingIsDroppedAndLaterPhotosRemainSelectable() {
        val page = igPage(igPolaris(media = igMedia(8, children = (1..3).map { igPhoto(it) })))
        fixture(respond = { request, socket ->
            when (request.target) {
                IG_PAGE_TARGET -> socket.reply(body = page, headers = mapOf("Content-Type" to "text/html"))
                igImagePath(2, "_s640x640") -> socket.reply(status = 404)
                else -> socket.reply(body = raster(40, 30, "jpg"), headers = mapOf("Content-Type" to "image/jpeg"))
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(listOf(0, 2), result.inspection.items.map { it.position })
                assertEquals(2, result.previews.size)
                assertEquals(1, result.discovery.unavailableCount)
                assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
                val chosen = result.inspection.items.last().renditions.single()
                val selection = assertNotNull(result.inspection.selection(chosen.ref))
                val sink = java.io.ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertTrue(sink.size() > 0)
                assertEquals(igImagePath(3), f.server.requests.last().target)
            }
        }
    }

    @Test fun laterFetchDeadlinePreservesEarlierValidatedPhoto() {
        lateinit var operation: ImageInspectionOperation
        val page = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(1), igPhoto(2)))))
        fixture(respond = ig(page = page, override = { request, socket ->
            if (request.target == igImagePath(2, "_s640x640")) {
                operation.token.stop(MediaHttpFailure.TIMEOUT)
                socket.close()
                true
            } else false
        })) { f ->
            operation = f.inspector.newOperation(imageSource(IG_POST), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.INSTAGRAM) }).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(1, result.previews.size)
                assertEquals(1, result.discovery.unavailableCount)
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                assertIs<ImageSaveResult.Success>(runBlocking {
                    f.saver.transfer(f.saver.newOperation(selection), ByteArrayOutputStream())
                })
            }
        }
    }

    @Test fun budgetLimitedPreviewIsReportedSeparatelyAndLaterSiblingStillLoads() {
        val page = igPage(igPolaris(media = igMedia(8, children = (1..3).map { igPhoto(it, sized = true) })))
        fixture(respond = ig(page = page, override = { request, socket ->
            if (request.target == igImagePath(2, "_s640x640")) {
                socket.reply(declared = 192L * 1024 + 1, headers = jpeg); true
            } else false
        })) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(2, result.previews.size)
                assertEquals(1, result.discovery.notPreviewedCount)
                assertEquals(0, result.discovery.unavailableCount)
                assertEquals(listOf(0, 2), result.inspection.items.map { it.position })
            }
        }
    }

    @Test fun allPhotosFailingInMixedPostCarriesANoticeThroughVideoHandoff() {
        val page = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(), igVideo()))))
        for (decodeFails in listOf(false, true)) fixture(decoder = if (decodeFails)
            ImagePreviewDecoder { _, _, _, _ -> ImageDecodeResult.Failure(ImageFailure.DECODE) } else JdkPreviewDecoder(),
            respond = ig(page = page, override = { request, socket ->
                if (!decodeFails && request.target == photoTarget) { socket.reply(404); true } else false
            })) { f ->
            assertTrue(assertIs<MediaInspectResult.VideoRedirect>(f.inspectIg()).photosUnavailable)
        }
    }

    @Test fun videoOnlyPostsCarouselsAndReelLinksKeepTheirVideoRoute() {
        for (media in listOf(igMedia(2), igMedia(8, children = listOf(igVideo(), igVideo(8))))) {
            fixture(respond = ig(page = igPage(igPolaris(media = media)))) { f ->
                val link = "https://www.instagram.com/p/$IG_CODE/?igsh=Synthetic"
                val redirect = assertIs<MediaInspectResult.VideoRedirect>(f.inspectIg(link, revision = 7))
                assertEquals(link, redirect.source.link.rawUrl)
                assertEquals(7, redirect.source.revision)
                assertEquals(listOf(IG_PAGE_TARGET), f.server.requests.map { it.target })
            }
        }
        // A reel/tv link is never read by this reader (the app routes it straight to yt-dlp).
        fixture(respond = ig()) { f ->
            failed(f.inspectIg("https://www.instagram.com/reel/$IG_CODE/"), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(0, f.server.requests.size)
        }
    }

    @Test fun loginRedirectAndGatedPostsAreNotPublic() {
        for (location in listOf("https://www.instagram.com/accounts/login/?next=%2Fp%2F$IG_CODE%2F", "/accounts/login/",
            "https://www.instagram.com/challenge/?next=/p/$IG_CODE/")) {
            fixture(respond = ig(override = { request, socket ->
                if (request.target == IG_PAGE_TARGET) { socket.reply(302, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                failed(f.inspectIg(), ImageFailure.PRIVATE, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size, "the login page is never fetched")
            }
        }
        fixture(respond = ig(page = igPage(igPolaris(media = null)))) { f ->
            failed(f.inspectIg(), ImageFailure.PRIVATE, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun otherRedirectsAreNeverFollowed() {
        for (location in listOf("https://www.instagram.com/accounts/login-evil/", "/challenge-x/","https://www.instagram.com/p/SynthPost02/", "https://instagram.com/p/$IG_CODE/",
            "https://evil.example/p/$IG_CODE/", igImageUrl(1))) {
            fixture(respond = ig(override = { request, socket ->
                if (request.target == IG_PAGE_TARGET) { socket.reply(301, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                failed(f.inspectIg(), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun missingMalformedAndOversizePagesAreTypedFailures() {
        fixture(respond = ig(override = { _, socket -> socket.reply(404); true })) { f ->
            assertEquals(404, failed(f.inspectIg(), ImageFailure.HTTP, ImagePhase.PAGE).statusCode)
        }
        for (page in listOf(igShell(), "<html>changed</html>".toByteArray())) fixture(respond = ig(page = page)) { f ->
            failed(f.inspectIg(), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
        fixture(respond = ig(override = { request, socket ->
            if (request.target == IG_PAGE_TARGET) { socket.reply(declared = 3L * 1024 * 1024 + 1, headers = html); true } else false
        })) { f -> failed(f.inspectIg(), ImageFailure.BUDGET, ImagePhase.PAGE) }
        fixture(respond = ig(override = { request, socket ->
            if (request.target == photoTarget) { socket.reply(declared = 2L * 1024 * 1024 + 1, headers = jpeg); true } else false
        })) { f -> failed(f.inspectIg(), ImageFailure.BUDGET, ImagePhase.IMAGE) }
    }

    @Test fun photoRedirectsMayNotLeaveTheInstagramCdn() {
        for (location in listOf("https://scontent.example.fbcdn.net${igImagePath(1)}", "https://www.instagram.com/p/$IG_CODE/",
            "https://$IG_CDN/o1/v/t16/f2/m86/Synthetic.mp4", "https://evil.example/a.jpg")) {
            fixture(respond = ig(override = { request, socket ->
                if (request.target == photoTarget) { socket.reply(302, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                failed(f.inspectIg(), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
                assertEquals(2, f.server.requests.size)
            }
        }
        // A redirect inside the allowlist (another IG CDN host) is followed.
        fixture(respond = ig(override = { request, socket ->
            if (request.target == photoTarget && request.headers["host"] == IG_CDN) {
                socket.reply(302, headers = mapOf("Location" to igImageUrl(1, host = "scontent-xyz1-1.cdninstagram.com"))); true
            } else false
        })) { f ->
            inspected(f.inspectIg()).close()
            assertEquals("scontent-xyz1-1.cdninstagram.com", f.server.requests.last().headers["host"])
        }
    }

    @Test fun transportAllowsOnlyTheCanonicalPostPageAndTheInstagramCdn() {
        fixture(respond = ig()) { f ->
            val client = f.http.client
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest(IG_POST, MediaHttpPurpose.PAGE)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest("https://scontent.example.fbcdn.net${igImagePath(1)}",
                    MediaHttpPurpose.IMAGE, hostRule = MediaHostRule.INSTAGRAM_CDN)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest(IG_POST, MediaHttpPurpose.IMAGE,
                    hostRule = MediaHostRule.INSTAGRAM_CDN)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetchInstagramPost(token, assertNotNull(InstagramPostIdentity.parse(
                    "https://www.instagram.com/reel/$IG_CODE/"))), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(client.fetchInstagramPost(token, assertNotNull(InstagramPostIdentity.parse(IG_POST))),
                    MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGES_AND_IMAGE).use { token ->
                assertFailure(client.fetchFacebookPage(token, IG_POST), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetchLinkedInPage(token, IG_POST), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(0, f.server.requests.size, "refused shapes make no request")
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertSuccess(client.fetchInstagramPost(token, assertNotNull(InstagramPostIdentity.parse(IG_POST)))).close()
                assertFailure(client.fetchInstagramPost(token, assertNotNull(InstagramPostIdentity.parse(IG_POST))),
                    MediaHttpFailure.RESOURCE_LIMIT)
            }
            assertEquals(1, f.server.requests.size, "at most one page per operation")
        }
    }

    @Test fun saveRefetchesTheSamePhotoFromTheInstagramCdnOnly() {
        val bytes = raster(48, 30, "jpg")
        fixture(respond = ig(image = bytes)) { f ->
            inspected(f.inspectIg()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(bytes, sink.toByteArray())
                assertEquals(imageDigest(bytes), assertNotNull(saved.image.proof).completeBodySha256)
                assertEquals(3, f.server.requests.size, "Save refetches the photo only")
                assertEquals(photoTarget, f.server.requests.last().target)
                assertEquals(IG_CDN, f.server.requests.last().headers["host"])
            }
        }
    }

    @Test fun aJpegNamedPhotoIsFetchedAndSavedFromTheSamePath() {
        val bytes = raster(44, 30, "jpg")
        val page = igPage(igPolaris(media = igMedia(candidates = igCandidates(ext = "jpeg"))))
        fixture(respond = ig(page = page, image = bytes)) { f ->
            inspected(f.inspectIg()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(bytes, sink.toByteArray())
                assertEquals(listOf(IG_PAGE_TARGET, igImagePath(1, ext = "jpeg"), igImagePath(1, ext = "jpeg")),
                    f.server.requests.map { it.target })
            }
        }
    }

    @Test fun anOffAllowlistCandidateIsAnImagePhaseFailureWithoutAnImageRequest() {
        fixture(respond = ig(page = igPage(igPolaris(media = igMedia(candidates = igCandidates(host = "scontent.example.fbcdn.net")))))) { f ->
            failed(f.inspectIg(), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun saveStaysOnTheInstagramCdnWhenItRedirectsAway() {
        val redirectSave = AtomicBoolean()
        fixture(respond = ig(override = { request, socket ->
            if (request.target == photoTarget && redirectSave.get()) {
                socket.reply(302, headers = mapOf("Location" to "https://scontent.example.fbcdn.net${igImagePath(1)}")); true
            } else false
        })) { f ->
            inspected(f.inspectIg()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                redirectSave.set(true)
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(0, sink.size())
                assertTrue(f.server.requests.none { it.headers["host"] == "scontent.example.fbcdn.net" })
            }
        }
    }

    @Test fun facadeRoutesInstagramToTheReaderAndOtherRoutesCannotBorrowIt() {
        fixture(respond = ig()) { f ->
            val facade = MediaSourceInspector(f.inspector, LegacyMediaInspectionAdapter(FakeEngine(InspectResult.Failure(DownloadError.Unsupported))))
            inspected(runBlocking { facade.inspect(imageSource(IG_POST), "ig", MediaSourceRoute.INSTAGRAM) }).close()
            assertEquals(2, f.server.requests.size)
            failed(runBlocking { facade.inspect(imageSource(POST, 2), "li-as-ig", MediaSourceRoute.INSTAGRAM) },
                ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            failed(runBlocking { facade.inspect(imageSource(IG_POST, 3), "ig-as-li", MediaSourceRoute.LINKEDIN) },
                ImageFailure.SOURCE_REFUSED)
            failed(runBlocking { facade.inspect(imageSource(IG_POST, 4), "ig-as-x", MediaSourceRoute.X) },
                ImageFailure.SOURCE_REFUSED)
            failed(runBlocking { facade.inspect(imageSource("https://www.instagram.com/synthetic_user/", 5), "profile-as-ig",
                MediaSourceRoute.INSTAGRAM) }, ImageFailure.SOURCE_REFUSED)
            assertEquals(2, f.server.requests.size, "mismatched routes make no request")
        }
    }
}
