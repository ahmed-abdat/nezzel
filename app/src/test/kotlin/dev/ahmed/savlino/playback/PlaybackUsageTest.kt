package dev.ahmed.savlino.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.ahmed.savlino.usage.*
import dev.ahmed.savlino.viewer.ViewerItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackUsageTest {
    @Test fun audioSwipeDoesNotCountAndRepeatedPlayCountsOneOpen() {
        val counts = mutableListOf<String>()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), usage = object : UsageEvents {
            override fun count(key: String) { counts += key }
        })
        val owner = PlaybackCoordinator(env, NoAudioConnection)
        val audio = ViewerItem(Uri.parse("content://test/audio/1"), false, "audio/mpeg", isAudio = true)
        owner.settle(audio, false)
        assertTrue(counts.isEmpty())
        owner.play(); owner.play(); owner.pause(); owner.play()
        assertEquals(listOf(UsageKeys.PLAYER_OPEN_AUDIO), counts)
        owner.close()
    }
    @Test fun initialAudioOpenAndFailureRetryHaveNoDuplicateCounts() {
        val counts = mutableListOf<String>()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), usage = object : UsageEvents {
            override fun count(key: String) { counts += key }
        })
        val audio = object : AudioConnection {
            override fun open(item: ViewerItem, onPlayer: (Player) -> Unit, onFailure: () -> Unit) { onFailure(); onFailure() }
            override fun close() {}
        }
        val owner = PlaybackCoordinator(env, audio)
        owner.settle(ViewerItem(Uri.parse("content://test/audio/2"), false, "audio/mpeg", isAudio = true), true)
        owner.retry()
        assertEquals(1, counts.count { it == UsageKeys.PLAYER_OPEN_AUDIO })
        assertEquals(1, counts.count { it == UsageKeys.PLAYER_FAILED })
        owner.close()
    }
    @Test fun videoRetryCountsOneOpen() {
        val counts = mutableListOf<String>()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> RecordingPlayer() },
            usage = object : UsageEvents { override fun count(key: String) { counts += key } }, sizeOf = { 10L })
        val owner = PlaybackCoordinator(env, NoAudioConnection)
        owner.settle(ViewerItem(Uri.parse("content://test/video/retry"), true, "video/mp4"), true)
        owner.retry(); owner.retry()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, counts.count { it == UsageKeys.PLAYER_OPEN_VIDEO })
        owner.close()
    }
    @Test fun backgroundAndFailureBoundariesDedupeAcrossServiceRetry() {
        val counts = mutableListOf<String>()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), usage = object : UsageEvents { override fun count(key: String) { counts += key } })
        env.countBackground("one"); env.countBackground("one")
        env.countFailure("one"); env.countFailure("one")
        env.countBackground("two")
        assertEquals(2, counts.count { it == UsageKeys.PLAYER_BACKGROUND })
        assertEquals(1, counts.count { it == UsageKeys.PLAYER_FAILED })
    }
}
