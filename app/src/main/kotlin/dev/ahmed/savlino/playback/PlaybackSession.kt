package dev.ahmed.savlino.playback

import android.os.Bundle
import androidx.media3.common.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.viewer.ViewerItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Tracks one player's current item and checkpoints. The service and viewer own their players separately. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackSession(val player: Player, private val environment: PlaybackEnvironment,
    private val ended: () -> Unit = {}, private val failed: () -> Unit = {}, private val openId: () -> String = { "" }) {
    val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    var resumedAt = 0L; private set
    private var item: ViewerItem? = null
    private var size: Long? = null
    private var version = 0
    private var closed = false
    private var failureCounted = false
    private var finished = false
    private var tick: PlaybackCancellation? = null
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (closed) return
            when (playbackState) {
                Player.STATE_BUFFERING -> state.value = PlaybackState.Loading
                Player.STATE_READY -> state.value = PlaybackState.Ready
                Player.STATE_ENDED -> { finished = true; clear(); state.value = PlaybackState.Ended; ended() }
            }
        }
        override fun onPlayerError(error: PlaybackException) { failure(error) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) checkpoint(); scheduleTick() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) checkpoint() }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A loop wrap is a finished watch: stop checkpointing until the next open, or the tick re-saves it.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { clear(); finished = true }
            else checkpoint()
        }
    }
    init { player.addListener(listener) }

    /** Resolve before setting media; version fences asynchronous IO from a replaced/closed owner. */
    suspend fun resolve(next: ViewerItem): Long {
        check(!closed)
        val expected = ++version
        checkpoint()
        ContentPlaybackSource.requireContent(next.uri)
        state.value = PlaybackState.Loading
        val bytes = environment.sizeOf(next)
        val position = if (next.resumePolicy == ResumePolicy.SAVED && bytes != null) environment.resume.position(next, bytes, next.durationMillis ?: 0)
            else environment.sessionPositions[next.uri]?.takeUnless { PlaybackResumeStore.finished(it, next.durationMillis ?: 0) } ?: 0
        if (closed || version != expected) throw CancellationException()
        item = next; size = bytes; resumedAt = position; finished = false; failureCounted = false
        return position
    }
    suspend fun open(next: ViewerItem) {
        try {
            val position = resolve(next)
            player.repeatMode = if (next.isAudio) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            player.setMediaItem(mediaItem(next, position), position)
            player.prepare()
            scheduleTick()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error) }
    }
    fun mediaItem(next: ViewerItem, position: Long): MediaItem = MediaItem.Builder()
        .setMediaId(UUID.randomUUID().toString()).setUri(next.uri).setMimeType(next.mimeType)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(dev.ahmed.savlino.locale.AppLocale.localized(environment.context).getString(R.string.playback_audio))
            .setExtras(Bundle().apply { putBoolean(SAVED, next.resumePolicy == ResumePolicy.SAVED); putLong(RESUMED_AT, position) }).build()).build()
    fun checkpoint() {
        if (closed || finished) return
        val current = item ?: return
        val bytes = size
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: current.durationMillis ?: 0
        if (current.resumePolicy == ResumePolicy.SESSION_ONLY || bytes == null) {
            if (position <= 0 || PlaybackResumeStore.finished(position, duration)) environment.sessionPositions.remove(current.uri)
            else environment.sessionPositions[current.uri] = position
            while (environment.sessionPositions.size > 200) environment.sessionPositions.remove(environment.sessionPositions.keys.first())
        }
        environment.scope.launch { environment.resume.save(current, bytes, position, duration) }
    }
    fun clear() {
        val current = item ?: return
        val bytes = size
        resumedAt = 0
        environment.sessionPositions.remove(current.uri)
        environment.scope.launch { environment.resume.clear(current, bytes) }
    }
    fun startOver() { clear(); player.seekTo(0) }
    fun failure(error: Throwable) {
        if (closed) return
        state.value = PlaybackState.Failed(playbackFailure(error))
        if (!failureCounted) { failureCounted = true; environment.countFailure(openId()) }
        failed()
    }
    private fun scheduleTick() {
        tick?.cancel(); tick = null
        if (!closed && player.isPlaying) tick = environment.scheduler.after(5_000) { checkpoint(); scheduleTick() }
    }
    fun close() {
        if (closed) return
        checkpoint(); closed = true; version++
        tick?.cancel(); tick = null
        player.removeListener(listener)
        player.release()
    }
    companion object {
        const val OPEN_ID = "playback_open"
        const val SAVED = "saved_playback"
        const val RESUMED_AT = "resumed_at"
    }
}
