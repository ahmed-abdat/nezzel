package dev.ahmed.savlino.playback

import androidx.media3.common.PlaybackException

/** Persistence is opt-in at the saved-file caller; expiring source previews remain in memory. */
enum class ResumePolicy { SESSION_ONLY, SAVED }
enum class PlaybackFailure { UNAVAILABLE, UNSUPPORTED, OTHER }
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Ready : PlaybackState
    data object Ended : PlaybackState
    data class Failed(val failure: PlaybackFailure) : PlaybackState
}

fun playbackFailure(error: Throwable): PlaybackFailure {
    if (error is LocalSourceException) return error.failure
    if (error is SecurityException || error is java.io.FileNotFoundException) return PlaybackFailure.UNAVAILABLE
    if (error is PlaybackException) return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailure.UNAVAILABLE
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure.UNSUPPORTED
        else -> error.cause?.let { if (it is LocalSourceException) it.failure else PlaybackFailure.OTHER } ?: PlaybackFailure.OTHER
    }
    return PlaybackFailure.OTHER
}
