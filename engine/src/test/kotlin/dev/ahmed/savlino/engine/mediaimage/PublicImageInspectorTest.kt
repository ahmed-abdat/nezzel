package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class PublicImageInspectorTest : PublicImageTestBase() {
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
            contexts.set(f.store, object : LinkedHashMap<MediaHttpPostContext, StoredImageReference>() {
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
