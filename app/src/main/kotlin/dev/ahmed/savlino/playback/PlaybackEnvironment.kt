package dev.ahmed.savlino.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.viewer.ViewerItem
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every file is local, so a seek plays again after a quarter second of read-ahead instead of Media3's one second
 * (two after a stall when it can't tell the source is local). The long read-ahead limits stay Media3's.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackBuffers {
    const val START_MS = 250
    const val AFTER_STALL_MS = 500
    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, START_MS, AFTER_STALL_MS)
        .setBufferDurationsMsForLocalPlayback(DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS, START_MS, AFTER_STALL_MS)
        .build()
}

fun interface PlayerFactory { fun create(context: Context, audio: Boolean): Player }
fun interface PlaybackCancellation { fun cancel() }
fun interface PlaybackScheduler { fun after(milliseconds: Long, action: () -> Unit): PlaybackCancellation }

/** Shared dependencies, IO writer and visibility; no application/startup playback side effect. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEnvironment(
    context: Context,
    val playerFactory: PlayerFactory = PlayerFactory { app, audio ->
        ExoPlayer.Builder(app)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(ContentPlaybackSource.Factory(app)))
            .setLoadControl(PlaybackBuffers.loadControl())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(if (audio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setWakeMode(if (audio) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE).build()
    },
    val usage: UsageEvents = (context.applicationContext as? NezzelApplication)?.usage ?: UsageEvents.None,
    val now: () -> Long = System::currentTimeMillis,
    val scheduler: PlaybackScheduler = PlaybackScheduler { milliseconds, action ->
        val handler = Handler(Looper.getMainLooper()); val runnable = Runnable(action)
        handler.postDelayed(runnable, milliseconds)
        PlaybackCancellation { handler.removeCallbacks(runnable) }
    },
    val sizeOf: suspend (ViewerItem) -> Long? = { item ->
        withContext(Dispatchers.IO) {
            ContentPlaybackSource.requireContent(item.uri)
            try {
                context.contentResolver.query(item.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
                }
            } catch (_: Exception) { null }
        }
    },
) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val resume = PlaybackResumeStore(File(this.context.noBackupFilesDir, "playback.json"), now)
    val foreground = MutableStateFlow(false)
    internal val audioState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    /** Natural completion only; Stop/disconnect also report Ended but must never select another file. */
    internal var completedAudioOpenId: String? = null
    internal val sessionPositions = linkedMapOf<android.net.Uri, Long>()
    private val backgroundOpens = linkedSetOf<String>()
    internal fun countBackground(openId: String) {
        if (backgroundOpens.add(openId)) usage.count(dev.ahmed.savlino.usage.UsageKeys.PLAYER_BACKGROUND)
        while (backgroundOpens.size > 200) backgroundOpens.remove(backgroundOpens.first())
    }
    private val failedOpens = linkedSetOf<String>()
    internal fun countFailure(openId: String) {
        if (failedOpens.add(openId)) usage.count(dev.ahmed.savlino.usage.UsageKeys.PLAYER_FAILED)
        while (failedOpens.size > 200) failedOpens.remove(failedOpens.first())
    }
    internal var stopAudio: (() -> Unit)? = null
    // release() waits on the playback thread while it frees the codec (40-60 ms on a phone): a closed viewer's player is
    // stopped at once (frees the codec, drops audio focus) and released once that's done, off the close frame.
    private val stopped = linkedMapOf<Player, PlaybackCancellation?>()
    internal fun releaseLater(player: Player) {
        player.clearVideoSurface(); player.stop()
        stopped[player] = null
        val timer = scheduler.after(RELEASE_DELAY_MS) { if (stopped.containsKey(player)) { stopped.remove(player); player.release() } }
        if (stopped.containsKey(player)) stopped[player] = timer
    }
    /** Releases every stopped player now: a new video opens, the app leaves the foreground, or the viewer's activity ends. */
    internal fun releaseStopped() { stopped.keys.toList().forEach { stopped.remove(it)?.cancel(); it.release() } }
    private val activities = mutableSetOf<Activity>()
    private var owner: Any? = null
    private var stopOwner: (() -> Unit)? = null

    internal fun claim(next: Any, stop: () -> Unit) {
        if (owner !== next) stopOwner?.invoke()
        owner = next; stopOwner = stop
    }
    internal fun abandon(old: Any) { if (owner === old) { owner = null; stopOwner = null } }
    internal fun attach(activity: Activity) {
        val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        if (lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true) {
            activities += activity; foreground.value = true
        }
    }
    internal fun watch(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { activities += activity; foreground.value = true }
            override fun onActivityStopped(activity: Activity) {
                activities -= activity
                // Rotation is not leaving the app; the replacement activity will bind the retained viewer.
                if (activities.isEmpty() && !activity.isChangingConfigurations) { foreground.value = false; releaseStopped() }
            }
            override fun onActivityDestroyed(activity: Activity) { activities -= activity }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        })
    }
    companion object {
        internal const val RELEASE_DELAY_MS = 1_000L
        private val applications = java.util.WeakHashMap<Context, PlaybackEnvironment>()
        @Synchronized fun get(context: Context): PlaybackEnvironment {
            val application = context.applicationContext
            val environment = applications.getOrPut(application) { PlaybackEnvironment(application).also { it.watch(application as Application) } }
            if (context is Activity) environment.attach(context)
            return environment
        }
    }
}
