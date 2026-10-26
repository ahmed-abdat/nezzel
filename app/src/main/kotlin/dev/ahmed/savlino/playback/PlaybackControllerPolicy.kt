package dev.ahmed.savlino.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** External sessions are welcome, but can only transport the already-selected item. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class PlaybackControllerPolicy(private val packageName: String, private val uid: Int) : MediaSession.Callback {
    fun owns(controller: MediaSession.ControllerInfo) = controller.packageName == packageName && controller.uid == uid
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(if (owns(controller)) MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().addSessionCommands(TRANSPORT).build()
                else SessionCommands.Builder().addSessionCommands(TRANSPORT).build())
            .setAvailablePlayerCommands(if (owns(controller)) Player.Commands.Builder().addAllCommands().build() else transportCommands())
            .build()
    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        if (!owns(controller)) return Futures.immediateFailedFuture(SecurityException())
        return try {
            require(mediaItems.size == 1)
            mediaItems.forEach { ContentPlaybackSource.requireContent(it.localConfiguration?.uri ?: throw LocalSourceException(PlaybackFailure.UNAVAILABLE)) }
            Futures.immediateFuture(mediaItems)
        } catch (_: Exception) { Futures.immediateFailedFuture(LocalSourceException(PlaybackFailure.UNAVAILABLE)) }
    }
    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return Futures.transform(onAddMediaItems(session, controller, mediaItems),
            { items -> MediaSession.MediaItemsWithStartPosition(items!!, 0, startPositionMs.coerceAtLeast(0)) }, Runnable::run)
    }
    @Suppress("OVERRIDE_DEPRECATION") // Explicitly keep old platform callers from post-death resumption too.
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException())
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        Futures.immediateFailedFuture(UnsupportedOperationException())
    companion object {
        /** Android 13+ system media controls show only session custom actions, so ±10 s and Stop also exist as these. */
        const val BACK = "dev.ahmed.savlino.playback.BACK"
        const val FORWARD = "dev.ahmed.savlino.playback.FORWARD"
        const val STOP = "dev.ahmed.savlino.playback.STOP"
        val TRANSPORT = listOf(BACK, FORWARD, STOP).map { SessionCommand(it, Bundle.EMPTY) }
        fun transportCommands(): Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_STOP).build()
    }
}
