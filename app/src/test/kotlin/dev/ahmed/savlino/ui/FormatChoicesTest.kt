package dev.ahmed.savlino.ui

import dev.ahmed.savlino.domain.*
import org.junit.Assert.*
import org.junit.Test

class FormatChoicesTest {
    private fun video(id: String, width: Int?, height: Int, codec: String = "avc1", size: Size = Size.Unknown) =
        Format(id, "mp4", true, true, videoCodec = codec, width = width, height = height, size = size)

    /** Item 8: a stale sheet downloads without asking again only when the fresh row is the same choice. */
    @Test fun sameChoiceNeedsTheSameIdsTypeHeightCodecsAndNoMuchBiggerFile() {
        val picked = Format("137", "mp4", true, true, videoCodec = "avc1.640028", audioCodec = "mp4a.40.2", height = 1080,
            audioFormatId = "140-1-drc", requiresMerge = true)
        assertTrue(FormatChoices.sameChoice(picked, picked.copy(), 1_000, 1_250))
        assertTrue(FormatChoices.sameChoice(picked, picked.copy(width = 1920, bitrateKbps = 4000), null, 9_999))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(), 1_000, 1_251)) // > 25% bigger than shown
        assertTrue(FormatChoices.sameChoice(picked, picked.copy(), 1_000, 400)) // smaller is fine
        val huge = Long.MAX_VALUE / 2 // ×4 / ×5 would overflow here
        assertTrue(FormatChoices.sameChoice(picked, picked.copy(), huge, huge))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(), huge, Long.MAX_VALUE))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(height = 720), null, null))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(videoCodec = "vp09.02.51.10"), null, null)) // HDR
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(audioCodec = "opus"), null, null))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(audioFormatId = "140-0"), null, null)) // other dub
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(container = "mkv"), null, null))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(id = "137b"), null, null))
        assertFalse(FormatChoices.sameChoice(picked, picked.copy(sourceFormatId = "399"), null, null))
        val mp3 = Format("a-mp3", "mp3", false, true, conversion = AudioConversion.MP3_128, sourceFormatId = "140", sourceContainer = "m4a")
        assertFalse(FormatChoices.sameChoice(mp3, mp3.copy(conversion = AudioConversion.MP3_320), null, null))
        val silent = Format("v", "mp4", true, false, sourceHasNoSound = true)
        assertFalse(FormatChoices.sameChoice(silent, silent.copy(sourceHasNoSound = false), null, null))
    }

    @Test fun tiersFollowTheQuality() {
        assertEquals(FormatChoices.Tier.UHD, FormatChoices.tier(2160))
        assertEquals(FormatChoices.Tier.FULL_HD, FormatChoices.tier(1080))
        assertEquals(FormatChoices.Tier.HD, FormatChoices.tier(720))
        assertEquals(FormatChoices.Tier.SD, FormatChoices.tier(540))
        assertEquals(FormatChoices.Tier.LOW, FormatChoices.tier(360))
        assertEquals(FormatChoices.Tier.VERY_LOW, FormatChoices.tier(240))
        assertEquals(FormatChoices.Tier.LOWEST, FormatChoices.tier(144))
    }

    /** UX critique U3: 240p and 144p both read "Very low"; no two rows in one list may share a tier name. */
    @Test fun tierNamesNeverRepeatInOneList() {
        val youtube = listOf(1080, 720, 480, 360, 240, 144).map { video("v$it", it * 16 / 9, it) }
        val names = FormatChoices.tierNames(FormatChoices.simple(youtube))
        assertEquals(6, names.values.filterNotNull().size)
        assertEquals(names.values.filterNotNull().size, names.values.filterNotNull().map { it.title }.toSet().size)
        assertEquals(FormatChoices.Tier.VERY_LOW, names["v240"]); assertEquals(FormatChoices.Tier.LOWEST, names["v144"])
        val portrait = listOf(video("p540", 540, 960), video("p480", 480, 854))
        val shared = FormatChoices.tierNames(portrait)
        assertEquals(FormatChoices.Tier.SD, shared["p540"])
        assertTrue("the lower SD row shows only its 480p", "p480" in shared && shared["p480"] == null)
        // Each tier's title and hint are distinct strings, so no two tiers can ever read alike.
        assertEquals(FormatChoices.Tier.entries.size, FormatChoices.Tier.entries.map { it.title }.toSet().size)
        assertEquals(FormatChoices.Tier.entries.size, FormatChoices.Tier.entries.map { it.hint }.toSet().size)
    }

    @Test fun otherVersionsCompareOnlyVisibleFacts() {
        val row = video("first", 426, 240)
        assertEquals(FormatChoices.shownKey(row), FormatChoices.shownKey(row.copy(id = "estimate", size = Size.Estimated(500))))
        val exact = row.copy(size = Size.Exact(1_000_001))
        assertEquals(FormatChoices.shownKey(exact), FormatChoices.shownKey(exact.copy(size = Size.Exact(1_000_002))))
        assertNotEquals(FormatChoices.shownKey(exact), FormatChoices.shownKey(exact.copy(size = Size.Exact(2_000_000))))
        assertNotEquals(FormatChoices.shownKey(row), FormatChoices.shownKey(row.copy(sourceHasNoSound = true, hasAudio = false)))
    }

    @Test fun simpleKeepsOneRealRowPerQualityPreferringPresetThenCompatibleCodec() {
        val av1 = video("av1-1080", 1080, 1920, codec = "av01", size = Size.Exact(5))
        val h264 = video("h264-1080", 1080, 1920, size = Size.Estimated(6))
        val wide = video("h264-1152", 1152, 2048)
        val hd = video("vp9-720", 720, 1280, codec = "vp9")
        val rows = listOf(av1, h264, wide, hd)
        assertEquals(listOf("h264-1080", "vp9-720"), FormatChoices.simple(rows).map { it.id })
        assertEquals(listOf("av1-1080", "vp9-720"), FormatChoices.simple(rows, preferred = setOf("av1-1080")).map { it.id })
    }

    @Test fun perQualityWinnerFollowsH264ThenHevcThenAv1ThenVp9() {
        val vp9 = video("vp9", 720, 1280, codec = "vp09.00.31.08", size = Size.Exact(1))
        val av1 = video("av1", 720, 1280, codec = "av01.0.05M.08", size = Size.Exact(2))
        val hevc = video("hevc", 720, 1280, codec = "hvc1.1.6.L93.90")
        val h264 = video("h264", 720, 1280, codec = "h264")
        assertEquals(listOf("h264"), FormatChoices.simple(listOf(vp9, av1, hevc, h264)).map { it.id })
        assertEquals(listOf("hevc"), FormatChoices.simple(listOf(vp9, av1, hevc)).map { it.id })
        assertEquals(listOf("av1"), FormatChoices.simple(listOf(vp9, av1)).map { it.id })
        val mkv = Format("vp9-mkv", "mkv", true, false, videoCodec = "vp9", width = 480, height = 848,
            audioFormatId = "a", requiresMerge = true)
        assertEquals("a VP9-only quality keeps its real mkv row", listOf("vp9-mkv"), FormatChoices.simple(listOf(mkv)).map { it.id })
    }

    @Test fun audioKeepsTheBestM4aAndOneStandardMp3() {
        val small = Format("a48", "m4a", false, true, bitrateKbps = 48, size = Size.Exact(1))
        val best = Format("a128", "m4a", false, true, bitrateKbps = 128, size = Size.Exact(2))
        val mp3 = Format("mp3-128", "mp3", false, true, conversion = AudioConversion.MP3_128, sourceFormatId = "a128", sourceContainer = "m4a")
        val mp3High = Format("mp3-320", "mp3", false, true, conversion = AudioConversion.MP3_320, sourceFormatId = "a128", sourceContainer = "m4a")
        assertEquals(listOf("a128", "mp3-128"), FormatChoices.simple(listOf(small, mp3High, best, mp3)).map { it.id })
        assertEquals(listOf("a48", "mp3-128"), FormatChoices.simple(listOf(small, best, mp3), preferred = setOf("a48")).map { it.id })
        val opus = Format("opus", "webm", false, true, bitrateKbps = 160)
        assertEquals(listOf("opus"), FormatChoices.simple(listOf(opus)).map { it.id })
    }

    @Test fun mainListMp3IsTheOneNinetyTwoRowWhenAllThreeExist() {
        val m4a = Format("a128", "m4a", false, true, bitrateKbps = 128, size = Size.Exact(2))
        fun mp3(c: AudioConversion) = Format("mp3-${c.bitrateKbps}", "mp3", false, true, conversion = c, sourceFormatId = "a128", sourceContainer = "m4a")
        val rows = listOf(m4a, mp3(AudioConversion.MP3_320), mp3(AudioConversion.MP3_128), mp3(AudioConversion.MP3_192))
        assertEquals(listOf("a128", "mp3-192"), FormatChoices.simple(rows).map { it.id })
        assertEquals(listOf("a128", "mp3-192"), FormatChoices.simple(rows.reversed()).map { it.id })
    }
}
