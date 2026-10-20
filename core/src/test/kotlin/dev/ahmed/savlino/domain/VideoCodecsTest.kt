package dev.ahmed.savlino.domain

import kotlin.test.*

class VideoCodecsTest {
    @Test fun rankPrefersH264ThenHevcThenAv1ThenVp9() {
        val ranked = listOf("vp09.00.40.08", "av01.0.08M.08", "hvc1.1.6.L120.90", "avc1.4d401f").sortedByDescending(VideoCodecs::rank)
        assertEquals(listOf("avc1.4d401f", "hvc1.1.6.L120.90", "av01.0.08M.08", "vp09.00.40.08"), ranked)
        assertEquals(VideoCodecs.rank("h264"), VideoCodecs.rank("avc1"))
        assertEquals(VideoCodecs.rank("vp9"), VideoCodecs.rank(null))
    }

    @Test fun onlyReportedVp9OrVp8IsKnownMp4Unsafe() {
        for (codec in listOf("vp9", "vp09.00.31.08", "VP9", "vp8", "vp08.00.10.08")) assertTrue(VideoCodecs.knownMp4Unsafe(codec), codec)
        for (codec in listOf(null, "avc1.64001f", "h264", "hev1.1.6", "av01.0.05M.08", "mp4v.20.8")) assertFalse(VideoCodecs.knownMp4Unsafe(codec), codec)
        assertTrue(VideoCodecs.mp4Safe("av01.0.05M.08")); assertFalse(VideoCodecs.mp4Safe(null)); assertFalse(VideoCodecs.mp4Safe("vp9"))
    }

    @Test fun animatedGifRequiresVideo() {
        assertFailsWith<IllegalArgumentException> { Format("a", "m4a", false, true, animatedGif = true) }
        assertTrue(Format("g", "mp4", true, true, animatedGif = true).animatedGif)
    }
}
