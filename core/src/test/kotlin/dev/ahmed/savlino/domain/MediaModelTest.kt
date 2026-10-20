package dev.ahmed.savlino.domain

import kotlin.test.*

class MediaModelTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/source?token=source-secret")!!, 7)
    private val identity = MediaAdapterIdentity("adapter", 1, "runtime", 3)
    private val itemRef = MediaItemRef("adapter", "returned-item")
    private val row = Format("returned-video", "mp4", true, true)
    private fun ref(id: String = row.id) = MediaRenditionRef(itemRef, id)
    private fun av(format: Format = row) = MediaRendition.Audiovisual(ref(format.id), format)
    private fun item(renditions: List<MediaRendition> = listOf(av()),
        presets: List<PresetChoice> = emptyList(), rough: Map<String, Long> = emptyMap(),
        kind: MediaKind = MediaKind.VIDEO) = MediaItem(itemRef, 0, kind, MediaMetadata(), renditions, presets, rough)
    private fun inspection(items: List<MediaItem>) = MediaInspection(source, identity, items, MediaInspectionValidity.processLocal())

    @Test fun callerMutationCannotReplaceItemsRenditionsPresetsOrRoughSizes() {
        val rows = mutableListOf<MediaRendition>(av())
        val presets = mutableListOf(PresetChoice(Preset.BEST, row.id))
        val rough = mutableMapOf(row.id to 123L)
        val mediaItem = item(rows, presets, rough)
        val items = mutableListOf(mediaItem)
        val found = inspection(items)
        rows.clear(); presets.clear(); rough.clear(); items.clear()
        assertEquals(1, found.items.size)
        assertEquals(1, mediaItem.renditions.size)
        assertEquals(listOf(PresetChoice(Preset.BEST, "returned-video")), mediaItem.presets)
        assertEquals(mapOf("returned-video" to 123L), mediaItem.roughSizeBytes)
    }

    @Test fun exposedCollectionsCannotBeMutatedThroughCasts() {
        val mediaItem = item(presets = listOf(PresetChoice(Preset.BEST, row.id)), rough = mapOf(row.id to 123L))
        val found = inspection(listOf(mediaItem))
        assertFailsWith<UnsupportedOperationException> { (found.items as MutableList<MediaItem>).clear() }
        assertFailsWith<UnsupportedOperationException> { (mediaItem.renditions as MutableList<MediaRendition>).clear() }
        assertFailsWith<UnsupportedOperationException> { (mediaItem.presets as MutableList<PresetChoice>).clear() }
        assertFailsWith<UnsupportedOperationException> { (mediaItem.roughSizeBytes as MutableMap<String, Long>).clear() }
    }

    @Test fun roughSizesRemainPerRenditionDisplayOnlyAndFilterUnknownPositiveRows() {
        val exact = row.copy(id = "exact", size = Size.Exact(40))
        val estimate = row.copy(id = "estimated", size = Size.Estimated(50))
        val zero = row.copy(id = "zero")
        val negative = row.copy(id = "negative")
        val mediaItem = item(listOf(av(), av(exact), av(estimate), av(zero), av(negative)),
            rough = mapOf(row.id to 100L, "exact" to 1000L, "estimated" to 2000L,
                "zero" to 0L, "negative" to -3L, "absent" to 90L))
        assertEquals(mapOf("returned-video" to 100L), mediaItem.roughSizeBytes)
        assertSame(Size.Unknown, mediaItem.renditions.first().size)
        assertSame(row, (mediaItem.renditions.first() as MediaRendition.Audiovisual).format)
    }

    @Test fun selectionCarriesExactSourceAdapterValidityAndReturnedRefs() {
        val found = inspection(listOf(item()))
        val selection = assertNotNull(found.selection(ref()))
        assertSame(source, selection.source)
        assertSame(identity, selection.identity)
        assertSame(found.validity, selection.validity)
        assertEquals(ref(), selection.renditionRef)
        assertEquals(MediaKind.VIDEO, selection.outputKind)
        assertNull(found.selection(ref("absent")))
        assertNull(found.selection(MediaRenditionRef(MediaItemRef("adapter", "other-item"), row.id)))
    }

    @Test fun multipleActualItemsKeepTheirOwnRenditionsAndOrder() {
        val secondRef = MediaItemRef("adapter", "second-returned-item")
        val imageRef = MediaRenditionRef(secondRef, "returned-image")
        val image = MediaRendition.Image(imageRef, MediaImageMetadata(MediaImageFormat.PNG))
        val second = MediaItem(secondRef, 1, MediaKind.IMAGE, MediaMetadata(), listOf(image))
        val found = inspection(listOf(item(), second))
        assertEquals(listOf("returned-item", "second-returned-item"), found.items.map { it.ref.itemId })
        assertEquals(1, found.items[1].position)
        assertEquals(MediaKind.IMAGE, assertNotNull(found.selection(imageRef)).outputKind)
    }

    @Test fun duplicateItemAndRenditionIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { inspection(listOf(item(), item())) }
        assertFailsWith<IllegalArgumentException> { item(listOf(av(), av())) }
    }

    @Test fun blankIdsAndInvalidAdapterVersionsAreRejected() {
        for (blank in listOf("", " \t")) {
            assertFailsWith<IllegalArgumentException> { MediaItemRef("adapter", blank) }
            assertFailsWith<IllegalArgumentException> { MediaItemRef(blank, "item") }
            assertFailsWith<IllegalArgumentException> { MediaRenditionRef(itemRef, blank) }
            assertFailsWith<IllegalArgumentException> { identity.copy(adapterId = blank) }
            assertFailsWith<IllegalArgumentException> { identity.copy(runtimeVersion = blank) }
        }
        assertFailsWith<IllegalArgumentException> { identity.copy(recipeVersion = 0) }
        assertFailsWith<IllegalArgumentException> { identity.copy(generation = -1) }
    }

    @Test fun mismatchedAdapterItemOrFormatRefsCannotEnterAnInspection() {
        val other = MediaItemRef("adapter", "other")
        assertFailsWith<IllegalArgumentException> {
            item(listOf(MediaRendition.Audiovisual(MediaRenditionRef(other, row.id), row)))
        }
        assertFailsWith<IllegalArgumentException> { MediaRendition.Audiovisual(ref("invented"), row) }
        assertFailsWith<IllegalArgumentException> {
            MediaInspection(source, identity.copy(adapterId = "different"), listOf(item()), MediaInspectionValidity.processLocal())
        }
        assertFailsWith<IllegalArgumentException> { MediaItem(itemRef, -1, MediaKind.VIDEO, MediaMetadata(), listOf(av())) }
    }

    @Test fun itemKindsCannotMislabelVideoOrImageRenditions() {
        val image = MediaRendition.Image(ref("image"), MediaImageMetadata(MediaImageFormat.JPEG))
        assertFailsWith<IllegalArgumentException> { item(kind = MediaKind.AUDIO) }
        assertFailsWith<IllegalArgumentException> { item(kind = MediaKind.IMAGE) }
        assertFailsWith<IllegalArgumentException> { item(listOf(image)) }
        assertFailsWith<IllegalArgumentException> { item(listOf(av(), image)) }
    }

    @Test fun videoSourceRequiresReturnedVideoEvidenceInThisFoundation() {
        val audio = Format("returned-audio", "m4a", false, true)
        assertFailsWith<IllegalArgumentException> { item(listOf(av(audio))) }
        val videoItem = item(listOf(av(), av(audio)))
        assertEquals(MediaKind.VIDEO, videoItem.sourceKind)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.AUDIO), videoItem.renditions.map { it.outputKind })
        val found = inspection(listOf(videoItem))
        assertEquals(MediaKind.AUDIO, assertNotNull(found.selection(ref(audio.id))).outputKind)
    }

    @Test fun presetsCannotReferenceMissingOrIncompatibleRenditions() {
        assertFailsWith<IllegalArgumentException> { item(presets = listOf(PresetChoice(Preset.BEST, "missing"))) }
        assertFailsWith<IllegalArgumentException> { item(presets = listOf(PresetChoice(Preset.BEST, row.id), PresetChoice(Preset.BEST, row.id))) }
        assertFailsWith<IllegalArgumentException> { item(presets = listOf(PresetChoice(Preset.AUDIO_M4A, row.id))) }
        assertFailsWith<IllegalArgumentException> { item(presets = listOf(PresetChoice(Preset.AUDIO_MP3, row.id))) }
        val image = MediaRendition.Image(ref("image"), MediaImageMetadata(MediaImageFormat.JPEG))
        assertFailsWith<IllegalArgumentException> { item(listOf(image), listOf(PresetChoice(Preset.BEST, "image")), kind = MediaKind.IMAGE) }
    }

    @Test fun imageMetadataKeepsUnknownEstimatedAndExactSizesWithoutInventingProof() {
        for (size in listOf(Size.Unknown, Size.Estimated(42), Size.Exact(42))) {
            val metadata = MediaImageMetadata(MediaImageFormat.JPEG, size)
            val image = MediaRendition.Image(ref("image"), metadata)
            assertSame(size, image.size)
            assertNull(metadata.width); assertNull(metadata.height); assertNull(metadata.proof)
            assertEquals(MediaKind.IMAGE, image.outputKind)
            assertEquals("image/jpeg", metadata.format.mimeType)
        }
        assertEquals("image/png", MediaImageMetadata(MediaImageFormat.PNG).format.mimeType)
        assertEquals("image/webp", MediaImageMetadata(MediaImageFormat.WEBP).format.mimeType)
    }

    @Test fun imageDimensionsMustBePositiveWhenPresent() {
        assertFailsWith<IllegalArgumentException> { MediaImageMetadata(MediaImageFormat.PNG, width = 0) }
        assertFailsWith<IllegalArgumentException> { MediaImageMetadata(MediaImageFormat.PNG, height = -1) }
        val metadata = MediaImageMetadata(MediaImageFormat.WEBP, width = 800, height = 596)
        assertEquals(800, metadata.width); assertEquals(596, metadata.height)
    }

    @Test fun imageKnownSizesCannotClaimAZeroByteStill() {
        for (size in listOf(Size.Exact(0), Size.Estimated(0))) {
            assertFailsWith<IllegalArgumentException> { MediaImageMetadata(MediaImageFormat.JPEG, size) }
        }
        assertSame(Size.Unknown, MediaImageMetadata(MediaImageFormat.JPEG).size)
    }

    @Test fun completeBodyProofRejectsMalformedDigestLengthAndConflictingExactSize() {
        for (digest in listOf("", "a".repeat(63), "g".repeat(64), "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { MediaImageProof(digest, 42) }
        }
        for (bytes in listOf(0L, -1L)) assertFailsWith<IllegalArgumentException> { MediaImageProof("a".repeat(64), bytes) }
        val proof = MediaImageProof("a".repeat(64), 42)
        assertFailsWith<IllegalArgumentException> { MediaImageMetadata(MediaImageFormat.PNG, Size.Exact(43), proof = proof) }
        assertSame(proof, MediaImageMetadata(MediaImageFormat.PNG, Size.Exact(42), proof = proof).proof)
        assertSame(proof, MediaImageMetadata(MediaImageFormat.PNG, Size.Unknown, proof = proof).proof)
    }

    @Test fun metadataUsesExistingSanitizationAndDoesNotInterpretUrlsAsIds() {
        val metadata = MediaMetadata("  عنوان\u202E\n 👩‍💻  ", "قناة\u2066\u0000", -1, "http://example.org/no")
        assertEquals("عنوان 👩‍💻", metadata.title)
        assertEquals("قناة", metadata.uploader)
        assertNull(metadata.durationSeconds); assertNull(metadata.thumbnailUrl)
    }

    @Test fun diagnosticsNeverRenderAnyUntrustedPayloadOrValidityContents() {
        val secret = "https://secret.example.org/path?signature=private-sentinel"
        val privateIdentity = MediaAdapterIdentity(secret, 1, secret, 0)
        val privateItemRef = MediaItemRef(secret, secret)
        val privateRef = MediaRenditionRef(privateItemRef, secret)
        val format = Format(secret, "mp4", true, true, videoCodec = secret, sourceFormatId = secret)
        val rendition = MediaRendition.Audiovisual(privateRef, format)
        val metadata = MediaMetadata(secret, secret, 5, secret)
        val mediaItem = MediaItem(privateItemRef, 0, MediaKind.VIDEO, metadata, listOf(rendition),
            listOf(PresetChoice(Preset.BEST, secret)), mapOf(secret to 42L))
        val found = MediaInspection(source, privateIdentity, listOf(mediaItem), MediaInspectionValidity.processLocal())
        val proof = MediaImageProof("abcd".repeat(16), 42)
        val imageMetadata = MediaImageMetadata(MediaImageFormat.PNG, proof = proof)
        val objects = listOf(privateIdentity, privateItemRef, privateRef, rendition, metadata, mediaItem, found,
            assertNotNull(found.selection(privateRef)), found.validity, proof, imageMetadata,
            MediaRendition.Image(privateRef, imageMetadata))
        for (value in objects) {
            val diagnostic = value.toString()
            assertFalse(diagnostic.contains("private-sentinel"), value.javaClass.simpleName)
            assertFalse(diagnostic.contains("source-secret"), value.javaClass.simpleName)
            assertFalse(diagnostic.contains("abcd".repeat(16)), value.javaClass.simpleName)
        }
    }
}
