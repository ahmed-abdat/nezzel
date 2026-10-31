package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class PublicImageInspectorTest : PublicImageTestBase() {
    @Test fun pinterestSignInWallFallsBackToTheSamePinsPublicWidget() {
        val pin = "100000000000000001"
        val widget = "/v3/pidgets/pins/info/?pin_ids=$pin"
        val image = "/564x/01/23/45/0123456789abcdef0123456789abcdef.jpg"
        val bytes = raster(40, 30, "jpg")
        for (status in listOf(302, 401, 403)) fixture(respond = { request, socket ->
            when (request.target) {
                "/pin/$pin/" -> socket.reply(status, headers = mapOf(
                    "Location" to "https://www.pinterest.com/login/", "Set-Cookie" to "session=ignored"))
                widget -> socket.reply(body = """{"status":"success","data":[{"id":"$pin","is_video":false,
                    "images":{"564x":{"url":"https://i.pinimg.com$image","width":564,"height":423}}}]}""".toByteArray(),
                    headers = mapOf("Content-Type" to "application/json"))
                image -> socket.reply(body = bytes, headers = mapOf("Content-Type" to "image/jpeg"))
                else -> error("unexpected fixture request")
            }
        }) { f ->
            val operation = f.inspector.newOperation(imageSource("https://www.pinterest.com/pin/$pin/"), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.PINTEREST) }).use { result ->
                val rendition = firstRendition(result)
                assertEquals(40, rendition.image.width)
                assertEquals(30, rendition.image.height)
                assertEquals(Size.Exact(bytes.size.toLong()), rendition.size)
                assertEquals("https://i.pinimg.com$image", f.store.resolve(
                    assertNotNull(result.inspection.selection(rendition.ref)))?.url)
            }
            assertEquals(listOf("/pin/$pin/", widget, image), f.server.requests.map { it.target })
            assertEquals("widgets.pinterest.com", f.server.requests[1].headers["host"])
            assertTrue(f.server.requests.all { it.headers["cookie"] == null })
        }
    }

    @Test fun pinterestWidgetFailurePreservesTheOriginalPageFailure() {
        val pin = "100000000000000001"
        for (status in listOf(302, 401, 403, 404, 500)) fixture(respond = { request, socket ->
            if (request.target == "/pin/$pin/") socket.reply(status,
                headers = mapOf("Location" to "https://www.pinterest.com/login/"))
            else socket.reply(500)
        }) { f ->
            val operation = f.inspector.newOperation(imageSource("https://www.pinterest.com/pin/$pin/"), IMAGE_IDENTITY)
            val result = failed(runBlocking { f.inspector.inspect(operation, ImageSourceType.PINTEREST) },
                if (status == 302) ImageFailure.PRIVATE else ImageFailure.HTTP, ImagePhase.PAGE)
            assertEquals(if (status == 302) null else status, result.statusCode)
            assertEquals(if (status in listOf(302, 401, 403)) 2 else 1, f.server.requests.size)
        }
    }

    @Test fun linkedInFivePhotoPostReturnsEveryRealImageInPageOrderWithOneSaveContext() {
        linkedInPhotos()
    }

    @Test fun linkedInFailedSiblingIsSkippedAndRemainingPhotosStillReportOfFive() {
        linkedInPhotos(failedPosition = 2)
    }

    @Test fun linkedInSinglePhotoKeepsCompleteProofAndBoundSave() {
        linkedInPhotos(count = 1)
    }

    @Test fun linkedInSixtyFourPhotoPostDoesNotInheritTheTwentyImageCarouselLimit() {
        linkedInPhotos(count = 64)
    }

    private fun linkedInPhotos(count: Int = 5, failedPosition: Int? = null) {
        val urls = (0 until count).map { "https://media.licdn.com/dms/image/synthetic-$it/feedshare-shrink_800/photo.jpg" }
        val bodies = urls.indices.map { raster(width = 32 + it, format = "jpg") }
        // Reverse JSON-LD order: only the post-owned DOM determines the attachment order.
        var position = 0
        val html = linkedInHtml(assets = urls.reversed(), domAssets = urls, og = urls.first(), claimed = count)
            .toString(Charsets.UTF_8).replace(Regex("<li>")) {
                "<li data-test-id=\"feed-images-content__list-item\" data-position=\"${position++}\" style=\"\">"
            }.toByteArray()
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/")) socket.reply(body = html, headers = mapOf("Content-Type" to "text/html"))
            else {
                val index = urls.indexOf("https://media.licdn.com${request.target}")
                if (index < 0 || index == failedPosition) socket.reply(403)
                else socket.reply(body = bodies[index], headers = mapOf("Content-Type" to "image/jpeg"))
            }
        }) { f ->
            val operation = f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.LINKEDIN) }).use { result ->
                val expectedPositions = urls.indices.filter { it != failedPosition }
                assertEquals(expectedPositions, result.inspection.items.map { it.position })
                assertEquals(expectedPositions.size, result.discovery.discoveredCount)
                assertEquals(count, result.discovery.claimedCount)
                assertEquals(if (failedPosition == null) 0 else 1, result.discovery.unavailableCount)
                assertEquals(if (failedPosition == null) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
                    result.discovery.completeness)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals(count + 1, f.server.requests.size, "every attachment fetched, author avatar excluded")
                val contexts = result.inspection.items.map { item ->
                    val rendition = assertIs<MediaRendition.Image>(item.renditions.single(), "no placeholder items")
                    val selection = assertNotNull(result.inspection.selection(rendition.ref))
                    val reference = assertNotNull(f.store.resolve(selection))
                    assertTrue(reference.linkedIn)
                    assertEquals(urls[item.position], reference.url)
                    assertEquals(32 + item.position, rendition.image.width)
                    assertEquals(Size.Exact(bodies[item.position].size.toLong()), rendition.size)
                    assertEquals(imageDigest(bodies[item.position]), assertNotNull(rendition.image.proof).completeBodySha256)
                    val context = assertNotNull(reference.postContext)
                    val sink = ByteArrayOutputStream()
                    assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                    assertContentEquals(bodies[item.position], sink.toByteArray())
                    assertEquals(POST, f.server.requests.last().headers["referer"])
                    context
                }
                assertTrue(contexts.all { it === contexts.first() }, "one post context shared by all Save references")
                assertEquals(count + 1 + expectedPositions.size, f.server.requests.size)
            }
        }
    }

    @Test fun outOfMemoryAfterPhotoVerdictStaysAnImageFailure() {
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/"))
                socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        }) { f ->
            val operation = f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY)
            // Fail the real context-binding allocation after the page verdict and image validation,
            // outside the transport/validator OOM catches, without allocating an actual memory bomb.
            val contexts = MediaReferenceStore::class.java.getDeclaredField("postContexts").apply { isAccessible = true }
            contexts.set(f.store, object : LinkedHashMap<MediaHttpPostContext, List<StoredImageReference>>() {
                override val size: Int get() = throw OutOfMemoryError("synthetic context allocation failure")
            })
            failed(runBlocking { f.inspector.inspect(operation, ImageSourceType.LINKEDIN) }, ImageFailure.BUDGET, ImagePhase.IMAGE)
            assertEquals(2, f.server.requests.size, "post and proven photo reached before the allocation failure")
            f.http.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { a ->
                f.http.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { b ->
                    assertNull(a.checkActive()); assertNull(b.checkActive())
                }
            }
        }
    }

    @Test fun aLinkOpenedWhileBothTransportSlotsAreHeldWaitsForOneInsteadOfFailing() = fixture { f ->
        f.http.control()
        val held = List(2) { f.http.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE) }
        val facade = MediaSourceInspector(f.inspector, LegacyMediaInspectionAdapter(FakeEngine()))
        val result = runBlocking {
            launch(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.delay(300); held[0].close() }
            facade.inspect(imageSource(), "after-busy", MediaSourceRoute.DIRECT_IMAGE)
        }
        inspected(result).close()
        held[1].close()
    }

    @Test fun directOpaquePathUsesActualMimeCompleteBytesAndMeasuredProofWithoutCreatingFiles() {
        val bytes = raster(87, 53)
        fixture(respond = { _, socket -> socket.reply(body = bytes, headers = mapOf("Content-Type" to "image/png"), chunked = true) }) { f ->
            f.http.control()
            val operation = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.DIRECT) }).use { result ->
                val rendition = firstRendition(result)
                assertEquals(Size.Exact(bytes.size.toLong()), rendition.size)
                assertEquals(imageDigest(bytes), assertNotNull(rendition.image.proof).completeBodySha256)
                assertEquals(87, rendition.image.width); assertEquals(53, rendition.image.height)
                assertEquals(MediaImageFormat.PNG, rendition.image.format)
                assertEquals(MediaKind.IMAGE, result.inspection.items.single().sourceKind)
                assertTrue(result.inspection.source.matches(operation.source))
                assertEquals(IMAGE_IDENTITY, result.inspection.identity)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertTrue(assertNotNull(result.preview).width <= 1024 && assertNotNull(result.preview).height <= 1024)
                assertEquals(2, f.server.requests.size, "paired control plus exactly one image")
                assertTrue(result.inspection.items.single().presets.isEmpty(), "no invented AV preset/Format")
            }
        }
    }

    @Test fun unsupportedMimeSignatureMismatchTruncatedAndAnimatedResponsesAreTypedFailures() {
        val png = raster()
        for ((bytes, mime, expected) in listOf(Triple("<svg/>".toByteArray(), "image/svg+xml", ImageFailure.UNSUPPORTED),
            Triple(png, "image/jpeg", ImageFailure.INVALID_IMAGE), Triple(png.copyOf(png.size - 1), "image/png", ImageFailure.INVALID_IMAGE),
            Triple(animatedPng(), "image/png", ImageFailure.ANIMATED))) {
            fixture(respond = { _, socket -> socket.reply(body = bytes, headers = mapOf("Content-Type" to mime)) }) { f ->
                f.http.control()
                failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) }, expected, ImagePhase.IMAGE)
                assertEquals(2, f.server.requests.size)
            }
        }
    }

    @Test fun oversizeAndHttp403StayImageFailuresWithoutAlternateClient() {
        for (status in listOf(200, 403)) fixture(respond = { _, socket ->
            socket.reply(status, declared = if (status == 200) 2097153 else 3)
        }) { f ->
            f.http.control()
            val result = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) },
                if (status == 200) ImageFailure.BUDGET else ImageFailure.HTTP, ImagePhase.IMAGE)
            if (status == 403) assertEquals(403, result.statusCode)
            assertEquals(2, f.server.requests.size, "no fallback/retry on bounded image failure")
        }
    }

    @Test fun dimensionBombNeverReachesDecoderAndNeverCreatesProof() {
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        val decoder = ImagePreviewDecoder { _, _, _, _ -> decoded.incrementAndGet(); ImageDecodeResult.Failure(ImageFailure.DECODE) }
        fixture(decoder, respond = { _, socket -> socket.reply(body = bombPng(20000, 1), headers = mapOf("Content-Type" to "image/png")) }) { f ->
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) }, ImageFailure.DIMENSIONS)
            assertEquals(0, decoded.get(), "dimension rejection precedes pixel decoder entry")
        }
    }

    @Test fun decoderFailureIsTypedAndHttpResourcesCloseBeforeReturning() {
        fixture(ImagePreviewDecoder { _, _, _, _ -> ImageDecodeResult.Failure(ImageFailure.DECODE) }) { f ->
            f.http.control()
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) }, ImageFailure.DECODE, ImagePhase.DECODE)
            // Both HTTP slots must be free after an unsuccessful inspection.
            f.http.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { a ->
                f.http.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { b -> assertNull(a.checkActive()); assertNull(b.checkActive()) }
            }
        }
    }

    @Test fun decoderClassInitErrorIsAFailedReadNotACrash() {
        for (error in listOf(NoClassDefFoundError("synthetic"), StackOverflowError())) {
            fixture(ImagePreviewDecoder { _, _, _, _ -> throw error }) { f ->
                failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) },
                    ImageFailure.SOURCE_REFUSED, ImagePhase.DECODE)
            }
        }
    }

    @Test fun opaqueReferencesAndResultErrorsNeverRenderSourceOrSignedQueries() {
        fixture { f ->
            val operation = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation) }).use { result ->
                val rendition = firstRendition(result)
                assertFalse(rendition.ref.item.itemId.contains("https:"))
                assertFalse(rendition.ref.renditionId.contains("synthetic"))
                for (value in listOf(operation, result, result.preview, result.inspection, rendition.ref, rendition.image)) {
                    val text = value.toString()
                    assertFalse(text.contains("media.licdn.com") || text.contains("sig=") || text.contains("https:"))
                }
            }
        }
    }

    @Test fun facadeKeepsGenuineLegacyRowsAndOnlyBridgesActualReturnedItemIdentity() = fixture { f ->
        val source = imageSource("https://media.licdn.com/video?sig=synthetic")
        val row = Format("returned-av-id", "mp4", true, true)
        val legacyInspection = Inspection(source, "legacy-fixture", listOf(row))
        val adapter = LegacyMediaInspectionAdapter(FakeEngine(InspectResult.Success(legacyInspection)))
        val facade = MediaSourceInspector(f.inspector, adapter)
        val result = assertIs<MediaInspectResult.Legacy>(runBlocking { facade.inspect(source, "av-only", MediaSourceRoute.LEGACY) })
        assertSame(legacyInspection, assertIs<InspectResult.Success>(result.result).inspection)
        assertNull(result.bridge, "legacy Inspection has no returned source item ID; do not invent one")
        val identity = MediaAdapterIdentity("legacy", 1, "legacy-fixture", 1)
        val bridged = assertIs<MediaInspectResult.Legacy>(runBlocking { adapter.inspect(source, "genuine-av", "engine-returned-item", identity) })
        assertEquals("engine-returned-item", assertNotNull(bridged.bridge).inspection.items.single().ref.itemId)
        assertIs<MediaRendition.Audiovisual>(bridged.bridge!!.inspection.items.single().renditions.single())
        assertTrue(f.server.requests.isEmpty(), "legacy routing does not probe the media HTTP boundary")
    }
}
