package dev.ahmed.savlino.playback

import android.app.Notification
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPrivacyTest {
    private fun controller(pkg: String, uid: Int) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(pkg, uid, 0, 0, 0, false, android.os.Bundle.EMPTY, true)
    @Test fun acceptsAllControllersButReplacementRequiresPackageAndUid() {
        val context = RuntimeEnvironment.getApplication()
        val player = RecordingPlayer()
        val session = MediaSession.Builder(context, player).build()
        try {
            val policy = PlaybackControllerPolicy(context.packageName, android.os.Process.myUid())
            val external = controller("other.player", 10001)
            assertTrue(policy.onConnect(session, external).isAccepted)
            val commands = policy.onConnect(session, external).availablePlayerCommands
            assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_PLAY_PAUSE))
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS))
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM))
            val hostile = listOf(MediaItem.fromUri("https://invalid.test/a"))
            for (caller in listOf(external, controller(context.packageName, 10001))) {
                try { policy.onSetMediaItems(session, caller, hostile, 0, 0).get(); fail() } catch (_: java.util.concurrent.ExecutionException) {}
                try { policy.onAddMediaItems(session, caller, hostile).get(); fail() } catch (_: java.util.concurrent.ExecutionException) {}
            }
            val own = controller(context.packageName, android.os.Process.myUid())
            try { policy.onSetMediaItems(session, own, hostile, 0, 0).get(); fail() } catch (_: java.util.concurrent.ExecutionException) {}
            assertEquals(0, player.mediaItemCount)
        } finally { session.release(); player.release() }
    }
    @Test fun notificationIgnoresTitleUriAndArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val player = RecordingPlayer()
        player.setMediaItem(MediaItem.Builder().setUri("content://test/private/1").setMediaMetadata(MediaMetadata.Builder().setTitle("private title").build()).build())
        player.prepare()
        val session = MediaSession.Builder(context, player).build()
        val pending = android.app.PendingIntent.getService(context, 0, android.content.Intent("transport"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val actions = object : MediaNotification.ActionFactory {
            override fun createMediaAction(session: MediaSession, icon: androidx.core.graphics.drawable.IconCompat, title: CharSequence, command: Int) =
                androidx.core.app.NotificationCompat.Action(icon, title, pending)
            override fun createCustomAction(session: MediaSession, icon: androidx.core.graphics.drawable.IconCompat, title: CharSequence, action: String, extras: android.os.Bundle) =
                androidx.core.app.NotificationCompat.Action(icon, title, pending)
            override fun createCustomActionFromCustomCommandButton(session: MediaSession, button: CommandButton) =
                androidx.core.app.NotificationCompat.Action(android.R.drawable.ic_media_play, "", pending)
            override fun createMediaActionPendingIntent(session: MediaSession, command: Int) = pending
        }
        try {
            val notification = PlaybackNotification(context, null).createNotification(session, com.google.common.collect.ImmutableList.of(), actions) {}.notification
            assertEquals(context.getString(dev.ahmed.savlino.R.string.playback_audio), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertFalse(notification.extras.toString().contains("private"))
            assertFalse(notification.extras.toString().contains("content://"))
            assertEquals(4, notification.actions.size)
        } finally { session.release(); player.release() }
    }
    @Test fun sessionMetadataDoesNotExposeEmbeddedTitleOrArtwork() {
        val raw = RecordingPlayer()
        raw.setMediaItem(MediaItem.Builder().setUri("content://test/private/1").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("private title").setArtist("private artist").setArtworkUri(Uri.parse("https://invalid.test/art")).build()).build())
        raw.prepare()
        val safe = PrivateAudioPlayer(raw, "Audio")
        assertEquals("Audio", safe.mediaMetadata.title)
        assertNull(safe.mediaMetadata.artist)
        assertNull(safe.mediaMetadata.artworkUri)
        safe.release()
        assertTrue(raw.released)
    }
}
