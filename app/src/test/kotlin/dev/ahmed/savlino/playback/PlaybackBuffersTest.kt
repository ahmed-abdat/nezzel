package dev.ahmed.savlino.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackBuffersTest {
    private fun startsWith(uri: String, bufferedMs: Long, stalled: Boolean): Boolean {
        val timeline = SinglePeriodTimeline(60_000_000, true, false, false, null, MediaItem.fromUri(uri))
        val control = PlaybackBuffers.loadControl()
        control.onPrepared(PlayerId.UNSET)
        return control.shouldStartPlayback(LoadControl.Parameters(PlayerId.UNSET, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0, bufferedMs * 1_000, 1f, true, stalled, C.TIME_UNSET, 0))
    }

    @Test fun aSeekPlaysAgainAfterAQuarterSecondNotOne() {
        for (uri in listOf("content://media/external/video/media/1", "https://example.invalid/a.mp4")) {
            assertTrue(uri, startsWith(uri, PlaybackBuffers.START_MS.toLong(), stalled = false))
            assertFalse(uri, startsWith(uri, 100, stalled = false))
        }
    }

    @Test fun aStallWaitsHalfASecondNotTwo() {
        for (uri in listOf("content://media/external/video/media/1", "https://example.invalid/a.mp4")) {
            assertTrue(uri, startsWith(uri, PlaybackBuffers.AFTER_STALL_MS.toLong(), stalled = true))
            assertFalse(uri, startsWith(uri, PlaybackBuffers.START_MS.toLong(), stalled = true))
        }
    }
}
