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

    private fun avifFixture(name: String = "still-64x48.avif") =
        checkNotNull(javaClass.getResourceAsStream("/avif/$name")).use { it.readBytes() }
    private fun word(value: Int) = java.nio.ByteBuffer.allocate(4).putInt(value).array()
    private fun box(type: String, body: ByteArray, extended: Boolean = false): ByteArray =
        (if (extended) word(1) + type.toByteArray() + java.nio.ByteBuffer.allocate(8).putLong(body.size + 16L).array()
        else word(body.size + 8) + type.toByteArray()) + body
    private fun avif(properties: List<ByteArray>, associations: ByteArray? = byteArrayOf(1),
        extended: Boolean = false, major: String = "avif", primary: Int = 1): ByteArray {
        val ipma = associations?.let { box("ipma", word(0) + word(1) + byteArrayOf(0, 1, it.size.toByte()) + it) } ?: byteArrayOf()
        val meta = word(0) + box("pitm", word(0) + byteArrayOf((primary ushr 8).toByte(), primary.toByte())) +
            box("iprp", box("ipco", properties.fold(byteArrayOf()) { a, b -> a + b }) + ipma)
        return box("ftyp", major.toByteArray() + word(0) + "avifmif1".toByteArray(), extended) +
            box("meta", meta, extended) + box("mdat", byteArrayOf(1), extended)
    }
    private fun ispe(width: Int = 64, height: Int = 48) = box("ispe", word(0) + word(width) + word(height))

    @Test fun stillAvifBindsDimensionsMeasuredSizeAndEveryByteAcrossChunks() {
        val bytes = avifFixture()
        val expected = valid(bytes, "image/avif")
        assertEquals("AVIF", expected.format.name)
        assertEquals("avif", expected.format.extension)
        assertEquals(64, expected.width); assertEquals(48, expected.height)
        assertEquals(Size.Exact(bytes.size.toLong()), expected.size)
        assertEquals(bytes.size.toLong(), assertNotNull(expected.proof).measuredBytes)
        assertEquals(imageDigest(bytes), assertNotNull(expected.proof).completeBodySha256)
        for (chunk in listOf(1, 7)) {
            val stream = validator.newStream("image/avif")
            for (offset in bytes.indices step chunk) assertNull(stream.accept(bytes, offset, minOf(chunk, bytes.size - offset)))
            val image = assertIs<ImageValidationResult.Valid>(stream.finish()).image
            assertEquals(expected.format, image.format)
            assertEquals(expected.width, image.width); assertEquals(expected.height, image.height)
            assertEquals(expected.size, image.size)
            val proof = assertNotNull(image.proof)
            val expectedProof = assertNotNull(expected.proof)
            assertEquals(expectedProof.completeBodySha256, proof.completeBodySha256)
            assertEquals(expectedProof.measuredBytes, proof.measuredBytes)
        }
        assertEquals("AVIF", imageFormatFor("image/avif; q=1")?.name)
        reject(bytes, "image/png", ImageFailure.INVALID_IMAGE)
        assertEquals("GIF", valid(animatedGif(), "image/avif").format.name)
    }

    @Test fun avifSequencesWrongBytesTruncatedAndOversizedMetadataFail() {
        reject(avifFixture("anim-32x32.avif"), "image/avif", ImageFailure.ANIMATED)
        reject(raster(format = "jpeg"), "image/avif", ImageFailure.INVALID_IMAGE)
        val bytes = avifFixture()
        val meta = bytes.toString(Charsets.ISO_8859_1).indexOf("meta")
        assertTrue(meta > 4)
        reject(bytes.copyOf(meta + 12), "image/avif", ImageFailure.INVALID_IMAGE)
        val prefix = bytes.copyOf(meta + 4).also { word(65537).copyInto(it, meta - 4) }
        val stream = validator.newStream("image/avif")
        assertEquals(ImageFailure.BUDGET, stream.accept(prefix, 0, prefix.size))
        assertIs<ImageValidationResult.Failure>(stream.finish())
    }

    @Test fun avifPrimaryAssociationsResolveDimensionsAndRotationWithoutGuessing() {
        val properties = listOf(ispe(8, 8), ispe(), box("irot", byteArrayOf(1)))
        val image = valid(avif(properties, byteArrayOf(2, 3)), "image/avif")
        assertEquals(48, image.width); assertEquals(64, image.height)
        reject(avif(listOf(ispe()), primary = 2), "image/avif", ImageFailure.INVALID_IMAGE)
        assertEquals(64, valid(avif(properties, byteArrayOf(2)), "image/avif").width)
        reject(avif(properties, null), "image/avif", ImageFailure.INVALID_IMAGE)
        reject(avif(properties, byteArrayOf(1, 2)), "image/avif", ImageFailure.INVALID_IMAGE)
        reject(avif(properties, byteArrayOf(4)), "image/avif", ImageFailure.INVALID_IMAGE)
        assertEquals(64, valid(avif(listOf(ispe()), null), "image/avif").width)
        reject(avif(listOf(ispe(16385, 1))), "image/avif", ImageFailure.DIMENSIONS)
        reject(avif(listOf(ispe(0, 1))), "image/avif", ImageFailure.INVALID_IMAGE)
    }

    @Test fun avifWidePrimaryIdAndAssociationsChooseTheGridOutputProperty() {
        val primary = box("pitm", word(0x01000000) + word(70000))
        val grid = box("iinf", word(0x01000000) + word(1) +
            box("infe", word(0x03000000) + word(70000) + byteArrayOf(0, 0) + "grid".toByteArray() + byteArrayOf(0)))
        val properties = box("ipco", ispe(8, 8) + ispe() + box("irot", byteArrayOf(3)), extended = true)
        val associations = box("ipma", word(0x01000001) + word(1) + word(70000) +
            byteArrayOf(2, 0x80.toByte(), 2, 0x80.toByte(), 3))
        val bytes = box("ftyp", "avif".toByteArray() + word(0)) +
            box("meta", word(0) + primary + grid + box("iprp", properties + associations)) + box("mdat", byteArrayOf(1))
        val stream = validator.newStream("image/avif")
        for (index in bytes.indices) assertNull(stream.accept(bytes, index, 1))
        val image = assertIs<ImageValidationResult.Valid>(stream.finish()).image
        assertEquals(48, image.width); assertEquals(64, image.height)
    }

    @Test fun avifExtendedBoxesCompatibleBrandsAndFinalMdatAreBounded() {
        val bytes = avif(listOf(ispe()), extended = true, major = "mif1")
        assertEquals(64, valid(bytes, "image/avif").width)
        val ordinary = avif(listOf(ispe()))
        val mdat = ordinary.size - 9
        assertEquals(64, valid(ordinary.copyOf().also { word(0).copyInto(it, mdat) }, "image/avif").width)
        reject(word(0) + "ftypavif".toByteArray(), "image/avif", ImageFailure.INVALID_IMAGE)
        reject(box("ftyp", "mif1".toByteArray() + word(0)), "image/avif", ImageFailure.INVALID_IMAGE)
        reject(avif(listOf(ispe()), major = "avis"), "image/avif", ImageFailure.ANIMATED)
        reject(bytes.copyOf(bytes.size - 1), "image/avif", ImageFailure.INVALID_IMAGE)
        reject(ordinary + byteArrayOf(0), "image/avif", ImageFailure.INVALID_IMAGE)
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
            "png" to MediaImageFormat.PNG, "webp" to MediaImageFormat.WEBP, "avif" to MediaImageFormat.AVIF)) {
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
        for (mime in listOf("text/html", "image/svg+xml", "application/pdf", "application/xml", ""))
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
