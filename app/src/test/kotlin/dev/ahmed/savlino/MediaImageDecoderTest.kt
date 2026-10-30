package dev.ahmed.savlino

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.test.*

/** Native graphics exercises real encoded pixels; not ART memory or phone acceptance. */
@RunWith(RobolectricTestRunner::class)
// SDK coverage: Only AVIF branches on API (engine/.../mediaimage/AndroidImagePreviewDecoder.kt:36); its API29 override stays below.
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaImageDecoderTest {
    private val decoder = AndroidImagePreviewDecoder()
    @Suppress("DEPRECATION")
    private fun encoded(width: Int = 80, height: Int = 60, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(36, 151, 124))
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    @Test @Config(sdk = [29]) fun avifIsUnsupportedBeforeCallingThePlatformCodec() {
        fun box(type: String, body: ByteArray) = ByteBuffer.allocate(body.size + 8)
            .putInt(body.size + 8).put(type.toByteArray()).put(body).array()
        val ispe = box("ispe", ByteBuffer.allocate(12).putInt(0).putInt(64).putInt(48).array())
        val bytes = box("ftyp", "avif".toByteArray() + ByteArray(4)) +
            box("meta", ByteArray(4) + box("iprp", box("ipco", ispe))) + box("mdat", byteArrayOf(1))
        assertIs<ImageValidationResult.Valid>(ImageRepresentationValidator().validate(bytes, "image/avif"))
        rejects(bytes, ImageFailure.UNSUPPORTED, "image/avif")
        assertEquals(ImageFailure.UNSUPPORTED, assertIs<ImageDecodeResult.Failure>(
            decoder.decodeThumbnail(bytes, "image/avif", { true }, bytes.size)).reason)
    }

    @Test fun animatedGifProducesOnlyTheFirstFrameAsABitmap() {
        val bytes = "47494638396101000100800000ff00000000ff21ff0b4e45545343415045322e30030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        preview(bytes, "image/jpeg").use { image ->
            assertEquals(1, image.width); assertEquals(1, image.height)
            assertEquals(Color.RED, image.bitmap.getPixel(0, 0))
        }
        val thumbnail = assertIs<ImageDecodeResult.Success>(decoder.decodeThumbnail(bytes, "image/gif", { true }, bytes.size))
        assertIs<AndroidImagePreview>(thumbnail.preview).use { image ->
            assertEquals(Color.RED, image.bitmap.getPixel(0, 0))
        }
    }

    @Test fun carouselThumbnailDecodesRealPixelsWithin256AxisAndAllocationBound() {
        val bytes = encoded(1600, 1200)
        val decoded = runBlocking { withContext(Dispatchers.IO) {
            decoder.decodeThumbnail(bytes, "image/png", { true }, bytes.size)
        } }
        val image = assertIs<AndroidImagePreview>(assertIs<ImageDecodeResult.Success>(decoded).preview)
        try {
            assertTrue(image.width in 1..256 && image.height in 1..256)
            assertTrue(image.allocationBytes <= 256 * 256 * 4L)
            assertFalse(image.bitmap.isRecycled)
            assertEquals(Color.rgb(36, 151, 124), image.bitmap.getPixel(0, 0))
        } finally { image.close() }
        assertTrue(image.isClosed)
    }

    private fun decode(bytes: ByteArray, mime: String = "image/png", active: () -> Boolean = { true }): ImageDecodeResult =
        runBlocking { withContext(Dispatchers.IO) { decoder.decode(bytes, mime, active, bytes.size) } }
    private fun preview(bytes: ByteArray = encoded(), mime: String = "image/png") =
        assertIs<AndroidImagePreview>(assertIs<ImageDecodeResult.Success>(decode(bytes, mime)).preview)
    private fun rejects(bytes: ByteArray, reason: ImageFailure, mime: String = "image/png") =
        assertEquals(reason, assertIs<ImageDecodeResult.Failure>(decode(bytes, mime)).reason)
    private fun chunk(name: String, body: ByteArray): ByteArray {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(body) }.value.toInt()
        return ByteBuffer.allocate(body.size + 12).putInt(body.size).put(type).put(body).putInt(crc).array()
    }
    private fun pngHeader(width: Int, height: Int): ByteArray =
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(8, 2, 0, 0, 0)).array()) +
            chunk("IEND", byteArrayOf())

    /** Complete, real, highly compressed grayscale PNG: small body, large raster if decoded at full size. */
    private fun compressedGrayPng(width: Int, height: Int, bitDepth: Int = 8, value: Int = 151): ByteArray {
        val row = ByteArray(1 + ((width.toLong() * bitDepth + 7) / 8).toInt()) { if (it == 0) 0 else value.toByte() }
        val pixels = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(pixels, java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)).use { out ->
            repeat(height) { out.write(row) }
        }
        return byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(bitDepth.toByte(), 0, 0, 0, 0)).array()) +
            chunk("IDAT", pixels.toByteArray()) + chunk("IEND", byteArrayOf())
    }

    @Suppress("DEPRECATION")
    @Test fun measuredLengthExcludesSpareCapacityFromDecode() {
        val body = encoded()
        val allocation = body.copyOf(2 * 1024 * 1024)
        val result = runBlocking { withContext(Dispatchers.IO) {
            decoder.decode(allocation, "image/png", { true }, body.size)
        } }
        val preview = assertIs<ImageDecodeResult.Success>(result).preview
        assertEquals(80, preview.width)
        preview.close()
    }

    @Test fun jpegPngAndStillWebpDecodeRealPixelsInSoftwareSrgbAndRecycleExactlyOnce() {
        for ((format, mime) in listOf(Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.WEBP to "image/webp")) {
            val preview = preview(encoded(format = format), mime)
            try {
                assertEquals(80, preview.width); assertEquals(60, preview.height)
                assertEquals(Bitmap.Config.ARGB_8888, preview.bitmap.config)
                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), preview.bitmap.colorSpace)
                val pixel = preview.bitmap.getPixel(20, 20)
                assertTrue(Color.green(pixel) in 140..162)
                assertTrue(Color.red(pixel) in 25..48)
                assertFalse(preview.isClosed)
            } finally { preview.close() }
            assertTrue(preview.isClosed); assertTrue(preview.bitmap.isRecycled)
            preview.close()
            assertTrue(preview.bitmap.isRecycled)
        }
    }

    @Test fun sampledPreviewBoundsBothAxesAndOwnedAllocation() {
        // Power-of-two codec sampling (2) decodes straight to <=1024 px; no full-size or scale-down raster.
        preview(encoded(1800, 900)).use { preview ->
            assertEquals(900, preview.width); assertEquals(450, preview.height)
            assertTrue(preview.allocationBytes in 1..(1024L * 1024 * 4))
            assertEquals(151, Color.green(preview.bitmap.getPixel(500, 250)))
        }
    }

    @Test fun completeCompressedHighDimensionImageDecodesBySamplingNotAFullSizeRaster() {
        val bytes = compressedGrayPng(8000, 8000)
        assertTrue(bytes.size < 2 * 1024 * 1024, "real compressed body, 64 MP raster")
        preview(bytes).use { preview ->
            assertEquals(1000, preview.width, "sample size 8 chosen from validated dimensions")
            assertEquals(1000, preview.height)
            assertEquals(1000, preview.bitmap.width)
            assertTrue(preview.allocationBytes <= 1024L * 1024 * 4, "owned raster bounded by the preview target")
            assertEquals(151, Color.green(preview.bitmap.getPixel(500, 500)))
        }
        for ((width, height, depth) in listOf(Triple(16385, 1, 8), Triple(10001, 10000, 1)))
            rejects(compressedGrayPng(width, height, depth), ImageFailure.DIMENSIONS)
    }

    @Test fun dimensionAndPixelBombsAreTypedBeforeAnOversizedRasterCanBeAllocated() {
        preview().close() // The decoder must actually accept a real image first.
        for ((width, height) in listOf(16385 to 1, 1 to 16385, 10001 to 10000, Int.MAX_VALUE to Int.MAX_VALUE))
            rejects(pngHeader(width, height), ImageFailure.DIMENSIONS)
    }

    @Test fun partialImageMalformedBytesAndWrongMimeCannotYieldDrawableSuccess() {
        val png = encoded()
        preview(png).close()
        rejects(png.copyOf(png.size - 1), ImageFailure.INVALID_IMAGE)
        rejects(png.copyOf(png.size / 2), ImageFailure.INVALID_IMAGE)
        rejects("<html>not pixels</html>".toByteArray(), ImageFailure.INVALID_IMAGE)
        rejects(png, ImageFailure.INVALID_IMAGE, "image/jpeg")
    }

    @Test fun animationAndNonImagePayloadAreRejectedBeforeAStillPreview() {
        val png = encoded()
        preview(png).close()
        val animated = png.copyOfRange(0, 33) + chunk("acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array()) + png.copyOfRange(33, png.size)
        rejects(animated, ImageFailure.ANIMATED)
        rejects("<svg/>".toByteArray(), ImageFailure.UNSUPPORTED, "image/svg+xml")
    }

    @Test fun cancellationBeforeAndAfterDecodeCannotPublishOwnedPixels() {
        val bytes = encoded()
        preview(bytes).close()
        assertEquals(ImageFailure.CANCELLED, assertIs<ImageDecodeResult.Failure>(decode(bytes, active = { false })).reason)
        var admissions = 0
        assertEquals(ImageFailure.CANCELLED, assertIs<ImageDecodeResult.Failure>(decode(bytes, active = { ++admissions == 1 })).reason)
        assertTrue(admissions >= 2, "active state checked again after allocating/decoding")
    }
}
