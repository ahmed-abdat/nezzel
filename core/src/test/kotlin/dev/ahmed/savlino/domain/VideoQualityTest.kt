package dev.ahmed.savlino.domain

import kotlin.test.*

class VideoQualityTest {
    @Test fun portraitLandscapeAndCinemaCropsReadAsTheirCommonStep() {
        assertEquals(1080, VideoQuality.of(1080, 1920))
        assertEquals(1080, VideoQuality.of(1152, 2048))
        assertEquals(1080, VideoQuality.of(1080, 2400))
        assertEquals(1080, VideoQuality.of(1920, 1080))
        assertEquals(1080, VideoQuality.of(1920, 816))
        assertEquals(720, VideoQuality.of(1280, 544))
        assertEquals(720, VideoQuality.of(720, 1280))
        assertEquals(540, VideoQuality.of(540, 960))
        assertEquals(1080, VideoQuality.of(1080, 1080))
    }

    @Test fun missingWidthUsesHeightAndMissingHeightIsUnknown() {
        assertEquals(480, VideoQuality.of(null, 480))
        assertNull(VideoQuality.of(1920, null))
        assertNull(VideoQuality.of(0, 0))
        assertEquals(720, VideoQuality.of(0, 720))
        assertEquals(100, VideoQuality.of(100, 178))
    }
}
