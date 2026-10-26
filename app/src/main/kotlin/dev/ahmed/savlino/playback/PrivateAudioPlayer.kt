package dev.ahmed.savlino.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Metadata

/** System media surfaces consume session metadata directly, independent of notification text. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PrivateAudioPlayer(player: Player, label: String) : ForwardingSimpleBasePlayer(player) {
    private val generic = MediaMetadata.Builder().setTitle(label).build()
    override fun getState(): State {
        val current = super.getState()
        return current.buildUpon().setPlaylist(current.timeline, current.currentTracks, generic)
            .setPlaylistMetadata(MediaMetadata.EMPTY).setTimedMetadata(Metadata(emptyList())).build()
    }
}
