package dev.ahmed.savlino.domain

import kotlin.test.*

class ContractsTest {
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/video")), 4)
    private val video = Format("v720", "mp4", true, true, "h264", "aac", 1280, 720,
        bitrateKbps = 1500, size = Size.Exact(1200))
    private fun inspection(rows: List<Format> = listOf(video), presets: List<PresetChoice> = emptyList()) =
        Inspection(source, "fixture-1", rows, presets)
    private fun command(id: String = "job-1", format: Format = video) =
        assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(id, inspection(listOf(format)), source, format.id, "عنوان")).command

    @Test fun sourceContainerIsValidatedAndAllowedOnlyForConversions() {
        val conversion = Format("conversion", "mp3", false, true, conversion = AudioConversion.MP3_128)
        assertNull(conversion.sourceContainer)
        for (container in listOf("mp3", "m4a", "mp4", "webm")) {
            assertEquals(container, command(format = conversion.copy(sourceContainer = container)).format.sourceContainer)
        }
        for (invalid in listOf("", "../mp3", "mp 3", "x".repeat(11))) {
            assertFailsWith<IllegalArgumentException>(invalid) { conversion.copy(sourceContainer = invalid) }
        }
    }
    @Test fun nativeOriginalCannotClaimConversionSourceContainer() {
        assertFailsWith<IllegalArgumentException> { video.copy(sourceContainer = "mp4") }
        val native = Format("native", "mp3", false, true)
        assertFailsWith<IllegalArgumentException> { native.copy(sourceContainer = "mp3") }
    }

    @Test fun exactEstimatedAndUnknownSizesRemainDistinct() {
        assertNotEquals<Size>(Size.Exact(42), Size.Estimated(42))
        assertEquals(Size.Unknown, video.copy(size = Size.Unknown).size)
        assertFailsWith<IllegalArgumentException> { Size.Exact(-1) }
        assertFailsWith<IllegalArgumentException> { Size.Estimated(-1) }
    }

    @Test fun roughDisplayMetadataIsCopiedFilteredAndNeverChangesConfirmedSize() {
        val unknown = video.copy(size = Size.Unknown)
        val input = mutableMapOf(unknown.id to 1000L, "missing" to 123L, "negative" to -1L)
        val found = Inspection(source, "fixture-1", listOf(unknown), roughSizeBytes = input)
        input[unknown.id] = 9000
        assertEquals(mapOf(unknown.id to 1000L), found.roughSizeBytes)
        val cmd = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1", found, source, unknown.id, "title")).command
        assertEquals(Size.Unknown, cmd.format.size)
        assertSame(unknown, cmd.format)
        assertTrue(Inspection(source, "fixture-1", listOf(video), roughSizeBytes = mapOf(video.id to 1L)).roughSizeBytes.isEmpty())
        assertTrue(Inspection(source, "fixture-1", listOf(unknown), roughSizeBytes = mapOf(unknown.id to 0L)).roughSizeBytes.isEmpty())
    }

    @Test fun fullRowsRetainExactVideoAndAudioSelection() {
        val row = video.copy(hasAudio = false, audioCodec = null, audioFormatId = "a140", requiresMerge = true)
        val cmd = command(format = row)
        assertEquals("v720", cmd.format.id)
        assertEquals("a140", cmd.format.audioFormatId)
        assertTrue(cmd.format.requiresMerge)
        assertEquals(1280, cmd.format.width)
        assertEquals(Size.Exact(1200), cmd.format.size)
    }

    @Test fun presetsOnlyReferenceReturnedRowsAndMp3ConversionIsExplicit() {
        val audio = Format("a140", "m4a", false, true, audioCodec = "aac", bitrateKbps = 128)
        val mp3 = audio.copy(id = "mp3-128", container = "mp3", conversion = AudioConversion.MP3_128,
            sourceFormatId = "a140", size = Size.Estimated(1000))
        val found = inspection(listOf(video, audio, mp3), listOf(PresetChoice(Preset.BEST, "v720"),
            PresetChoice(Preset.BALANCED, "v720"), PresetChoice(Preset.SMALL, "v720"),
            PresetChoice(Preset.AUDIO_M4A, "a140"), PresetChoice(Preset.AUDIO_MP3, "mp3-128")))
        assertEquals(5, found.presets.size)
        assertEquals(128, command(format = mp3).format.conversion?.bitrateKbps)
        assertEquals(320, command(format = mp3.copy(conversion = AudioConversion.MP3_320)).format.conversion?.bitrateKbps)
        assertFailsWith<IllegalArgumentException> { inspection(presets = listOf(PresetChoice(Preset.BEST, "invented"))) }
        assertFailsWith<IllegalArgumentException> { inspection(listOf(video, video)) }
        assertFailsWith<IllegalArgumentException> { inspection(presets = listOf(PresetChoice(Preset.AUDIO_M4A, "v720"))) }
    }

    @Test fun malformedRowsCannotBecomeCommands() {
        assertFailsWith<IllegalArgumentException> { video.copy(id = "") }
        assertFailsWith<IllegalArgumentException> { video.copy(width = -1) }
        assertFailsWith<IllegalArgumentException> { video.copy(hasVideo = false, hasAudio = false) }
        assertFailsWith<IllegalArgumentException> { video.copy(requiresMerge = true) }
        assertFailsWith<IllegalArgumentException> { video.copy(conversion = AudioConversion.MP3_128) }
    }

    @Test fun commandRejectsStaleRevisionDifferentUrlAndAbsentFormat() {
        assertEquals(ConfirmResult.Stale, DownloadCommand.confirm("job", inspection(), source.copy(revision = 5), video.id, "x"))
        val other = source.copy(link = assertNotNull(LinkInput.parse("https://example.org/other")))
        assertEquals(ConfirmResult.Stale, DownloadCommand.confirm("job", inspection(), other, video.id, "x"))
        assertEquals(ConfirmResult.Invalid(DownloadError.FormatExpired), DownloadCommand.confirm("job", inspection(), source, "invented", "x"))
        for (id in listOf("", "../escape", "a".repeat(129))) {
            assertEquals(ConfirmResult.Invalid(DownloadError.InvalidInput), DownloadCommand.confirm(id, inspection(), source, video.id, "x"))
        }
        val confirmed = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1", inspection(), source, video.id, "عنوان")).command
        assertEquals("job-1", confirmed.jobId)
        assertEquals(source, confirmed.source)
        assertEquals("fixture-1", confirmed.engineVersion)
        assertEquals(video, confirmed.format)
        assertEquals("عنوان.mp4", confirmed.filename)
    }

    @Test fun videoOnlyRowsRequireAnExplicitAudioMerge() {
        val row = video.copy(hasAudio = false, audioCodec = null)
        assertEquals(ConfirmResult.Invalid(DownloadError.Unsupported),
            DownloadCommand.confirm("job-1", inspection(listOf(row)), source, row.id, "عنوان"))
    }

    @Test fun filenameUnicodeSeparatorsCollapseToPlainSpaces() {
        assertEquals("first second third fourth.mp4",
            FilenameSanitizer.sanitize("first\u00A0second\u2028third\u2029\u3000fourth", "mp4"))
        assertEquals("video.mp4", FilenameSanitizer.sanitize("\u00A0\u2028\u2029\u3000", "mp4"))
    }

    @Test fun filenamesKeepPersianJoinersAndEmojiSequences() {
        assertEquals("می\u200Cروم.mp4", FilenameSanitizer.sanitize("می\u200Cروم", "mp4"))
        assertEquals("👩\u200D💻.mp4", FilenameSanitizer.sanitize("👩\u200D💻", "mp4"))
        val controls = "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u061C\u0000\u200B"
        assertEquals("ab.mp4", FilenameSanitizer.sanitize("a" + controls + "b", "mp4"))
    }

    @Test fun filenamesNormalizeNfcBeforeApplyingTheStemCap() {
        assertEquals("café.mp4", FilenameSanitizer.sanitize("cafe\u0301", "mp4"))
        assertEquals("é".repeat(80) + ".mp4", FilenameSanitizer.sanitize("e\u0301".repeat(200), "mp4"))
    }

    @Test fun filenameStemCapKeepsHarakatAndCombiningMarksWithTheirBase() {
        assertEquals("a".repeat(79) + ".mp4", FilenameSanitizer.sanitize("a".repeat(79) + "بُ", "mp4"))
        assertEquals("a".repeat(79) + ".mp4", FilenameSanitizer.sanitize("a".repeat(79) + "x\u0301", "mp4"))
        assertEquals("a".repeat(78) + "بُ.mp4", FilenameSanitizer.sanitize("a".repeat(78) + "بُz", "mp4"))
    }

    @Test fun filenamesKeepArabicButRemoveTraversalControlsAndBidi() {
        assertEquals("عنوان جميل.mp4", FilenameSanitizer.sanitize("عنوان جميل", "mp4"))
        val safe = FilenameSanitizer.sanitize("../../عنوان\u202E/evil\\\u0000\n", "mp4")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertFalse(safe.contains(".."))
        assertFalse(safe.contains('\u202E'))
        assertFalse(safe.any { Character.isISOControl(it) })
        assertEquals("video.mp4", FilenameSanitizer.sanitize(" .\u200F. ", "mp4"))
        assertFailsWith<IllegalArgumentException> { FilenameSanitizer.sanitize("x", "../mp4") }
    }

    @Test fun filenameByteCapPreservesExtensionAndWholeUnicodeCodePoints() {
        for (title in listOf("م".repeat(300), "😀".repeat(100), "a".repeat(500))) {
            val name = FilenameSanitizer.sanitize(title, "mp4")
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
            assertTrue(name.endsWith(".mp4"))
            assertFalse(name.dropLast(4).last().isHighSurrogate())
        }
    }
}
