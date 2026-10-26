package dev.ahmed.savlino.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.ahmed.savlino.usage.UsageKeys
import dev.ahmed.savlino.viewer.ViewerItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

interface AudioConnection {
    var onStopped: () -> Unit
        get() = {}
        set(value) {}
    var openId: String
        get() = ""
        set(value) {}
    fun open(item: ViewerItem, onPlayer: (Player) -> Unit, onFailure: () -> Unit)
    fun close()
}

/** Controller futures can finish after a swipe/close: their generation must still own the viewer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SessionAudioConnection(private val environment: PlaybackEnvironment) : AudioConnection {
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var generation = 0
    override var onStopped: () -> Unit = {}
    override var openId: String = ""
    override fun open(item: ViewerItem, onPlayer: (Player) -> Unit, onFailure: () -> Unit) {
        close()
        val expected = ++generation
        val future = MediaController.Builder(environment.context, SessionToken(environment.context,
            ComponentName(environment.context, AudioPlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) { if (generation == expected) { this@SessionAudioConnection.controller = null; onStopped() } }
            }).buildAsync()
        pending = future
        future.addListener({
            if (generation != expected) return@addListener
            try {
                val connected = future.get()
                controller = connected; pending = null
                connected.setMediaItem(MediaItem.Builder().setMediaId(UUID.randomUUID().toString()).setUri(item.uri).setMimeType(item.mimeType)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(item.uri).build())
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                        putBoolean(PlaybackSession.SAVED, item.resumePolicy == ResumePolicy.SAVED)
                        putString(PlaybackSession.OPEN_ID, openId)
                    }).build()).build())
                onPlayer(connected)
                connected.prepare()
            } catch (_: Exception) { if (generation == expected) onFailure() }
        }, ContextCompat.getMainExecutor(environment.context))
    }
    override fun close() {
        generation++
        controller?.let { it.stop(); it.release() }; controller = null
        pending?.let(MediaController::releaseFuture); pending = null
        environment.stopAudio?.invoke()
    }
}

/** Retained by the viewer; owns one video tracker OR a service controller, never both. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCoordinator internal constructor(private val environment: PlaybackEnvironment, private val audio: AudioConnection) {
    var item: ViewerItem? = null; private set
    val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val player = MutableStateFlow<Player?>(null)
    val resumedAt = MutableStateFlow(0L)
    private var tracker: PlaybackSession? = null
    private var videoLoad: Job? = null
    private var observe: Job? = null
    private var generation = 0
    private var wantsPlay = false
    private var visible = true
    private var audioOpened = false
    private var audioCounted = false
    private var failureCounted = false
    private var closed = false
    private var suppressDisconnect = false
    private var openId = UUID.randomUUID().toString()
    private var resumeDismissed = false
    private val videoIntentListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) wantsPlay = false
        }
    }
    private val audioListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (item?.isAudio != true) return
            if (!resumeDismissed) resumedAt.value = player.currentMediaItem?.mediaMetadata?.extras?.getLong(PlaybackSession.RESUMED_AT) ?: 0
            state.value = player.playerError?.let { PlaybackState.Failed(playbackFailure(it)) } ?: when (player.playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Loading
                Player.STATE_READY -> PlaybackState.Ready
                Player.STATE_ENDED -> PlaybackState.Ended
                else -> state.value
            }
        }
    }
    init {
        audio.onStopped = {
            observe?.cancel(); observe = null
            player.value?.removeListener(audioListener); player.value = null; audioOpened = false
            state.value = environment.audioState.value.let { if (it is PlaybackState.Failed) it else PlaybackState.Ended }
        }
    }
    fun settle(next: ViewerItem, initiallyTapped: Boolean, restoreOnly: Boolean = false) {
        if (closed || item == next) return
        releaseItem()
        environment.claim(this, ::close)
        item = next; wantsPlay = false; audioOpened = false; audioCounted = false; failureCounted = false
        openId = UUID.randomUUID().toString(); audio.openId = openId; resumeDismissed = false
        resumedAt.value = 0; state.value = PlaybackState.Idle
        if (restoreOnly) return
        if (next.isVideo && !next.isAudio) {
            environment.usage.count(UsageKeys.PLAYER_OPEN_VIDEO)
            wantsPlay = true; openVideo(next)
        } else if (next.isAudio && initiallyTapped && visible) play()
    }
    private fun openVideo(next: ViewerItem) {
        val expected = generation
        try {
            ContentPlaybackSource.requireContent(next.uri)
            val session = PlaybackSession(environment.playerFactory.create(environment.context, false), environment,
                failed = { failureCounted = true }, openId = { openId })
            tracker = session; session.player.addListener(videoIntentListener); player.value = session.player
            observe = environment.scope.launch { session.state.collect { state.value = it; resumedAt.value = session.resumedAt } }
            videoLoad = environment.scope.launch {
                session.open(next)
                if (!closed && generation == expected && state.value !is PlaybackState.Failed) session.player.playWhenReady = wantsPlay && visible
            }
        } catch (error: Exception) { fail(error) }
    }
    fun play() {
        if (closed || !visible) return
        val current = item ?: return
        wantsPlay = true
        if (current.isVideo && !current.isAudio && tracker == null) {
            environment.usage.count(UsageKeys.PLAYER_OPEN_VIDEO)
            openVideo(current)
            return
        }
        if (current.isAudio && !audioOpened) {
            audioOpened = true
            if (!audioCounted) { audioCounted = true; environment.usage.count(UsageKeys.PLAYER_OPEN_AUDIO) }
            observe?.cancel()
            observe = environment.scope.launch { environment.audioState.collect { if (it != PlaybackState.Idle) state.value = it } }
            state.value = PlaybackState.Loading
            val expected = generation
            audio.open(current, onPlayer = { connected ->
                if (closed || generation != expected) return@open
                player.value = connected; connected.addListener(audioListener); connected.playWhenReady = wantsPlay
            }, onFailure = { if (!suppressDisconnect && generation == expected) fail(IllegalStateException()) })
        } else player.value?.play()
    }
    fun pause() { wantsPlay = false; player.value?.pause(); tracker?.checkpoint() }
    fun seek(position: Long) { player.value?.seekTo(position.coerceAtLeast(0)); tracker?.checkpoint() }
    fun startOver() { resumeDismissed = true; resumedAt.value = 0; tracker?.clear(); seek(0) }
    fun foreground(foreground: Boolean) {
        visible = foreground
        if (item?.isAudio != true) player.value?.playWhenReady = wantsPlay && foreground && state.value !is PlaybackState.Failed
    }
    fun retry() {
        val current = item ?: return
        if (!visible) return
        val wasAudio = audioOpened
        val hadFailure = failureCounted
        releaseItem()
        failureCounted = hadFailure
        wantsPlay = true
        // Retry is the same explicit open: no second open count for either kind.
        if (current.isAudio) {
            audioOpened = wasAudio
            val expected = generation
            state.value = PlaybackState.Loading
            observe = environment.scope.launch { environment.audioState.collect { if (it != PlaybackState.Idle) state.value = it } }
            audio.open(current, { connected -> if (generation == expected) { player.value = connected; connected.addListener(audioListener); connected.playWhenReady = wantsPlay } },
                { if (generation == expected) fail(IllegalStateException()) })
        } else openVideo(current)
    }
    private fun fail(error: Throwable) {
        state.value = PlaybackState.Failed(playbackFailure(error))
        if (!failureCounted) { failureCounted = true; environment.countFailure(openId) }
    }
    private fun releaseItem() {
        generation++; videoLoad?.cancel(); videoLoad = null; observe?.cancel(); observe = null
        tracker?.player?.removeListener(videoIntentListener)
        tracker?.close(); tracker = null
        player.value?.removeListener(audioListener); player.value = null
        suppressDisconnect = true; audio.close(); suppressDisconnect = false
    }
    fun close() {
        if (closed) return
        closed = true; releaseItem(); environment.abandon(this)
    }
}
