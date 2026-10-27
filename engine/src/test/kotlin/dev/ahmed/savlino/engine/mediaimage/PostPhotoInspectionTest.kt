package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** Recorded page shapes and generated JPEGs, through the existing isolated TLS transport; no public network. */
class PostPhotoInspectionTest : PublicImageTestBase() {
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val telegram = "https://telegram.me/synthetic_channel/123?single"
    private val bluesky = "https://bsky.app/profile/synthetic.test/post/3synthetic001"
    private fun page(site: String, name: String) = javaClass.getResourceAsStream("/$site/$name.${if (site == "telegram") "html" else "json"}")!!.use { it.readBytes() }

    private fun fixturePost(site: String, name: String, image: ByteArray = raster(40, 30, "jpg"),
        redirectImage: Boolean = false, chunkedImage: Boolean = false, run: (PublicImageFixture) -> Unit) = fixture(respond = { request, socket ->
        when {
            request.headers["host"] == "t.me" -> socket.reply(body = page(site, name), headers = mapOf("Content-Type" to "text/html"))
            request.target.startsWith("/xrpc/com.atproto.identity.resolveHandle?") ->
                socket.reply(body = page("bluesky", "resolve-handle"), headers = mapOf("Content-Type" to "application/json"))
            request.headers["host"] == "public.api.bsky.app" -> socket.reply(body = page(site, name), headers = mapOf("Content-Type" to "application/json"))
            redirectImage -> socket.reply(302, headers = mapOf("Location" to "https://example.org/stolen.jpg"))
            else -> socket.reply(body = image, headers = mapOf("Content-Type" to "image/jpeg"), chunked = chunkedImage)
        }
    }, test = run)
    private fun PublicImageFixture.inspect(site: String, directDid: Boolean = false): MediaInspectResult = runBlocking {
        val route = if (site == "telegram") MediaSourceRoute.TELEGRAM else MediaSourceRoute.BLUESKY
        val url = if (site == "telegram") telegram else if (directDid) bluesky.replace("synthetic.test", did) else bluesky
        MediaSourceInspector(inspector, LegacyMediaInspectionAdapter(FakeEngine())).inspect(imageSource(url), "post", route)
    }

