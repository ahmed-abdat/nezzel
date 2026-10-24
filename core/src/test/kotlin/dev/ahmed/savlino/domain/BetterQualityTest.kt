package dev.ahmed.savlino.domain

import kotlin.test.*

class BetterQualityTest {
    private fun video(id: String, w: Int?, h: Int?, codec: String = "avc1") =
        Format(id, "mp4", hasVideo = true, hasAudio = true, videoCodec = codec, audioCodec = "mp4a", width = w, height = h)

    private val offered = listOf(video("360", 640, 360), video("480", 854, 480), video("720", 1280, 720), video("1080", 1920, 1080))

    @Test fun higherTiersOnlyHighestFirst() =
        assertEquals(listOf("1080", "720"), BetterQuality.above(offered, video("s", 854, 480)).map { it.id })

    @Test fun portraitCountsItsShortSide() =
        assertEquals(listOf("p1080"), BetterQuality.above(listOf(video("p1080", 1080, 1920), video("p480", 480, 854)),
            video("s", 720, 1280)).map { it.id })

    @Test fun sameTierOtherCodecIsNotBetter() =
        assertEquals(emptyList(), BetterQuality.above(listOf(video("vp9", 854, 480, "vp9")), video("s", 854, 480)))

    @Test fun audioSavedOrUnknownSizeFindsNothing() {
        val audio = Format("140", "m4a", hasVideo = false, hasAudio = true, audioCodec = "mp4a")
        assertEquals(emptyList(), BetterQuality.above(offered, audio))
        assertEquals(emptyList(), BetterQuality.above(offered, video("s", null, null)))
        assertEquals(emptyList(), BetterQuality.above(listOf(video("u", null, null), audio), video("s", 854, 480)))
    }

    @Test fun onlyReturnedFormatsAreListed() =
        assertEquals(emptyList(), BetterQuality.above(emptyList(), video("s", 854, 480)))
}
