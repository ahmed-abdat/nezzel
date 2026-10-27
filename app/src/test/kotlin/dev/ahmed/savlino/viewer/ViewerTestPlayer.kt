package dev.ahmed.savlino.viewer

import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.ahmed.savlino.playback.RecordingPlayer

/** RecordingPlayer plus the bits the viewer reads: speed, picture size, seekability and buffering. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ViewerTestPlayer(val inner: RecordingPlayer = RecordingPlayer()) : ForwardingSimpleBasePlayer(inner) {
    var size: VideoSize = VideoSize.UNKNOWN; set(value) { field = value; invalidateState() }
    var seekable = true; set(value) { field = value; invalidateState() }
    var buffering = false; set(value) { field = value; invalidateState() }
    var refuseSpeed = false; set(value) { field = value; invalidateState() }
    private var parameters = PlaybackParameters.DEFAULT

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setVideoSize(size).setPlaybackParameters(parameters)
            .setAvailableCommands(if (refuseSpeed) state.availableCommands.buildUpon().remove(Player.COMMAND_SET_SPEED_AND_PITCH).build() else state.availableCommands)
            .apply { if (buffering && state.playbackState == Player.STATE_READY) setPlaybackState(Player.STATE_BUFFERING) }
            .apply { if (!seekable) setPlaylist(state.playlist.map { it.buildUpon().setIsSeekable(false).setDurationUs(C.TIME_UNSET).build() }) }
            .build()
    }
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters; return Futures.immediateVoidFuture()
    }
}
