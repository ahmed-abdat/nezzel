package dev.ahmed.savlino.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** One motion vocabulary: exponential ease-out for state, linear only for real progress. */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val FADE_OUT = 90
    const val FADE_IN = 180
    const val STATE = 200
    const val TAB = 250
    /** Equals the coordinator's progress tick, so the ring trails the journal and never runs ahead. */
    const val PROGRESS = 250
    const val SAVED_DWELL = 1600L
}

/** "Remove animations" (animator scale 0) means remove: every spec snaps. */
val LocalReducedMotion = staticCompositionLocalOf { false }

fun <T> motionSpec(reduced: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduced) snap() else spec

fun <T> easeOut(reduced: Boolean, millis: Int = Motion.STATE, delay: Int = 0): FiniteAnimationSpec<T> =
    motionSpec(reduced, tween(millis, delay, Motion.EaseOut))

/** Follows the system setting live, so toggling it in Developer options applies without a restart. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
