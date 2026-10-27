package dev.ahmed.savlino.playback

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import dev.ahmed.savlino.R

/** Uses the private player's allowlisted metadata; no artwork URI or network data source. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackNotification(private val base: Context, private val launcher: PendingIntent?) : MediaNotification.Provider {
    private val context: Context get() = dev.ahmed.savlino.locale.AppLocale.localized(base)
    // Decoded once per cover: the notification is rebuilt on every player state change, and Media3 copies the bytes.
    private var art: Pair<ByteArray, android.graphics.Bitmap?>? = null
    override fun getNotificationChannelInfo() = MediaNotification.Provider.NotificationChannelInfo(
        AudioPlaybackService.CHANNEL, context.getString(R.string.playback_channel))
    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) = false
    override fun createNotification(session: MediaSession, mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory, onNotificationChangedCallback: MediaNotification.Provider.Callback): MediaNotification {
        val player = session.player
        val metadata = player.mediaMetadata
        val builder = NotificationCompat.Builder(context, AudioPlaybackService.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nezzel).setContentTitle(metadata.title ?: context.getString(R.string.playback_audio))
            .setContentText(metadata.artist ?: context.getString(R.string.app_name))
            .setLargeIcon(metadata.artworkData?.let(::artwork))
            .setContentIntent(launcher).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setOngoing(player.playWhenReady)
            .setDeleteIntent(actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_STOP))
        fun action(icon: Int, label: Int, command: Int) {
            builder.addAction(actionFactory.createMediaAction(session, IconCompat.createWithResource(context, icon), context.getString(label), command))
        }
        action(android.R.drawable.ic_media_rew, R.string.playback_back_10, Player.COMMAND_SEEK_BACK)
        action(if (player.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (player.playWhenReady) R.string.viewer_pause else R.string.viewer_play, Player.COMMAND_PLAY_PAUSE)
        action(android.R.drawable.ic_media_ff, R.string.playback_forward_10, Player.COMMAND_SEEK_FORWARD)
        action(android.R.drawable.ic_menu_close_clear_cancel, R.string.playback_stop, Player.COMMAND_STOP)
        builder.setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1, 2))
        return MediaNotification(AudioPlaybackService.NOTIFICATION, builder.build())
    }
    private fun artwork(bytes: ByteArray): android.graphics.Bitmap? = art?.takeIf { it.first.contentEquals(bytes) }?.second
        ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { art = bytes to it }
}
