package dev.ahmed.savlino.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** External sessions are welcome, but can only transport the already-selected item. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class PlaybackControllerPolicy(private val packageName: String, private val uid: Int) : MediaSession.Callback {
    fun owns(controller: MediaSession.ControllerInfo) = controller.packageName == packageName && controller.uid == uid
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(if (owns(controller)) MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS else SessionCommands.EMPTY)
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
        fun transportCommands(): Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_STOP).build()
    }
}
