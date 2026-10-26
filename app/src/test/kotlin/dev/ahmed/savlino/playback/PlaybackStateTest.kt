package dev.ahmed.savlino.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// PlaybackException reads SystemClock, so this needs the Android runtime.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackStateTest {
    @Test fun errorsAreTypedWithoutRetainingDiagnosticText() {
        val cases = mapOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to PlaybackFailure.UNAVAILABLE,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to PlaybackFailure.UNAVAILABLE,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to PlaybackFailure.UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to PlaybackFailure.UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to PlaybackFailure.UNSUPPORTED,
            PlaybackException.ERROR_CODE_UNSPECIFIED to PlaybackFailure.OTHER)
        for ((code, expected) in cases) assertEquals(expected, playbackFailure(PlaybackException("private", null, code)))
    }
}
