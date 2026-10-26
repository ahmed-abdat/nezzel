package dev.ahmed.savlino.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.ahmed.savlino.R
import dev.ahmed.savlino.playback.PlaybackControllerPolicy.Companion.BACK
import dev.ahmed.savlino.playback.PlaybackControllerPolicy.Companion.FORWARD
import dev.ahmed.savlino.playback.PlaybackControllerPolicy.Companion.STOP
import dev.ahmed.savlino.viewer.ViewerItem
import kotlinx.coroutines.*

/** Audio is handed in by the visible viewer. Binding and transport alone cannot select media. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class AudioPlaybackService : MediaSessionService() {
    private lateinit var environment: PlaybackEnvironment
    private var playback: PlaybackSession? = null
    internal var playbackSession: MediaSession? = null; private set
    internal var sessionCallback: MediaSession.Callback? = null; private set
    private var pausedTimeout: PlaybackCancellation? = null
    private var visibility: Job? = null
    private var stateObserver: Job? = null
    private var inForeground = false
    private var backgroundCounted = false
    private var stopped = false
    private var destroyed = false
    private var launcher: PendingIntent? = null
    private lateinit var policy: PlaybackControllerPolicy
    private var requestVersion = 0
    private var openId = ""
    private val stopAction: () -> Unit = { shutdown() }
    protected open fun createEnvironment(): PlaybackEnvironment = PlaybackEnvironment.get(this)

    override fun onCreate() {
        super.onCreate()
        environment = createEnvironment()
        inForeground = environment.foreground.value
        val strings = dev.ahmed.savlino.locale.AppLocale.localized(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,
            strings.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW))
        launcher = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        setMediaNotificationProvider(PlaybackNotification(this, launcher))
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                playback?.failure(IllegalStateException()); shutdown()
            }
        })
        policy = PlaybackControllerPolicy(packageName, android.os.Process.myUid())
        start()
    }

    /** A bound controller can keep this instance alive past shutdown; the next owned open starts it again. */
    private fun start() {
        stopped = false; backgroundCounted = false; openId = ""; inForeground = environment.foreground.value
        val strings = dev.ahmed.savlino.locale.AppLocale.localized(this)
        val player = PrivateAudioPlayer(environment.playerFactory.create(this, true), strings.getString(R.string.playback_audio))
        environment.audioState.value = PlaybackState.Idle
        playback = PlaybackSession(player, environment, ended = ::shutdown, failed = { player.pause() }, openId = { openId })
        stateObserver = environment.scope.launch { playback!!.state.collect { environment.audioState.value = it } }
        val callback = object : PlaybackControllerPolicy(packageName, android.os.Process.myUid()) {
            override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
                startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                if (!owns(controller) || stopped || mediaItems.size != 1)
                    return Futures.immediateFailedFuture(SecurityException())
                val source = mediaItems.single()
                if (!environment.foreground.value) {
                    val id = source.mediaMetadata.extras?.getString(PlaybackSession.OPEN_ID) ?: java.util.UUID.randomUUID().toString()
                    environment.countFailure(id)
                    environment.audioState.value = PlaybackState.Failed(PlaybackFailure.OTHER)
                    return Futures.immediateFailedFuture(IllegalStateException())
                }
                // The pinned controller bundles localConfiguration; the request URI covers a future Media3 that drops it.
                val uri = source.localConfiguration?.uri ?: source.requestMetadata.mediaUri ?: return Futures.immediateFailedFuture(LocalSourceException(PlaybackFailure.UNAVAILABLE))
                val item = ViewerItem(uri, false, source.localConfiguration?.mimeType ?: "audio/mpeg", isAudio = true,
                    resumePolicy = if (source.mediaMetadata.extras?.getBoolean(PlaybackSession.SAVED) == true) ResumePolicy.SAVED else ResumePolicy.SESSION_ONLY)
                val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                val expected = ++requestVersion
                environment.scope.launch {
                    try {
                        openId = source.mediaMetadata.extras?.getString(PlaybackSession.OPEN_ID) ?: java.util.UUID.randomUUID().toString()
                        ContentPlaybackSource.requireContent(uri)
                        val tracker = playback ?: throw CancellationException()
                        player.pause()
                        val position = tracker.resolve(item)
                        if (stopped || expected != requestVersion) throw CancellationException()
                        backgroundCounted = false
                        result.set(MediaSession.MediaItemsWithStartPosition(listOf(tracker.mediaItem(item, position)), 0, position))
                    } catch (_: CancellationException) { result.cancel(false) }
                    catch (error: Exception) {
                        playback?.failure(error)
                        result.setException(LocalSourceException(playbackFailure(error)))
                    }
                }
                return result
            }
            // Add has no current-item activation/restore path; keep this a single-item service.
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                Futures.immediateFailedFuture(UnsupportedOperationException())
            // Transport only, like the player commands every controller already has: no item can be chosen this way.
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: android.os.Bundle): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    BACK -> if (hasItem) session.player.seekBack()
                    FORWARD -> if (hasItem) session.player.seekForward()
                    STOP -> environment.scheduler.after(0) { shutdown() }
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            override fun onPlayerInteractionFinished(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommands: Player.Commands) {
                if (playerCommands.contains(Player.COMMAND_STOP)) environment.scheduler.after(0) { shutdown() }
            }
        }
        fun button(icon: Int, label: Int, action: String, slot: Int) = CommandButton.Builder(icon).setDisplayName(strings.getString(label))
            .setSessionCommand(SessionCommand(action, android.os.Bundle.EMPTY)).setSlots(slot).build()
        val buttons = listOf(button(CommandButton.ICON_SKIP_BACK_10, R.string.playback_back_10, BACK, CommandButton.SLOT_BACK),
            button(CommandButton.ICON_SKIP_FORWARD_10, R.string.playback_forward_10, FORWARD, CommandButton.SLOT_FORWARD),
            button(CommandButton.ICON_STOP, R.string.playback_stop, STOP, CommandButton.SLOT_OVERFLOW))
        sessionCallback = callback
        playbackSession = MediaSession.Builder(this, player).setCallback(callback).setMediaButtonPreferences(buttons)
            .apply { launcher?.let(::setSessionActivity) }.build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { updateBackgroundPolicy() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updateBackgroundPolicy() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && player.playerError == null) environment.scheduler.after(0) { shutdown() }
            }
        })
        environment.stopAudio?.invoke()
        environment.stopAudio = stopAction
        visibility = environment.scope.launch { environment.foreground.collect { foregroundChanged(it) } }
    }
    private val hasItem get() = !stopped && playback?.player?.mediaItemCount?.let { it > 0 } == true
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (stopped && !destroyed && policy.owns(controllerInfo) && !mediaButton(controllerInfo)) start()
        // No item: a media button (or an outside controller) has nothing to resume. Null makes Media3 stop with its
        // own shutdown notification, which also settles a pending startForegroundService.
        return playbackSession?.takeIf { hasItem || (policy.owns(controllerInfo) && !mediaButton(controllerInfo)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mediaButton = intent?.action == Intent.ACTION_MEDIA_BUTTON
        val selfStart = intent != null && hasItem && intent.action == null && intent.hasExtra("androidx.media3.session.intent.uid")
        if (!mediaButton && !selfStart) { shutdown(); return START_NOT_STICKY }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { shutdown() }
    internal fun foregroundChanged(foreground: Boolean) { inForeground = foreground; updateBackgroundPolicy() }
    private fun updateBackgroundPolicy() {
        if (stopped) return
        val player = playback?.player ?: return
        if (!inForeground && player.isPlaying && !backgroundCounted) {
            backgroundCounted = true; environment.countBackground(openId)
        }
        // playWhenReady stays true through a call or other transient focus loss: only a real pause starts the timer.
        if (inForeground || player.isPlaying || player.playWhenReady) { pausedTimeout?.cancel(); pausedTimeout = null }
        else if (pausedTimeout == null) pausedTimeout = environment.scheduler.after(300_000) { shutdown() }
    }
    internal fun shutdown() {
        if (stopped) return
        stopped = true; requestVersion++
        pausedTimeout?.cancel(); pausedTimeout = null
        visibility?.cancel(); visibility = null
        stateObserver?.cancel(); stateObserver = null
        if (environment.audioState.value !is PlaybackState.Failed) environment.audioState.value = PlaybackState.Ended
        playback?.checkpoint()
        pauseAllPlayersAndStopSelf()
        // A session is only added once a controller connects; removing an unadded one throws.
        playbackSession?.let { if (isSessionAdded(it)) removeSession(it); it.release() }; playbackSession = null
        playback?.close(); playback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        if (environment.stopAudio === stopAction) environment.stopAudio = null
        stopSelf()
    }
    override fun onDestroy() { destroyed = true; shutdown(); super.onDestroy() }
    companion object {
        const val CHANNEL = "playback"
        const val NOTIFICATION = 7401
        private fun mediaButton(controller: MediaSession.ControllerInfo) =
            controller.connectionHints.getString("androidx.media3.session.hint.controller_info_type") == Intent.ACTION_MEDIA_BUTTON
    }
}
