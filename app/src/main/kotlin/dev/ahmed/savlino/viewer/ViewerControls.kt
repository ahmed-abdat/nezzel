package dev.ahmed.savlino.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.ahmed.savlino.locale.NezzelFormat
import kotlin.math.abs

/**
 * Viewer chrome shared by the top bar and a media page: both show and hide together. Saved across rotation with the
 * chosen speed (for one file) and the fullscreen request, so a recreated viewer picks up where it was.
 */
@Stable
internal class ViewerControls(shown: Boolean = true, speed: Float = 1f, speedFor: String? = null,
                              fullscreen: Boolean = false, orientationBefore: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
    var shown by mutableStateOf(shown); private set
    /** Bumped by every interaction, so the auto-hide wait starts over. */
    var touches by mutableIntStateOf(0); private set
    var menuOpen by mutableStateOf(false)
    var volumeOpen by mutableStateOf(false)
    var scrubbing by mutableStateOf(false)
    private var focused by mutableStateOf(emptySet<String>())
    var speed by mutableFloatStateOf(speed); private set
    var speedFor by mutableStateOf(speedFor); private set
    var fullscreen by mutableStateOf(fullscreen); private set
    var orientationBefore = orientationBefore; private set

    fun show() { shown = true; touches++ }
    fun hide() { shown = false; menuOpen = false; volumeOpen = false }
    fun toggle() = if (shown) hide() else show()
    fun touched() { touches++ }
    fun focus(part: String, has: Boolean) { focused = if (has) focused + part else focused - part }
    /** The timer waits while a person is scrubbing, choosing in a menu or moving focus through the controls. */
    val frozen get() = scrubbing || menuOpen || volumeOpen || focused.isNotEmpty()

    /** Speed belongs to one file: any other file starts at 1×. */
    fun speedOf(uri: String): Float = if (speedFor == uri) speed else 1f
    fun choose(uri: String, value: Float) { speedFor = uri; speed = value }

    fun enterFullscreen(orientation: Int) { if (!fullscreen) { orientationBefore = orientation; fullscreen = true } }
    fun exitFullscreen() { fullscreen = false }

    companion object {
        const val HIDE_AFTER = 3_000L
        val Saver: Saver<ViewerControls, Any> = listSaver(
            save = { listOf(it.shown, it.speed, it.speedFor ?: "", it.fullscreen, it.orientationBefore) },
            restore = { ViewerControls(it[0] as Boolean, it[1] as Float, (it[2] as String).ifEmpty { null }, it[3] as Boolean, it[4] as Int) })
    }
}

/** ±10 s on an LTR timeline: physical left goes back, right goes forward, in every language. */
internal object ViewerSeek {
    const val STEP = 10_000L
    /** Seek only once the player reports a seekable item with a known length; never guess one. */
    fun seekable(player: Player?): Boolean = player != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET && player.duration > 0
    fun target(player: Player?, forward: Boolean): Long? {
        if (!seekable(player)) return null
        val duration = player!!.duration
        return (player.currentPosition.coerceAtLeast(0) + if (forward) STEP else -STEP).coerceIn(0, duration)
    }
    fun forward(x: Float, width: Int): Boolean = x >= width / 2f
}

/** 0.5–2×, pitch kept: setPlaybackSpeed changes speed only. */
internal object ViewerSpeed {
    val choices = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun number(speed: Float, language: String) = NezzelFormat.number(speed.toDouble(), language, 2)
    fun same(a: Float, b: Float) = abs(a - b) < .01f
    /** True when the player took [speed]; a refused change leaves the old speed in place. */
    fun apply(player: Player, speed: Float): Boolean {
        if (same(player.playbackParameters.speed, speed)) return true
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return false
        return try { player.setPlaybackSpeed(speed); same(player.playbackParameters.speed, speed) } catch (_: RuntimeException) { false }
    }
}

/** Wide = the decoded picture, after pixel aspect, is wider than tall. Unknown size is never wide. */
internal fun isWide(size: VideoSize): Boolean = size.width > 0 && size.height > 0 && size.width * size.pixelWidthHeightRatio > size.height

/** Landscape for a wide video even with rotation lock on; exit puts back whatever the screen asked for before. */
internal object ViewerFullscreen {
    const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    fun enter(activity: Activity?, controls: ViewerControls) {
        controls.enterFullscreen(activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        activity?.requestedOrientation = LANDSCAPE
    }
    fun exit(activity: Activity?, controls: ViewerControls) {
        if (!controls.fullscreen) return
        controls.exitFullscreen()
        activity?.requestedOrientation = controls.orientationBefore
    }
}

/** The phone's media volume, the same one the volume keys move. Fixed-volume devices show no slider. */
internal class DeviceVolume(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    val max get() = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    val level get() = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    val adjustable get() = audio != null && !audio.isVolumeFixed && max > 0
    fun set(value: Int) = try { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0); true } catch (_: SecurityException) { false }
}

internal fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/** TalkBack touch exploration: controls never hide on their own while it is on. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** The viewer is always black: the light theme's primary is too dark there, so take its inverse. */
internal data class ViewerColors(val accent: Color, val onAccent: Color, val menu: Color, val onMenu: Color)

@Composable
internal fun viewerColors(): ViewerColors {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() > .5f) ViewerColors(scheme.inversePrimary, scheme.onPrimaryContainer, scheme.inverseSurface, scheme.inverseOnSurface)
    else ViewerColors(scheme.primary, scheme.onPrimary, scheme.surfaceContainerHigh, scheme.onSurface)
}

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White)).build()

internal val FullscreenIcon = icon("fullscreen", "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
internal val FullscreenExitIcon = icon("fullscreen_exit", "M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z")
