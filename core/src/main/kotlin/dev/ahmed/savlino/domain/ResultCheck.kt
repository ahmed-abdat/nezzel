package dev.ahmed.savlino.domain

/** What opening the saved file showed. Counts come from the platform's track list; null = not readable. */
data class ProbeFacts(val videoTracks: Int, val audioTracks: Int, val durationMs: Long? = null, val savedBytes: Long? = null) {
    init { require(videoTracks >= 0 && audioTracks >= 0) }
}

/** The verdict on a saved file. Only measured facts; anything unreadable is [Unchecked] and claims nothing. */
sealed interface ResultCheck {
    data class Verified(val video: Boolean, val audio: Boolean) : ResultCheck
    /** The file has no sound, and none was promised (a silent direct file). */
    data object NoSoundExpected : ResultCheck
    enum class Problem : ResultCheck { NO_SOUND, NO_PICTURE, DAMAGED }
    data object Unchecked : ResultCheck
}

object ResultJudge {
    /**
     * [format] is the confirmed format (null for images and other files: never checked);
     * [stagedBytes] is the staged length captured before publish; [facts] null when the probe failed.
     */
    fun judge(format: Format?, stagedBytes: Long?, facts: ProbeFacts?): ResultCheck {
        if (format == null || facts == null) return ResultCheck.Unchecked
        if (facts.savedBytes != null && stagedBytes != null && facts.savedBytes != stagedBytes) return ResultCheck.Problem.DAMAGED
        // A null duration (some MKV/WebM) is never a problem; only an explicit zero is
        if (facts.durationMs == 0L) return ResultCheck.Problem.DAMAGED
        if (format.hasVideo && facts.videoTracks == 0) return ResultCheck.Problem.NO_PICTURE
        // X animated GIFs carry no sound by design; unreported audio is not a promise
        val promisedAudio = format.hasAudio && !format.animatedGif && !format.unreportedStreams
        if (promisedAudio && facts.audioTracks == 0) return ResultCheck.Problem.NO_SOUND
        if (format.unreportedStreams && !format.animatedGif && facts.audioTracks == 0) return ResultCheck.NoSoundExpected
        return ResultCheck.Verified(video = format.hasVideo, audio = facts.audioTracks > 0 && !format.animatedGif)
    }
}
