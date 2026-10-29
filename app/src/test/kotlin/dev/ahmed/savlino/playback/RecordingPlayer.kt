package dev.ahmed.savlino.playback

import android.os.Looper
import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** No codecs, threads or test-utils artifact: session tests drive actual Player commands/listeners. */
internal class RecordingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var released = false
    var releases = 0
    /** Commands that reached the player after release(): a released player must not be touched. */
    var afterRelease = 0
    var surfaceCleared = false
    /** Runs inside stop(), where ExoPlayer delivers its stop events synchronously. */
    var onStop: () -> Unit = {}
    var prepared = 0
    private var position = 0L
    private var value = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
    override fun getState() = value.buildUpon().setContentPositionMs { position }.build()
    private fun update(next: State): ListenableFuture<*> { value = next; return Futures.immediateVoidFuture() }
    override fun handleSetMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> { position = startPositionMs.coerceAtLeast(0); return update(value.buildUpon()
        .setPlaylist(items.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(100_000_000).setIsSeekable(true).build() })
        .setCurrentMediaItemIndex(if (startIndex < 0) 0 else startIndex).setContentPositionMs(position).build()) }
    override fun handlePrepare(): ListenableFuture<*> { touch(); prepared++; return update(value.buildUpon().setPlaybackState(Player.STATE_READY).build()) }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = touch().update(value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build())
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = update(value.buildUpon().setRepeatMode(repeatMode).build())
    @Suppress("OVERRIDE_DEPRECATION")
    override fun handleSetVolume(volume: Float): ListenableFuture<*> = update(value.buildUpon().setVolume(volume).build())
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> { touch(); position = positionMs.coerceAtLeast(0); return update(value.buildUpon().setContentPositionMs(position).build()) }
    override fun handleStop(): ListenableFuture<*> = touch().also { onStop() }.update(value.buildUpon().setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build())
    override fun handleRelease(): ListenableFuture<*> { released = true; releases++; return Futures.immediateVoidFuture() }
    private fun touch() = also { if (released) afterRelease++ }
    // PlayerSurface attaches a surface on every video page; all commands are advertised, so accept it.
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> { touch(); return Futures.immediateVoidFuture() }
    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> { touch(); surfaceCleared = true; return Futures.immediateVoidFuture() }
    fun at(position: Long) { this.position = position }
    /** The video renderer drew its first picture on the surface. */
    fun renderFirstFrame() {
        value = value.buildUpon().setNewlyRenderedFirstFrame(true).build(); invalidateState()
        value = value.buildUpon().setNewlyRenderedFirstFrame(false).build()
    }
    fun tags(metadata: MediaMetadata, timed: Metadata = Metadata(emptyList())) {
        value = value.buildUpon().setPlaylist(value.timeline, value.currentTracks, metadata)
            .setPlaylistMetadata(metadata).setTimedMetadata(timed).build()
        invalidateState()
    }
    fun loop() { position = 0; value = value.buildUpon().setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0).build(); invalidateState() }
    fun interrupt(reason: Int) { value = value.buildUpon().setPlayWhenReady(false, reason).build(); invalidateState() }
    fun suppress() { value = value.buildUpon().setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS).build(); invalidateState() }
    fun end() { value = value.buildUpon().setPlaybackState(Player.STATE_ENDED).build(); invalidateState() }
    fun failure(code: Int) { value = value.buildUpon().setPlaybackState(Player.STATE_IDLE).setPlayerError(PlaybackException("private", null, code)).build(); invalidateState() }
}
