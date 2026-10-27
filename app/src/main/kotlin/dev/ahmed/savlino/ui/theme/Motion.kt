package dev.ahmed.savlino.ui.theme

import androidx.compose.ui.graphics.Color

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One motion vocabulary: exponential ease-out for state, linear only for real progress. */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val TabFadeOut: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)
    val TabFadeIn: FiniteAnimationSpec<Float> = DelayedSpec(spring(dampingRatio = 1f, stiffness = 1600f), 60)
    val TabScale: FiniteAnimationSpec<Float> = spring(dampingRatio = .9f, stiffness = 700f)
    /** Spatial: the tab bar's raised pill settles in about 300 ms with a small overshoot. */
    val TabIndicator: FiniteAnimationSpec<Float> = spring(dampingRatio = .7f, stiffness = 800f)
    /** Effects: icon fill and label colour, no overshoot. */
    val TabIconFill: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)
    val TabLabelColor: FiniteAnimationSpec<Color> = spring(dampingRatio = 1f, stiffness = 1600f)
    /** Press feedback on the primary action. */
    val Press: FiniteAnimationSpec<Float> = spring(dampingRatio = .6f, stiffness = 1200f)
    const val FADE_OUT = 90
    const val FADE_IN = 180
    const val STATE = 200
    const val TAB = 250
    /** Shared axis X between a page and the one below it: both move [AxisShift], the old fades out over [FADE_OUT]. */
    const val AXIS = 300
    val AxisShift = 30.dp
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

/** Springs have no delay parameter. Preserve their physics and initial value during the fade-through gap. */
internal class DelayedSpec<T>(private val spec: FiniteAnimationSpec<T>, delayMillis: Int) : FiniteAnimationSpec<T> {
    private val delayNanos = delayMillis * 1_000_000L
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> {
        val delegate = spec.vectorize(converter)
        return object : VectorizedFiniteAnimationSpec<V> {
            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
                delayNanos + delegate.getDurationNanos(initialValue, targetValue, initialVelocity)
            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                if (playTimeNanos < delayNanos) initialValue
                else delegate.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)
            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                delegate.getVelocityFromNanos((playTimeNanos - delayNanos).coerceAtLeast(0), initialValue, targetValue, initialVelocity)
        }
    }
}
