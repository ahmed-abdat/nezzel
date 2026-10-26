package dev.ahmed.savlino.playback

import android.net.Uri
import dev.ahmed.savlino.usage.*
import dev.ahmed.savlino.viewer.ViewerItem
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCoordinatorTest {
    private fun item(id: Int, audio: Boolean = false) = ViewerItem(Uri.parse("content://test/items/$id"), !audio,
        if (audio) "audio/mpeg" else "video/mp4", isAudio = audio)
    @Test fun settleReplacesBeforeAcquireAndRecompositionDoesNotReopen() {
        val players = mutableListOf<RecordingPlayer>()
        val counts = mutableListOf<String>()
        var audioStarts = 0
        val environment = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ ->
            assertTrue(players.all { it.released }); RecordingPlayer().also(players::add)
        }, usage = object : UsageEvents { override fun count(key: String) { counts += key } }, sizeOf = { 10L })
        val audio = object : AudioConnection {
            override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) { audioStarts++ }
            override fun close() {}
        }
        val owner = PlaybackCoordinator(environment, audio)
        owner.settle(item(1), initiallyTapped = true)
        owner.settle(item(1), initiallyTapped = false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, players.size)
        owner.settle(item(2), initiallyTapped = false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(players[0].released)
        owner.settle(item(3, true), initiallyTapped = false)
        assertTrue(players[1].released)
        assertEquals(0, audioStarts)
        owner.play()
        owner.play()
        assertEquals(1, audioStarts)
        assertEquals(2, counts.count { it == UsageKeys.PLAYER_OPEN_VIDEO })
        owner.close()
    }
    @Test fun manualVideoPauseSurvivesBackgroundAndForeground() {
        val player = RecordingPlayer()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> player }, sizeOf = { 10L })
        val owner = PlaybackCoordinator(env, NoAudioConnection)
        owner.settle(item(1), true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        owner.pause()
        owner.foreground(false)
        owner.foreground(true)
        assertFalse(player.playWhenReady)
        owner.close()
        assertTrue(player.released)
    }
    @Test fun processRestorationDoesNotAllocateAutoplayOrCount() {
        var creates = 0
        val counts = mutableListOf<String>()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> creates++; RecordingPlayer() },
            usage = object : UsageEvents { override fun count(key: String) { counts += key } }, sizeOf = { 10L })
        val owner = PlaybackCoordinator(env, NoAudioConnection)
        owner.settle(item(1), true, restoreOnly = true)
        assertEquals(0, creates)
        assertTrue(counts.isEmpty())
        owner.play()
        assertEquals(1, creates)
        assertEquals(listOf(UsageKeys.PLAYER_OPEN_VIDEO), counts)
        owner.close()
    }
    @Test fun noisyOrPermanentFocusPauseDoesNotRestartOnForegroundReturn() {
        for (reason in listOf(androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
            val player = RecordingPlayer()
            val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> player }, sizeOf = { 10L })
            val owner = PlaybackCoordinator(env, NoAudioConnection)
            owner.settle(item(1), true)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            player.interrupt(reason)
            owner.foreground(false); owner.foreground(true)
            assertFalse(player.playWhenReady)
            owner.close()
        }
    }
    @Test fun lateAudioConnectionCannotResurrectReplacedItem() {
        var connected: ((androidx.media3.common.Player) -> Unit)? = null
        var closes = 0
        val audio = object : AudioConnection {
            override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) { connected = onPlayer }
            override fun close() { closes++ }
        }
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> RecordingPlayer() }, sizeOf = { 10L })
        val owner = PlaybackCoordinator(env, audio)
        owner.settle(item(1, true), true)
        owner.settle(item(2), false)
        connected!!(RecordingPlayer())
        assertFalse(owner.item!!.isAudio)
        assertTrue(closes > 0)
        owner.close()
    }
}
internal object NoAudioConnection : AudioConnection {
    override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) {}
    override fun close() {}
}
