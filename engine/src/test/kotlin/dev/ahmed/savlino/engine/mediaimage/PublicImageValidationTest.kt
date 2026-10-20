package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import org.junit.Test
import kotlin.test.*

class PublicImageValidationTest {
    private val validator = ImageRepresentationValidator()
    private fun valid(bytes: ByteArray, mime: String = "image/png") =
        assertIs<ImageValidationResult.Valid>(validator.validate(bytes, mime)).image
    private fun reject(bytes: ByteArray, mime: String, reason: ImageFailure) {
        assertEquals(reason, assertIs<ImageValidationResult.Failure>(validator.validate(bytes, mime)).reason)
    }

    @Test fun gifSignaturesOverrideDeclaredMimeAndBindEveryAnimationByte() {
        for (version in listOf("87a", "89a")) for (mime in listOf("image/gif", "image/jpeg")) {
            val bytes = animatedGif(version)
            val image = valid(bytes, mime)
            assertEquals("GIF", image.format.name)
            assertEquals("gif", image.format.extension)
            assertEquals("image/gif", image.format.mimeType)
            assertEquals(1, image.width); assertEquals(1, image.height)
            assertEquals(imageDigest(bytes), assertNotNull(image.proof).completeBodySha256)
            val stream = validator.newStream(mime)
            for (index in bytes.indices) assertNull(stream.accept(bytes, index, 1))
            assertEquals("GIF", assertIs<ImageValidationResult.Valid>(stream.finish()).image.format.name)
        }
    }

    @Test fun gifRequiresCompleteBlocksTrailerAndBoundedDimensions() {
        val bytes = animatedGif()
        valid(bytes, "image/gif")
        for (length in bytes.indices) reject(bytes.copyOf(length), "image/gif", ImageFailure.INVALID_IMAGE)
        reject(bytes + byteArrayOf(0), "image/gif", ImageFailure.INVALID_IMAGE)
        reject(bytes.copyOf().also { it[4] = '8'.code.toByte() }, "image/gif", ImageFailure.INVALID_IMAGE)
        reject(raster(), "image/gif", ImageFailure.INVALID_IMAGE)
        reject(bytes.copyOf().also { it[6] = 1; it[7] = 64 }, "image/gif", ImageFailure.DIMENSIONS)
    }

    @Test fun webpPreviewReferenceUsesJpegOriginalFormatAndSaveMetadata() {
        val preview = MediaImageMetadata(MediaImageFormat.WEBP, Size.Exact(123), 200, 150)
        val original = originalImageMetadata("https://instagram.fxyz1-1.fna.fbcdn.net/photo.jpg?stp=original", preview)
        assertEquals(MediaImageFormat.JPEG, original.format)
        assertNull(original.width)
        assertNull(original.height)
        assertNull(original.proof)
        assertEquals(Size.Unknown, original.size)
        val saved = valid(raster(800, 600, "jpg"), original.format.mimeType)
        assertEquals("jpg", saved.format.extension)
        assertEquals("image/jpeg", saved.format.mimeType)
        assertEquals(800, saved.width)
    }

    @Test fun originalCandidateFormatSupportsEveryAllowedInstagramStillExtension() {
        for ((extension, format) in listOf("jpg" to MediaImageFormat.JPEG, "jpeg" to MediaImageFormat.JPEG,
            "png" to MediaImageFormat.PNG, "webp" to MediaImageFormat.WEBP)) {
            val original = originalImageMetadata("https://instagram.fxyz1-1.fna.fbcdn.net/photo.$extension?stp=original",
                MediaImageMetadata(MediaImageFormat.PNG))
            assertEquals(format, original.format)
        }
        assertEquals(MediaImageFormat.JPEG, originalImageMetadata("https://pbs.twimg.com/media/photo?format=jpg&name=orig",
            MediaImageMetadata(MediaImageFormat.JPEG)).format)
    }

    @Test fun spareCapacityIsExcludedFromValidationAndWholeBodyProof() {
        val body = raster()
        val allocation = body.copyOf(2 * 1024 * 1024)
        val image = assertIs<ImageValidationResult.Valid>(validator.validate(allocation, "image/png", body.size)).image
        assertEquals(body.size.toLong(), image.proof!!.measuredBytes)
        assertEquals(imageDigest(body), image.proof!!.completeBodySha256)
    }

