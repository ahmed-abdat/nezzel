package dev.ahmed.savlino.domain

import kotlin.test.*

class ResultJudgeTest {
    private val merged = Format("137", "mp4", hasVideo = true, hasAudio = true, videoCodec = "avc1", audioCodec = "mp4a",
        width = 1920, height = 1080, audioFormatId = "140", requiresMerge = true)
    private val direct = Format("hd", "mp4", hasVideo = true, hasAudio = true, width = 720, height = 1280)
    private val gif = Format("gif", "mp4", hasVideo = true, hasAudio = true, width = 480, height = 270, animatedGif = true)
    private val m4a = Format("140", "m4a", hasVideo = false, hasAudio = true, audioCodec = "mp4a")
    private val mp3 = Format("140-mp3", "mp3", hasVideo = false, hasAudio = true, conversion = AudioConversion.MP3_192,
        sourceFormatId = "140", sourceContainer = "m4a")

    private fun judge(format: Format?, facts: ProbeFacts?, staged: Long? = 1000) = ResultJudge.judge(format, staged, facts)

    @Test fun videoWithSoundIsVerified() =
        assertEquals(ResultCheck.Verified(video = true, audio = true), judge(merged, ProbeFacts(1, 1, 60_000, 1000)))

    @Test fun promisedSoundMissingIsAProblem() =
        assertEquals(ResultCheck.Problem.NO_SOUND, judge(merged, ProbeFacts(1, 0, 60_000, 1000)))

    @Test fun mergedVideoOnlyRowWhoseOutputLostItsSoundIsAProblem() {
        // YtdlpMetadataMapper keeps the video row's hasAudio=false and adds the audio partner via audioFormatId.
        val pair = merged.copy(hasAudio = false, audioCodec = null)
        assertEquals(ResultCheck.Problem.NO_SOUND, judge(pair, ProbeFacts(1, 0, 60_000, 1000)))
        assertEquals(ResultCheck.Verified(video = true, audio = true), judge(pair, ProbeFacts(1, 1, 60_000, 1000)))
    }

    @Test fun missingPictureIsAProblem() =
        assertEquals(ResultCheck.Problem.NO_PICTURE, judge(merged, ProbeFacts(0, 1, 60_000, 1000)))

    @Test fun sizeMismatchIsDamaged() =
        assertEquals(ResultCheck.Problem.DAMAGED, judge(merged, ProbeFacts(1, 1, 60_000, 999)))

    @Test fun zeroDurationIsDamagedButUnknownDurationIsNot() {
        assertEquals(ResultCheck.Problem.DAMAGED, judge(merged, ProbeFacts(1, 1, 0, 1000)))
        assertEquals(ResultCheck.Verified(true, true), judge(merged, ProbeFacts(1, 1, null, 1000)))
    }

    @Test fun unknownSizesAreNeverCompared() =
        assertEquals(ResultCheck.Verified(true, true), judge(merged, ProbeFacts(1, 1, 60_000, null), staged = null))

    @Test fun animatedGifWantsNoSound() =
        assertEquals(ResultCheck.Verified(video = true, audio = false), judge(gif, ProbeFacts(1, 0, 3_000, 1000)))

    @Test fun unreportedAudioFoundOrMissing() {
        assertTrue(direct.unreportedStreams)
        assertEquals(ResultCheck.Verified(true, true), judge(direct, ProbeFacts(1, 1, 9_000, 1000)))
        assertEquals(ResultCheck.NoSoundExpected, judge(direct, ProbeFacts(1, 0, 9_000, 1000)))
    }

    @Test fun audioOnlyRowsWantSound() {
        assertEquals(ResultCheck.Verified(video = false, audio = true), judge(m4a, ProbeFacts(0, 1, 200_000, 1000)))
        assertEquals(ResultCheck.Verified(video = false, audio = true), judge(mp3, ProbeFacts(0, 1, 200_000, 1000)))
        assertEquals(ResultCheck.Problem.NO_SOUND, judge(mp3, ProbeFacts(0, 0, 200_000, 1000)))
    }

    @Test fun imagesAndFailedProbesClaimNothing() {
        assertEquals(ResultCheck.Unchecked, judge(null, ProbeFacts(0, 0, null, 1000)))
        assertEquals(ResultCheck.Unchecked, judge(merged, null))
    }
}
