package dev.ahmed.savlino.playback

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioPlaybackServiceTest {
    private val controller = Robolectric.buildService(TestAudioService::class.java).create()
    private val service get() = controller.get()
    @After fun cleanup() { controller.destroy() }
    @Test fun recentsDismissalReleasesEvenWhilePlaying() {
        playing()
        assertTrue(service.playbackSession!!.player.isPlaying)
        service.onTaskRemoved(Intent())
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun backgroundPauseTimeoutReleasesNotificationAndPlayer() {
        playing(); service.playbackSession!!.player.pause()
        service.foregroundChanged(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299_999))
        assertFalse(service.recording.released)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun transientFocusLossInBackgroundKeepsTheService() {
        playing(); service.foregroundChanged(false)
        shadowOf(Looper.getMainLooper()).idle() // SimpleBasePlayer ignores invalidateState while play() is pending
        service.recording.suppress()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("fake", service.recording.isPlaying)
        assertTrue("fake intent", service.recording.playWhenReady)
        assertFalse("wrapper", service.playbackSession!!.player.isPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertFalse(service.recording.released)
    }
    @Test fun mediaButtonWithNoItemStopsThroughForeground() {
        val button = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(android.content.ComponentName(service, TestAudioService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
        service.onStartCommand(button, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        // startForegroundService owes startForeground even when there is nothing to play; the id outlives the removal.
        assertNotEquals(0, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun ownedOpenAfterShutdownGetsAFreshSessionWhileStillBound() {
        val old = service.playbackSession!!
        service.shutdown()
        val viewer = controllerInfo(service.packageName, android.os.Process.myUid())
        val next = service.onGetSession(viewer)
        assertNotNull(next); assertNotSame(old, next)
        assertNull(service.onGetSession(controllerInfo("external.player", 10001)))
    }
    private fun controllerInfo(pkg: String, uid: Int) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(pkg, /* pid */ 0, uid, 0, 0, false, android.os.Bundle.EMPTY, true)
    @Test fun foregroundReturnCancelsPauseTimeout() {
        service.foregroundChanged(false)
        service.foregroundChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(service.recording.released)
    }
    @Test fun nullAndUnknownStartsStopWithoutRestart() {
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun unknownIntentStopsWithoutRestart() {
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent("hostile.replace"), 0, 1))
        assertTrue(service.recording.released)
    }
    private fun playing() {
        val player = service.playbackSession!!.player
        service.addSession(service.playbackSession!!)
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri("content://test/audio/1"))
        player.prepare(); player.play()
    }
    @Test fun stopAndEndReleaseWithoutAutoplay() {
        playing()
        service.playbackSession!!.player.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun completionReleasesWithoutSelectingAnotherItem() {
        playing()
        service.recording.end()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun validActionlessMedia3SelfStartDoesNotStopCurrentAudio() {
        playing()
        // Characterization of the pinned framework's otherwise private self-start protocol.
        val method = androidx.media3.session.MediaSessionService::class.java.getDeclaredMethod("getMediaNotificationManager")
        method.isAccessible = true
        val manager = method.invoke(service)
        val field = manager.javaClass.getDeclaredField("startSelfIntent"); field.isAccessible = true
        val start = Intent(field.get(manager) as Intent)
        assertNull(start.action)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(start, 0, 1))
        assertFalse(service.recording.released)
    }
    @Test fun bothResumptionCallbacksFail() {
        val callback = PlaybackControllerPolicy(service.packageName, android.os.Process.myUid())
        val session = service.playbackSession!!
        val caller = MediaSession.ControllerInfo.createTestOnlyControllerInfo("external.player", 10001, 0, 0, 0, false, android.os.Bundle.EMPTY, true)
        assertTrue(callback.onPlaybackResumption(session, caller).isDone)
        try { callback.onPlaybackResumption(session, caller).get(); fail() } catch (_: java.util.concurrent.ExecutionException) {}
        try { callback.onPlaybackResumption(session, caller, true).get(); fail() } catch (_: java.util.concurrent.ExecutionException) {}
    }
    @Test fun backgroundCountRequiresActualContinuedPlaybackAndOnlyFiresOnce() {
        service.foregroundChanged(true)
        playing()
        assertFalse(service.counts.contains(dev.ahmed.savlino.usage.UsageKeys.PLAYER_BACKGROUND))
        service.foregroundChanged(false)
        assertEquals(1, service.counts.count { it == dev.ahmed.savlino.usage.UsageKeys.PLAYER_BACKGROUND })
        service.foregroundChanged(true); service.foregroundChanged(false)
        assertEquals(1, service.counts.count { it == dev.ahmed.savlino.usage.UsageKeys.PLAYER_BACKGROUND })
    }
    @Test fun systemControlsGetBackForwardAndStopAsSessionCommands() {
        val actions = service.playbackSession!!.mediaButtonPreferences.map { it.sessionCommand!!.customAction }
        assertEquals(listOf(PlaybackControllerPolicy.BACK, PlaybackControllerPolicy.FORWARD, PlaybackControllerPolicy.STOP), actions)
        playing()
        val system = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.android.systemui", 10002, 0, 0, 0, false, android.os.Bundle.EMPTY, true)
        val callback = service.sessionCallback!!
        val unknown = callback.onCustomCommand(service.playbackSession!!, system, androidx.media3.session.SessionCommand("other", android.os.Bundle.EMPTY), android.os.Bundle.EMPTY).get()
        assertNotEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, unknown.resultCode)
        assertFalse(service.recording.released)
        val stop = androidx.media3.session.SessionCommand(PlaybackControllerPolicy.STOP, android.os.Bundle.EMPTY)
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, callback.onCustomCommand(service.playbackSession!!, system, stop, android.os.Bundle.EMPTY).get().resultCode)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.recording.released)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun notificationChannelIsSeparateAndLocalized() {
        val channel = service.getSystemService(NotificationManager::class.java).getNotificationChannel(AudioPlaybackService.CHANNEL)
        assertEquals(service.getString(dev.ahmed.savlino.R.string.playback_channel), channel.name)
    }
}
class TestAudioService : AudioPlaybackService() {
    internal val recording = RecordingPlayer()
    internal val counts = mutableListOf<String>()
    override fun createEnvironment() = PlaybackEnvironment(this, PlayerFactory { _, _ -> recording }, usage = object : dev.ahmed.savlino.usage.UsageEvents {
        override fun count(key: String) { counts += key }
    }, sizeOf = { 10L })
}
