package dev.ahmed.savlino.playback

import android.app.Notification
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.session.*
import kotlinx.coroutines.runBlocking
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
            // Outside controllers (Android 13+ system controls) get only the ±10 s and Stop session commands.
            assertEquals(PlaybackControllerPolicy.TRANSPORT.toSet(), policy.onConnect(session, external).availableSessionCommands.commands)
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
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) fun notificationUsesOnlyAppBuiltTitleArtistAndCover() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val raw = RecordingPlayer()
        val metadata = PlaybackMetadata(context).load(Uri.parse("content://test/private/1")).buildUpon().setTitle("Saved track").build()
        raw.setMediaItem(MediaItem.Builder().setUri("content://test/private/1").setMediaMetadata(metadata).build())
        raw.tags(MediaMetadata.Builder().setTitle("private title").setArtist("private artist").setDescription("private comment").build())
        val player = PrivateAudioPlayer(raw, "Audio")
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
            assertEquals("Saved track", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(metadata.artist, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertNotNull(notification.getLargeIcon())
            assertFalse(notification.extras.toString().contains("private"))
            assertFalse(notification.extras.toString().contains("content://"))
            assertEquals(4, notification.actions.size)
        } finally { session.release(); player.release() }
    }
    @Test fun aFileWithSourceUrlInCommentCannotReplaceSafeSessionMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val link = "https://invalid.test/comment"
        // A real ID3v2.3 COMM tag, decoded by the same Media3 extractor as playback.
        val comment = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + link.toByteArray()
        val frame = "COMM".toByteArray() + java.nio.ByteBuffer.allocate(4).putInt(comment.size).array() + byteArrayOf(0, 0) + comment
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame
        val file = java.io.File(context.cacheDir, "comment-tag.mp3").apply { writeBytes(tag) }
        val bytes = file.readBytes()
        val timed = requireNotNull(Id3Decoder().decode(bytes, bytes.size))
        assertEquals(link, (timed[0] as CommentFrame).text)
        val raw = RecordingPlayer()
        val artwork = byteArrayOf(1, 2, 3)
        raw.setMediaItem(MediaItem.Builder().setUri("content://test/private/1").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Saved track").setArtist("Nezzel").setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setDescription(link).setArtworkUri(Uri.parse(link)).setExtras(android.os.Bundle().apply { putString("comment", link) }).build()).build())
        raw.tags(MediaMetadata.Builder().setTitle(link).setArtist(link).setDescription(link).setArtworkUri(Uri.parse(link)).build(), timed)
        raw.prepare()
        val safe = PrivateAudioPlayer(raw, "Audio")
        try {
            assertEquals("Saved track", safe.mediaMetadata.title)
            assertEquals("Nezzel", safe.mediaMetadata.artist)
            assertArrayEquals(artwork, safe.mediaMetadata.artworkData)
            assertNull(safe.mediaMetadata.artworkUri)
            assertNull(safe.mediaMetadata.description)
            assertNull(safe.mediaMetadata.extras)
            assertEquals(MediaMetadata.EMPTY, safe.playlistMetadata)
            var timedEvents = 0
            safe.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMetadata(metadata: androidx.media3.common.Metadata) { timedEvents++ }
            })
            raw.tags(MediaMetadata.Builder().setDescription(link).build(), timed)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, timedEvents)
        } finally { safe.release(); file.delete() }
        assertTrue(raw.released)
    }
}
