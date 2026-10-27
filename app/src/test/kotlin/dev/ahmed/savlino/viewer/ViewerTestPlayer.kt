package dev.ahmed.savlino.viewer

import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.ahmed.savlino.playback.RecordingPlayer

/** RecordingPlayer plus the bits the viewer reads: speed, picture size, seekability, buffering, errors and slow seeks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ViewerTestPlayer(val inner: RecordingPlayer = RecordingPlayer()) : ForwardingSimpleBasePlayer(inner) {
    var size: VideoSize = VideoSize.UNKNOWN; set(value) { field = value; invalidateState() }
    var seekable = true; set(value) { field = value; invalidateState() }
    var buffering = false; set(value) { field = value; invalidateState() }
    var refuseSpeed = false; set(value) { field = value; invalidateState() }
    var error: PlaybackException? = null; set(value) { field = value; invalidateState() }
    /** A slow seek: the player keeps reporting the old position until [catchUp]. */
    var slowSeeks = false
    private var pendingSeek: Long? = null
    fun catchUp() { pendingSeek?.let { pendingSeek = null; inner.seekTo(it) } }
    private var parameters = PlaybackParameters.DEFAULT

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setVideoSize(size).setPlaybackParameters(parameters)
            .setAvailableCommands(if (refuseSpeed) state.availableCommands.buildUpon().remove(Player.COMMAND_SET_SPEED_AND_PITCH).build() else state.availableCommands)
            .apply { if (buffering && state.playbackState == Player.STATE_READY) setPlaybackState(Player.STATE_BUFFERING) }
            .apply { error?.let { setPlayerError(it).setPlaybackState(Player.STATE_IDLE) } }
            .apply { if (!seekable) setPlaylist(state.playlist.map { it.buildUpon().setIsSeekable(false).setDurationUs(C.TIME_UNSET).build() }) }
            .build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!slowSeeks) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        pendingSeek = positionMs; return Futures.immediateVoidFuture()
    }
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters; return Futures.immediateVoidFuture()
    }
}

/** The test player seen as an ExoPlayer, so the viewer's scrubbing mode can be watched; every Player call goes to [inner]. */
internal class ScrubWatch(val inner: ViewerTestPlayer) {
    var scrubbing = false
    val player: androidx.media3.exoplayer.ExoPlayer = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
        arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { _, method, args ->
        when {
            method.name == "setScrubbingModeEnabled" -> { scrubbing = args[0] as Boolean; null }
            method.name == "isScrubbingModeEnabled" -> scrubbing
            else -> try { inner.javaClass.getMethod(method.name, *method.parameterTypes).invoke(inner, *(args ?: emptyArray())) }
                catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause!! }
        }
    } as androidx.media3.exoplayer.ExoPlayer
}
