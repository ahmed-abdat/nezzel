package dev.ahmed.savlino.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One level down and back inside a tab, as Material's shared axis X: both pages move along the reading direction while
 * the old one fades out quickly and the new one fades in. [back] (null when [page] has no back of its own) leads to
 * [backTo]; on Android 14+ the gesture moves both pages back under the finger, crossfading so neither is ever gone, and
 * a cancelled gesture returns. Reduced motion swaps pages at once. Draw [SharedAxisContent] from the result, once per
 * slot that moves.
 */
@Composable
internal fun <T> rememberSharedAxis(page: T, backTo: T, back: (() -> Unit)?, label: String): SharedAxis<T> {
    val seek = remember { SeekableTransitionState(page) }
    val dragging = remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(page) {
        // A committed back gesture already chose its motion for this page; any other move goes along the axis.
        if (page != seek.targetState) dragging.value = false
        if (reduced) seek.snapTo(page) else seek.animateTo(page)
        dragging.value = false
    }
    SharedAxisBack(seek, dragging, page, backTo, back, reduced)
    val transition = rememberTransition(seek, label)
    return remember(transition) { SharedAxis(transition, dragging) }
}

/** The pages' transition, and whether a back gesture holds it under the finger. */
internal class SharedAxis<T>(val transition: Transition<T>, val dragging: State<Boolean>)

/** Its own scope: a tab being shown or hidden recomposes only this, and only while a page has its own back. */
@Composable
private fun <T> SharedAxisBack(
    seek: SeekableTransitionState<T>, dragging: MutableState<Boolean>, page: T, backTo: T, back: (() -> Unit)?, reduced: Boolean,
) {
    val scope = rememberCoroutineScope()
    val shown = LocalTabShown.current
    val backNow = rememberUpdatedState(back)
    val pageNow = rememberUpdatedState(page)
    // Hidden tabs never take back, whichever dispatcher delivers it.
    val enabled by remember { derivedStateOf { backNow.value != null && shown.value } }
    PredictiveBackHandler(enabled) { events ->
        try {
            if (!reduced) dragging.value = true
            events.collect { if (!reduced) seek.seekTo(it.progress, backTo) }
            backNow.value?.invoke()
        } catch (cancelled: CancellationException) {
            // Back to where the finger started, as quickly as it got there.
            scope.launch {
                val from = seek.fraction
                val nanos = (from * Motion.AXIS * 1_000_000L).toLong()
                if (nanos > 0) {
                    val start = withFrameNanos { it }
                    while (true) {
                        val left = from * (1f - (withFrameNanos { it } - start).toFloat() / nanos)
                        if (left <= 0f) break
                        seek.seekTo(left)
                    }
                }
                seek.snapTo(pageNow.value)
                dragging.value = false
            }
            throw cancelled
        }
    }
}

/**
 * Deeper pages enter from the end and leave toward it; RTL mirrors through the layout direction. Under a back gesture
 * the motion follows the finger linearly: the leaving page shrinks a little as it slides and both pages crossfade over
 * the first half of the swipe, so a finger resting anywhere still sees a page.
 */
@Composable
internal fun <T> SharedAxis<T>.SharedAxisContent(
    depth: (T) -> Int, modifier: Modifier = Modifier, content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val shift = with(LocalDensity.current) { Motion.AxisShift.roundToPx() }
    val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    transition.AnimatedContent(modifier, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None using SizeTransform { _, _ -> snap() }
        else if (dragging.value) {
            val along = if (depth(targetState) >= depth(initialState)) end * shift else -end * shift
            (slideInHorizontally(tween(Motion.AXIS, easing = LinearEasing)) { along } +
                fadeIn(tween(Motion.AXIS / 2, easing = LinearEasing))) togetherWith
                (slideOutHorizontally(tween(Motion.AXIS, easing = LinearEasing)) { -along } +
                    scaleOut(tween(Motion.AXIS, easing = LinearEasing), targetScale = .95f) +
                    fadeOut(tween(Motion.AXIS / 2, easing = LinearEasing))) using
                SizeTransform(clip = false) { _, _ -> tween(Motion.AXIS, easing = LinearEasing) }
        } else {
            val along = if (depth(targetState) >= depth(initialState)) end * shift else -end * shift
            (slideInHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { along } +
                fadeIn(tween(Motion.AXIS - Motion.FADE_OUT, Motion.FADE_OUT, Motion.EaseOut))) togetherWith
                (slideOutHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { -along } +
                    fadeOut(tween(Motion.FADE_OUT, easing = Motion.EaseOut))) using
                // A taller or shorter page resizes with the same motion and ends with it, instead of springing on after.
                SizeTransform(clip = false) { _, _ -> tween(Motion.AXIS, easing = Motion.EaseOut) }
        }
    }, content = content)
}

/** A leaving page keeps showing what it had instead of morphing into the next page's data while it fades. */
@Composable
internal fun <T> AnimatedVisibilityScope.heldWhileLeaving(value: T): T {
    val held = remember { Held(value) }
    if (transition.targetState == EnterExitState.Visible) held.value = value
    return held.value
}

private class Held<T>(var value: T)
