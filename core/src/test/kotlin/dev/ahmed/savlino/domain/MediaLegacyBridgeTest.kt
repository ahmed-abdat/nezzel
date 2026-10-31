package dev.ahmed.savlino.domain

import kotlin.test.*

class MediaLegacyBridgeTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/video?sig=source-secret")!!, 4)
    private val identity = MediaAdapterIdentity("yt-dlp", 1, "fixture-runtime", 2)
    private val video = Format("v720", "mp4", true, false, videoCodec = "h264", width = 1280, height = 720,
        audioFormatId = "a140", requiresMerge = true, sourceFormatId = "v720")
    private val audio = Format("a140", "m4a", false, true, audioCodec = "aac", size = Size.Exact(321))
    private val mp3 = Format("nezzel:mp3:128:a140", "mp3", false, true, audioCodec = "mp3",
        sourceFormatId = "a140", conversion = AudioConversion.MP3_128)
    private val itemRef = MediaItemRef("yt-dlp", "returned-item")
    private val legacy = Inspection(source, "fixture-runtime", listOf(video, audio, mp3),
        listOf(PresetChoice(Preset.BEST, "v720"), PresetChoice(Preset.AUDIO_M4A, "a140"),
            PresetChoice(Preset.AUDIO_MP3, "nezzel:mp3:128:a140")),
        title = "رحلة 🌊", uploader = "قناة", durationSeconds = 120,
        thumbnailUrl = "https://example.org/thumbnail?sig=preview-secret",
        roughSizeBytes = mapOf("v720" to 999L, "a140" to 888L, "nezzel:mp3:128:a140" to 777L))
    private fun wrap(inspection: Inspection = legacy) = MediaLegacyBridge.wrap(inspection, "returned-item", identity)
    private fun selection(bridge: MediaLegacyBridge, format: Format = video) = MediaSelection(source, identity,
        MediaRenditionRef(itemRef, format.id), if (format.hasVideo) MediaKind.VIDEO else MediaKind.AUDIO, bridge.inspection.validity)
    private fun confirm(bridge: MediaLegacyBridge, choice: MediaSelection = selection(bridge), job: String = "job-1",
        current: LinkRevision = source, adapter: MediaAdapterIdentity = identity) = bridge.confirm(job, choice, current, adapter, "fallback")

    @Test fun wrapRetainsOneActualItemAndAllLegacyMetadataPresetsAndFormats() {
        val bridge = wrap()
        assertEquals(1, bridge.inspection.items.size)
        val item = bridge.inspection.items.single()
        assertEquals(itemRef, item.ref)
        assertEquals(0, item.position)
        assertEquals(MediaKind.VIDEO, item.sourceKind)
        assertEquals(listOf("v720", "a140", "nezzel:mp3:128:a140"), item.renditions.map { it.ref.renditionId })
        for ((index, format) in legacy.formats.withIndex()) {
            assertSame(format, (item.renditions[index] as MediaRendition.Audiovisual).format)
        }
        assertEquals(legacy.presets, item.presets)
        assertEquals(mapOf("v720" to 999L, "nezzel:mp3:128:a140" to 777L), item.roughSizeBytes)
        assertEquals("رحلة 🌊", item.metadata.title); assertEquals("قناة", item.metadata.uploader)
        assertEquals(120L, item.metadata.durationSeconds)
        assertEquals("https://example.org/thumbnail?sig=preview-secret", item.metadata.thumbnailUrl)
    }

    @Test fun confirmationDelegatesOriginalFormatSourceEngineAndFilenameEffects() {
        val bridge = wrap()
        for ((format, filename) in listOf(video to "رحلة 🌊.mp4", audio to "رحلة 🌊.m4a", mp3 to "رحلة 🌊.mp3")) {
            val command = assertIs<MediaConfirmResult.Confirmed>(confirm(bridge, selection(bridge, format))).command
            assertSame(format, command.format)
            assertSame(source, command.source)
            assertEquals("fixture-runtime", command.engineVersion)
            assertEquals("job-1", command.jobId)
            assertEquals(filename, command.filename)
            assertEquals(format.sourceFormatId, command.format.sourceFormatId)
            assertEquals(format.audioFormatId, command.format.audioFormatId)
            assertEquals(format.conversion, command.format.conversion)
        }
    }

    @Test fun missingTitleUsesLegacyFallbackSanitization() {
        val bridge = wrap(Inspection(source, identity.runtimeVersion, listOf(audio)))
        val command = assertIs<MediaConfirmResult.Confirmed>(bridge.confirm("job", selection(bridge, audio), source, identity, "../name")).command
        assertEquals("name.m4a", command.filename)
    }

    @Test fun sourceKindIsDerivedFromActualRowsIncludingAudioExtractionAndEmptyInspection() {
        for ((rows, kind) in listOf(listOf(video, audio) to MediaKind.VIDEO, listOf(audio, mp3) to MediaKind.AUDIO, emptyList<Format>() to MediaKind.AUDIO)) {
            val bridge = wrap(Inspection(source, identity.runtimeVersion, rows))
            assertEquals(1, bridge.inspection.items.size)
            assertEquals(kind, bridge.inspection.items.single().sourceKind)
            assertEquals(rows.size, bridge.inspection.items.single().renditions.size)
        }
    }

    @Test fun invalidJobIdWinsOverStaleSourceWrongRefsKindIdentityAndValidity() {
        val bridge = wrap()
        val invalid = selection(bridge).copy(source = source.copy(revision = 9), identity = identity.copy(generation = 99),
            renditionRef = MediaRenditionRef(MediaItemRef("other", "absent"), "absent"), outputKind = MediaKind.IMAGE,
            validity = MediaInspectionValidity.processLocal())
        for (job in listOf("", "../escape", "-job", "a".repeat(81), "a".repeat(129))) {
            assertEquals(MediaConfirmResult.Invalid(DownloadError.InvalidInput), confirm(bridge, invalid, job, source.copy(revision = 10)))
        }
    }

    @Test fun staleSourceWinsOverKindWrongRefsAndChangedAdapter() {
        val bridge = wrap()
        val choice = selection(bridge).copy(outputKind = MediaKind.IMAGE,
            renditionRef = MediaRenditionRef(itemRef, "missing"))
        val otherUrl = source.copy(link = LinkInput.parse("https://example.org/video?sig=different")!!)
        for (current in listOf(source.copy(revision = 5), otherUrl)) {
            assertSame(MediaConfirmResult.Stale, confirm(bridge, choice, current = current, adapter = identity.copy(generation = 5)))
        }
        assertSame(MediaConfirmResult.Stale, confirm(bridge, choice.copy(source = source.copy(revision = 5))))
    }

    @Test fun imageSelectionReturnsUnsupportedEvenWhenItsRefIsNotAnAvFormat() {
        val bridge = wrap()
        val imageSelection = selection(bridge).copy(outputKind = MediaKind.IMAGE,
            renditionRef = MediaRenditionRef(MediaItemRef("image-adapter", "image-item"), "original"))
        assertEquals(MediaConfirmResult.Invalid(DownloadError.Unsupported), confirm(bridge, imageSelection))
    }

    @Test fun missingItemAndRenditionWinOverChangedIdentity() {
        val bridge = wrap()
        val choices = listOf(selection(bridge).copy(renditionRef = MediaRenditionRef(itemRef, "invented")),
            selection(bridge).copy(renditionRef = MediaRenditionRef(MediaItemRef("yt-dlp", "other"), video.id)),
            selection(bridge).copy(renditionRef = MediaRenditionRef(MediaItemRef("other-adapter", "returned-item"), video.id)))
        for (choice in choices) assertEquals(MediaConfirmResult.Invalid(DownloadError.FormatExpired),
            confirm(bridge, choice, adapter = identity.copy(generation = 5)))
    }

    @Test fun selectingWrongOutputKindForAnActualAvRowIsUnsupported() {
        val bridge = wrap()
        assertEquals(MediaConfirmResult.Invalid(DownloadError.Unsupported), confirm(bridge, selection(bridge).copy(outputKind = MediaKind.AUDIO)))
    }

    @Test fun everyCurrentOrSelectedAdapterIdentityFieldIsChecked() {
        val bridge = wrap()
        val changed = listOf(identity.copy(adapterId = "other"), identity.copy(recipeVersion = 2),
            identity.copy(runtimeVersion = "updated"), identity.copy(generation = 3))
        for (adapter in changed) {
            assertSame(MediaConfirmResult.Stale, confirm(bridge, adapter = adapter))
            assertSame(MediaConfirmResult.Stale, confirm(bridge, selection(bridge).copy(identity = adapter)))
        }
        val equalCopy = identity.copy()
        assertIs<MediaConfirmResult.Confirmed>(confirm(bridge, selection(bridge).copy(identity = equalCopy), adapter = equalCopy))
    }

    @Test fun validityFromAnotherWrapCannotConfirmButSameProcessLocalTokenRemainsUsable() {
        val first = wrap(); val second = wrap()
        assertNotSame(first.inspection.validity, second.inspection.validity)
        assertSame(MediaConfirmResult.Stale, confirm(first, selection(second)))
        assertIs<MediaConfirmResult.Confirmed>(confirm(first))
        assertIs<MediaConfirmResult.Confirmed>(confirm(first, job = "another-job"))
    }

    @Test fun unsupportedLegacyVideoRowStillReturnsUnsupported() {
        val unsupported = Format("silent", "mp4", true, false)
        val bridge = wrap(Inspection(source, identity.runtimeVersion, listOf(unsupported)))
        assertEquals(MediaConfirmResult.Invalid(DownloadError.Unsupported), confirm(bridge, selection(bridge, unsupported)))
    }

    @Test fun changedOriginalInspectionCannotRetargetTheConfirmedFormat() {
        val original = Inspection(source, identity.runtimeVersion, listOf(video, audio))
        val bridge = wrap(original)
        (original.formats as MutableList<Format>)[0] = video.copy(sourceFormatId = "different-returned-source")
        assertEquals(MediaConfirmResult.Invalid(DownloadError.FormatExpired), confirm(bridge))
    }

    @Test fun duplicateLiveFormatIdsReturnFormatExpiredWithoutThrowing() {
        for (duplicate in listOf(video, video.copy(sourceFormatId = "other-source"))) {
            val original = Inspection(source, identity.runtimeVersion, listOf(video, audio))
            val bridge = wrap(original)
            (original.formats as MutableList<Format>).add(duplicate)
            assertEquals(MediaConfirmResult.Invalid(DownloadError.FormatExpired), confirm(bridge))
        }
    }

    @Test fun emptyLegacyInspectionPreservesLegacyNoFormatConfirmationResult() {
        val original = Inspection(source, identity.runtimeVersion, emptyList())
        val bridge = wrap(original)
        assertEquals(ConfirmResult.Invalid(DownloadError.FormatExpired),
            DownloadCommand.confirm("job-1", original, source, video.id, "fallback"))
        assertEquals(MediaConfirmResult.Invalid(DownloadError.FormatExpired), confirm(bridge))
        assertNull(bridge.inspection.selection(MediaRenditionRef(itemRef, video.id)))
        val empty = MediaInspection(source, identity, emptyList(), MediaInspectionValidity.processLocal())
        assertTrue(empty.items.isEmpty())
        assertNull(empty.selection(MediaRenditionRef(itemRef, video.id)))
    }

    @Test fun bridgeRejectsMismatchedRuntimeAndBlankRealItemId() {
        assertFailsWith<IllegalArgumentException> { MediaLegacyBridge.wrap(legacy, "returned-item", identity.copy(runtimeVersion = "other")) }
        assertFailsWith<IllegalArgumentException> { MediaLegacyBridge.wrap(legacy, " ", identity) }
    }

    @Test fun bridgeAndConfirmationDiagnosticsDoNotRenderSourcePreviewOrFormatPayload() {
        val bridge = wrap()
        val result = assertIs<MediaConfirmResult.Confirmed>(confirm(bridge))
        for (value in listOf(bridge, bridge.inspection, selection(bridge), result)) {
            assertFalse(value.toString().contains("source-secret"))
            assertFalse(value.toString().contains("preview-secret"))
            assertFalse(value.toString().contains("v720"))
        }
    }
}