    @Test fun fullPngAndJpegProduceOriginalDimensionsMeasuredSizeAndWholeBodyProof() {
        for ((format, mime, encoding) in listOf(Triple("png", "image/png", MediaImageFormat.PNG),
            Triple("jpeg", "image/jpeg", MediaImageFormat.JPEG))) {
            val bytes = raster(80, 59, format)
            val image = valid(bytes, mime)
            assertEquals(encoding, image.format)
            assertEquals(80, image.width); assertEquals(59, image.height)
            assertEquals(Size.Exact(bytes.size.toLong()), image.size)
            val proof = assertNotNull(image.proof)
            assertEquals(bytes.size.toLong(), proof.measuredBytes)
            assertEquals(imageDigest(bytes), proof.completeBodySha256)
        }
    }

    @Test fun mimeAndSignatureMustAgreeWithoutFilenameOrContentLengthEvidence() {
        valid(raster())
        reject(raster(), "image/jpeg", ImageFailure.INVALID_IMAGE)
        reject(raster(format = "jpeg"), "image/png", ImageFailure.INVALID_IMAGE)
        for (mime in listOf("text/html", "image/svg+xml", "application/pdf", "application/xml", "image/avif", ""))
            reject(raster(), mime, ImageFailure.UNSUPPORTED)
    }

    @Test fun emptyMalformedTruncatedAndCorruptBodiesNeverProduceExactProof() {
        val png = raster()
        val jpeg = raster(format = "jpeg")
        valid(png)
        for ((bytes, mime) in listOf(byteArrayOf() to "image/png", "<html>image</html>".toByteArray() to "image/png",
            png.copyOf(32) to "image/png", png.copyOf(png.size - 1) to "image/png",
            jpeg.copyOf(jpeg.size - 2) to "image/jpeg",
            png.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to "image/png"))
            reject(bytes, mime, ImageFailure.INVALID_IMAGE)
    }

    @Test fun apngAndAnimatedWebpAreRejectedBeforeStillPreview() {
        valid(raster())
        reject(animatedPng(), "image/png", ImageFailure.ANIMATED)
        reject(webpHeader(width = 1, animated = true), "image/webp", ImageFailure.ANIMATED)
    }

    @Test fun dimensionsAndPixelMultiplicationAreBoundedWithoutAllocatingRaster() {
        valid(raster())
        for ((width, height) in listOf(16385 to 1, 1 to 16385, 10001 to 10000, Int.MAX_VALUE to Int.MAX_VALUE))
            reject(bombPng(width, height), "image/png", ImageFailure.DIMENSIONS)
        reject(webpHeader(), "image/webp", ImageFailure.DIMENSIONS)
        // Complete, valid, tiny compressed bodies still cannot carry an over-budget raster.
        val allowed = compressedGrayPng(8000, 8000)
        assertEquals(8000, valid(allowed).width)
        assertTrue(allowed.size < 2 * 1024 * 1024, "real compressed high-dimension control fits the preview body cap")
        for ((width, height, depth) in listOf(Triple(16385, 1, 8), Triple(1, 16385, 8), Triple(10001, 10000, 1)))
            reject(compressedGrayPng(width, height, depth), "image/png", ImageFailure.DIMENSIONS)
    }

    @Test fun streamedValidationBindsCompleteBytesEvenWhenEveryChunkSplitsHeaderFields() {
        val bytes = raster(61, 47)
        val stream = validator.newStream("image/png")
        for (index in bytes.indices) assertNull(stream.accept(bytes, index, 1))
        val image = assertIs<ImageValidationResult.Valid>(stream.finish()).image
        assertEquals(61, image.width); assertEquals(47, image.height)
        assertEquals(Size.Exact(bytes.size.toLong()), image.size)
        assertEquals(imageDigest(bytes), assertNotNull(image.proof).completeBodySha256)
    }

    @Test fun streamedPrefixCannotBecomeCompleteProofAndAppendedJunkCannotBeIgnored() {
        val png = raster()
        valid(png)
        val prefix = validator.newStream("image/png")
        assertNull(prefix.accept(png, 0, png.size - 1))
        assertIs<ImageValidationResult.Failure>(prefix.finish())
        reject(png + byteArrayOf(1, 2, 3), "image/png", ImageFailure.INVALID_IMAGE)
        reject(webpHeader(width = 1).also { it[4] = 0 }, "image/webp", ImageFailure.INVALID_IMAGE)
    }

    @Test fun proofOfDifferentEncodedRepresentationCannotBeSubstitutedByMatchingDimensions() {
        val a = valid(raster(color = 0x123456))
        val b = valid(raster(color = 0x654321))
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertNotEquals(assertNotNull(a.proof).completeBodySha256, assertNotNull(b.proof).completeBodySha256)
    }
}
