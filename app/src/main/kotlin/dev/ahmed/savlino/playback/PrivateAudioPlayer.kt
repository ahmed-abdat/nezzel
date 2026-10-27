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
        // currentMetadata includes extractor/file tags. Only the app-built item's allowlist is public.
        val source = player.currentMediaItem?.mediaMetadata
        val safe = source?.let { MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist)
            .setArtworkData(it.artworkData, it.artworkDataType).build() } ?: generic
        return current.buildUpon().setPlaylist(current.timeline, current.currentTracks, safe)
            .setPlaylistMetadata(MediaMetadata.EMPTY).setTimedMetadata(Metadata(emptyList())).build()
    }
}
