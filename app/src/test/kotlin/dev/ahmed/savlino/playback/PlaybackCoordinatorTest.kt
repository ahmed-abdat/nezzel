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

    @Test fun finishedAudioPlaysOnlyItsImmediateAudioNeighbourOnce() {
        val players = mutableListOf<RecordingPlayer>()
        val env = videoEnvironment(mutableListOf())
        val audio = object : AudioConnection {
            override var openId: String = ""
            override var onStopped: () -> Unit = {}
            override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) {
                env.audioState.value = PlaybackState.Idle
                onPlayer(RecordingPlayer().also {
                    it.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri)); it.prepare(); players += it
                })
            }
            override fun close() {}
        }
        val owner = PlaybackCoordinator(env, audio)
        val first = item(1, true)
        val second = item(2, true)
        val third = item(3, true)
        val clip = item(4)
        try {
            owner.settle(first, true)
            looper.idle()
            assertNull("A playing song cannot advance", owner.advanceAudio(listOf(first, second)))
            owner.state.value = PlaybackState.Ended
            assertNull("Stop is not a natural completion", owner.advanceAudio(listOf(first, second)))
            players.last().end(); looper.idle()
            // The real service releases its controller on completion, before Compose can move the page.
            env.completedAudioOpenId = audio.openId
            env.audioState.value = PlaybackState.Ended
            audio.onStopped()
            assertNull(owner.player.value)
            assertEquals(second, owner.advanceAudio(listOf(first, second, third)))
            looper.idle()
            assertEquals(second.uri, players.last().currentMediaItem!!.localConfiguration!!.uri)
            assertTrue(players.last().playWhenReady)
            assertNull("The previous end cannot skip the new song", owner.advanceAudio(listOf(first, second, third)))
            players.last().end(); looper.idle()
            assertNull("Never wrap at the end", owner.advanceAudio(listOf(first, second)))
            assertNull("Never skip over video", owner.advanceAudio(listOf(first, second, clip, third)))
            assertNull("A missing current item has no next item", owner.advanceAudio(listOf(third)))
            assertEquals(2, players.size)
        } finally { owner.close() }
    }
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
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(PlaybackEnvironment.RELEASE_DELAY_MS))
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
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(PlaybackEnvironment.RELEASE_DELAY_MS))
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
    private val looper get() = shadowOf(android.os.Looper.getMainLooper())
    private fun videoEnvironment(players: MutableList<RecordingPlayer>) = PlaybackEnvironment(RuntimeEnvironment.getApplication(),
        PlayerFactory { _, _ -> RecordingPlayer().also(players::add) }, sizeOf = { 10L })
    @Test fun closingTheViewerStopsThePlayerAtOnceAndReleasesItAfterTheCodecIsFreed() {
        val players = mutableListOf<RecordingPlayer>()
        val owner = PlaybackCoordinator(videoEnvironment(players), NoAudioConnection)
        owner.settle(item(1), true)
        looper.idle()
        owner.close()
        val player = players.single()
        // The close frame only detaches the surface and stops (codec freed, audio focus dropped on the playback thread).
        assertTrue(player.surfaceCleared)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertFalse("release() blocks the main thread on the codec: never on the close frame", player.released)
        looper.idleFor(Duration.ofMillis(PlaybackEnvironment.RELEASE_DELAY_MS))
        assertEquals(1, player.releases)
        looper.idleFor(Duration.ofSeconds(5))
        assertEquals(1, player.releases)
        assertEquals(0, player.afterRelease)
    }
    @Test fun aNewVideoReleasesEveryStoppedPlayerFirstAndEachOnlyOnce() {
        val players = mutableListOf<RecordingPlayer>()
        val environment = videoEnvironment(players)
        repeat(3) { index ->
            PlaybackCoordinator(environment, NoAudioConnection).apply { settle(item(index), true); looper.idle(); close() }
        }
        // Each open released the one closed before it; the last closed is still waiting for its delay.
        assertEquals(listOf(1, 1, 0), players.map { it.releases })
        val next = PlaybackCoordinator(environment, NoAudioConnection)
        next.settle(item(9), true)
        assertTrue(players.take(3).all { it.releases == 1 })
        looper.idleFor(Duration.ofSeconds(5))
        assertTrue(players.take(3).all { it.releases == 1 && it.afterRelease == 0 })
        assertFalse(players[3].released)
        next.close()
    }
    @Test fun leavingTheAppOrEndingTheViewerActivityReleasesStoppedPlayersAtOnce() {
        val players = mutableListOf<RecordingPlayer>()
        val application = RuntimeEnvironment.getApplication()
        val environment = videoEnvironment(players).also { it.watch(application) }
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().start()
        PlaybackCoordinator(environment, NoAudioConnection).apply { settle(item(1), true); looper.idle(); close() }
        assertFalse(players[0].released)
        activity.stop()
        assertEquals(1, players[0].releases)
        // The viewer's retained registry ends with its activity: its stopped players go with it.
        val store = androidx.lifecycle.ViewModelStore()
        val registry = androidx.lifecycle.ViewModelProvider.create(store)[dev.ahmed.savlino.viewer.ViewerPlaybackRegistry::class]
        registry.environment = environment
        registry.sessions["viewer"] = PlaybackCoordinator(environment, NoAudioConnection).apply { settle(item(2), true); looper.idle(); close() }
        assertFalse(players[1].released)
        store.clear()
        assertEquals(1, players[1].releases)
        looper.idleFor(Duration.ofSeconds(5))
        assertTrue(players.all { it.releases == 1 && it.afterRelease == 0 })
    }
    @Test fun theUiLetsGoOfThePlayerBeforeItsStopEventsArrive() {
        val players = mutableListOf<RecordingPlayer>()
        val owner = PlaybackCoordinator(videoEnvironment(players), NoAudioConnection)
        owner.settle(item(1), true)
        looper.idle()
        // ViewerVideo follows the player only while the owner still exposes it.
        val exposedDuringStop = mutableListOf<androidx.media3.common.Player?>()
        players[0].onStop = { exposedDuringStop += owner.player.value }
        owner.close()
        assertEquals(listOf<androidx.media3.common.Player?>(null), exposedDuringStop)
    }
    @Test fun aFlushCancelsTheDelayedReleases() {
        val pending = mutableListOf<() -> Unit>()
        var cancelled = 0
        val players = mutableListOf<RecordingPlayer>()
        val environment = PlaybackEnvironment(RuntimeEnvironment.getApplication(), PlayerFactory { _, _ -> RecordingPlayer().also(players::add) },
            sizeOf = { 10L }, scheduler = PlaybackScheduler { _, action -> pending += action; PlaybackCancellation { cancelled++; pending -= action } })
        environment.releaseLater(RecordingPlayer().also(players::add))
        environment.releaseLater(RecordingPlayer().also(players::add))
        assertEquals(2, pending.size)
        environment.releaseStopped()
        assertTrue(pending.isEmpty())
        assertEquals(2, cancelled)
        assertTrue(players.all { it.releases == 1 })
    }
}
internal object NoAudioConnection : AudioConnection {
    override fun open(item: ViewerItem, onPlayer: (androidx.media3.common.Player) -> Unit, onFailure: () -> Unit) {}
    override fun close() {}
}
