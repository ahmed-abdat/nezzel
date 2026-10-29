package dev.ahmed.savlino.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * What a video or photo page lends the host for a pull down: whether it can be pulled right now, whether it is in
 * fullscreen (the pull then only leaves fullscreen), and its own ways out. Music never registers one: it shrinks into the bar.
 */
internal class PageExit(val can: () -> Boolean, val fullscreen: () -> Boolean, val leaveFullscreen: () -> Unit, val close: () -> Unit)

/**
 * The page pulled down, as drawn: it follows the finger, shrinks to [MIN_SCALE] by half the screen, its black lets the app
 * through and its chrome goes first. Past [CLOSE_AT] of the height, or flung down, it leaves; anything less springs back.
 * Fullscreen: it moves half as far over black, and letting go past the same point only leaves fullscreen.
 */
internal object PagePull {
    const val CLOSE_AT = .2f
    const val MIN_SCALE = .85f
    /** Predictive back draws the page this far down at the gesture's end. */
    const val BACK = .2f
    /** Fullscreen follows at half the finger's speed: a pull there is a smaller step. */
    const val FULLSCREEN_FOLLOW = .5f
    /** A downward release this fast leaves whatever the distance; an upward one this fast never does. */
    val FLING = 1_000.dp
    const val OUT_MILLIS = 200

    fun fraction(pull: Float, height: Float) = if (height <= 0f) 0f else (pull / height).coerceIn(0f, 1f)
    fun scale(fraction: Float) = 1f - (1f - MIN_SCALE) * (fraction * 2f).coerceAtMost(1f)
    /** The page's own black, gone by about a third of the way. */
    fun backdrop(fraction: Float) = 1f - (fraction / .35f).coerceAtMost(1f)
    /** Top bar, centre buttons and seek bar: gone almost at once, so only the picture travels. */
    fun chrome(fraction: Float) = 1f - (fraction / .1f).coerceAtMost(1f)
    /** Whole page: fades only over the second half, which a release past the line runs through on its way out. */
    fun alpha(fraction: Float) = if (fraction <= .5f) 1f else 1f - (fraction - .5f) / .5f
    fun letsGo(distance: Float, velocity: Float, height: Float, fling: Float) =
        velocity >= fling || (height > 0f && distance >= height * CLOSE_AT && velocity > -fling)
}

/**
 * The page's one pull-down recognizer, on the host's page layer. It only watches until the finger has moved a touch slop,
 * then takes the drag only when that move is downward and more vertical than sideways. Everything inside the page sees each
 * move first, so whatever claimed it there keeps it: the pager's sideways swipe, a zoomed picture's pan, a pinch's second
 * finger, a long press (which holds its own finger until it lifts). Taps and double taps never move that far.
 * The page decides when it can't be pulled at all ([PageExit.can]: zoomed, a menu or the volume strip open), and the host
 * when a slider or scroll holds the finger ([ViewerHostState.held]) or music owns the page.
 */
internal fun Modifier.pullsDown(host: ViewerHostState) = pointerInput(host) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!host.canPull()) return@awaitEachGesture
        var moved = Offset.Zero
        // From the first touch: a quick flick may be only a few moves long, most of them spent on the slop.
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1 || host.held) return@awaitEachGesture
            velocity.addPosition(change.uptimeMillis, change.position)
            moved += change.positionChange()
            if (abs(moved.x) > slop || moved.y < -slop) return@awaitEachGesture
            if (moved.y > slop && moved.y > abs(moved.x)) { change.consume(); break }
        }
        if (!host.startPull()) return@awaitEachGesture
        var distance = 0f
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) break
                distance = (distance + change.positionChange().y).coerceAtLeast(0f)
                change.consume()
                host.dragPull(distance)
            }
            released = true
            host.releasePull(distance, velocity.calculateVelocity().y)
        } finally { if (!released) host.settlePull(0f) }
    }
}

/** Chrome on the page: fades out the moment a pull starts, fullscreen too, so only the picture travels down. */
internal fun Modifier.fadesWhilePulled(host: ViewerHostState?) =
    if (host == null) this else graphicsLayer { alpha = PagePull.chrome(PagePull.fraction(host.pull, host.page.height)) }