    @Test fun oversizedPreviewsLeaveSelectablePhotosAndSaveValidatesTheOriginal() {
        val jpeg = raster(40, 30, "jpg")
        val comment = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(65533)
        val oversized = jpeg.copyOfRange(0, 2) + comment + comment + comment + comment + jpeg.copyOfRange(2, jpeg.size)
        for (site in listOf("telegram", "bluesky")) for (name in listOf("single", "album")) for (chunked in listOf(false, true)) {
            fixturePost(site, name, image = oversized, chunkedImage = chunked) { f ->
                inspected(f.inspect(site)).use { result ->
                    assertEquals(if (name == "single") 1 else 2, result.inspection.items.size)
                    assertNull(result.preview)
                    assertTrue(result.previews.isEmpty())
                    assertEquals(result.inspection.items.size, result.discovery.notPreviewedCount)
                    val image = assertIs<MediaRendition.Image>(result.inspection.items.last().renditions.single())
                    assertEquals(Size.Unknown, image.image.size)
                    assertNull(image.image.width)
                    assertNull(image.image.proof)
                    val before = f.server.requests.size
                    val sink = ByteArrayOutputStream()
                    val selection = assertNotNull(result.inspection.selection(image.ref))
                    assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                    assertContentEquals(oversized, sink.toByteArray())
                    assertEquals(before + 1, f.server.requests.size)
                    if (site == "bluesky") {
                        assertTrue(f.server.requests.dropLast(1).filter { it.headers["host"] == "cdn.bsky.app" }
                            .all { it.target.startsWith("/img/feed_thumbnail/") })
                        assertTrue(f.server.requests.last().target.startsWith("/img/feed_fullsize/"))
                    }
                }
            }
        }
    }
    @Test fun anOversizedUnvalidatedPreviewNeverBypassesSaveValidation() {
        for (site in listOf("telegram", "bluesky")) fixturePost(site, "single", image = ByteArray(192 * 1024 + 1) { 32 }) { f ->
            inspected(f.inspect(site)).use { result ->
                assertNull(result.preview)
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val failed = assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(ImageFailure.INVALID_IMAGE, failed.reason)
                assertEquals(0, sink.size())
            }
        }
    }
    @Test fun telegramPageRedirectIsPrivateAndNeverFollowed() = fixture(respond = { _, socket ->
        socket.reply(302, headers = mapOf("Location" to "https://t.me/synthetic_channel"))
    }) { f ->
        failed(f.inspect("telegram"), ImageFailure.PRIVATE, ImagePhase.PAGE)
        assertEquals(1, f.server.requests.size)
    }
    @Test fun singlePhotoIsValidatedAndHandleResolutionIsSkippedForDid() {
        for (site in listOf("telegram", "bluesky")) for (directDid in listOf(false, true)) {
            fixturePost(site, "single") { f -> inspected(f.inspect(site, directDid)).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(MediaImageFormat.JPEG, firstRendition(result).image.format)
                assertEquals(if (site == "bluesky") Size.Unknown else Size.Exact(raster(40, 30, "jpg").size.toLong()), firstRendition(result).image.size)
                if (site == "bluesky") assertTrue(f.server.requests.last().target.startsWith("/img/feed_thumbnail/"))
                val pages = f.server.requests.filter { it.target.startsWith("/xrpc/") || it.headers["host"] == "t.me" }
                assertEquals(if (site == "bluesky" && !directDid) 2 else 1, pages.size)
                if (site == "telegram") {
                    assertEquals("Synthetic caption", result.inspection.items.single().metadata.title)
                    assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
                    assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                    assertEquals(1, result.previews.size)
                    assertNull(result.videoSource)
                    assertEquals("/synthetic_channel/123?embed=1&mode=tme", pages.single().target)
                    assertTrue(pages.single().headers["user-agent"].orEmpty().contains("Mozilla"))
                } else assertTrue(pages.last().target.endsWith("&depth=0"))
            } }
        }
    }
    @Test fun albumsExposeRealPhotosAndSaveOnlyTheConfirmedSecondPhoto() {
        for (site in listOf("telegram", "bluesky")) fixturePost(site, "album") { f ->
            inspected(f.inspect(site)).use { result ->
                assertEquals(2, result.inspection.items.size)
                val second = assertIs<MediaRendition.Image>(result.inspection.items[1].renditions.single())
                // Bluesky only fetched its returned thumbnail: full-size bytes/dimensions are not yet known.
                if (site == "bluesky") assertEquals(Size.Unknown, second.image.size)
                val before = f.server.requests.size
                val selection = assertNotNull(result.inspection.selection(second.ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(raster(40, 30, "jpg"), sink.toByteArray())
                assertEquals(before + 1, f.server.requests.size)
                assertTrue(f.server.requests.last().target.endsWith(if (site == "telegram") "syntheticPhoto2" else "bafkreisynthetic2@jpeg"))
                if (site == "bluesky") assertTrue(f.server.requests.last().target.startsWith("/img/feed_fullsize/plain/"))
            }
        }
    }
    @Test fun videoHandsBackOriginalLinkWithoutFetchingPosterAndNoMediaIsTerminal() {
        for (site in listOf("telegram", "bluesky")) {
            fixturePost(site, "video") { f ->
                val redirect = assertIs<MediaInspectResult.VideoRedirect>(f.inspect(site))
                assertFalse(redirect.photosUnavailable)
                assertTrue(redirect.source.matches(imageSource(if (site == "telegram") telegram else bluesky)))
                assertTrue(f.server.requests.none { it.headers["host"] in setOf("cdn1.telesco.pe", "cdn.bsky.app") })
            }
            for ((name, reason) in listOf("no-media" to ImageFailure.NO_MEDIA, "private" to ImageFailure.PRIVATE))
                fixturePost(site, name) { f -> failed(f.inspect(site), reason, ImagePhase.PAGE) }
        }
    }
    @Test fun byteSignatureAndCdnRedirectPolicyApplyToNewReaders() {
        for (site in listOf("telegram", "bluesky")) {
            fixturePost(site, "single", image = "not an image".toByteArray()) { f ->
                assertIs<MediaInspectResult.Failure>(f.inspect(site))
            }
            fixturePost(site, "single", redirectImage = true) { f ->
                failed(f.inspect(site), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
                assertTrue(f.server.requests.none { it.headers["host"] == "example.org" })
            }
        }
    }
    @Test fun telegramPrivateStatusesDropStatusAndOtherHttpFailuresPreserveIt() {
        for (status in listOf(401, 403, 404, 410, 500)) fixture(respond = { _, socket -> socket.reply(status) }) { f ->
            val result = failed(f.inspect("telegram"), if (status == 500) ImageFailure.HTTP else ImageFailure.PRIVATE, ImagePhase.PAGE)
            assertEquals(if (status == 500) 500 else null, result.statusCode)
            assertNull(result.fallback)
            assertEquals(1, f.server.requests.size)
        }
    }
    @Test fun telegramRefusedPhotoKeepsParserImagePhaseAndMakesNoAssetRequest() = fixture(respond = { _, socket ->
        socket.reply(body = page("telegram", "single").toString(Charsets.UTF_8)
            .replace("cdn1.telesco.pe", "example.org").toByteArray(), headers = mapOf("Content-Type" to "text/html"))
    }) { f ->
        val result = failed(f.inspect("telegram"), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
        assertNull(result.statusCode)
        assertNull(result.fallback)
        assertEquals(1, f.server.requests.size)
    }
    @Test fun telegramMixedPhotosFallBackAfterAssetOrDecodeFailure() {
        val mixed = page("telegram", "single").toString(Charsets.UTF_8)
            .replace("Synthetic caption</div>", "Synthetic caption</div><div class='tgme_widget_message_video_player'></div>")
            .toByteArray()
        for (decodeFailure in listOf(false, true)) {
            val decoder = if (decodeFailure) ImagePreviewDecoder { _, _, _, _ -> ImageDecodeResult.Failure(ImageFailure.DECODE) }
                else JdkPreviewDecoder()
            fixture(decoder = decoder, respond = { request, socket ->
                if (request.headers["host"] == "t.me")
                    socket.reply(body = mixed, headers = mapOf("Content-Type" to "text/html"))
                else if (decodeFailure) socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
                else socket.reply(404)
            }) { f ->
                val redirect = assertIs<MediaInspectResult.VideoRedirect>(f.inspect("telegram"))
                assertTrue(redirect.source.matches(imageSource(telegram)))
                assertTrue(redirect.photosUnavailable)
                assertEquals(2, f.server.requests.size)
            }
        }
    }
}
