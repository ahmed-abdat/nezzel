package dev.ahmed.savlino.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
 * [backTo]; on Android 14+ the gesture plays the same motion in reverse under the finger and a cancelled gesture returns.
 * Reduced motion swaps pages at once. Draw [SharedAxisContent] from the returned transition, once per slot that moves.
 */
@Composable
internal fun <T> rememberSharedAxis(page: T, backTo: T, back: (() -> Unit)?, label: String): Transition<T> {
    val seek = remember { SeekableTransitionState(page) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(page) { if (reduced) seek.snapTo(page) else seek.animateTo(page) }
    SharedAxisBack(seek, page, backTo, back, reduced)
    return rememberTransition(seek, label)
}

/** Its own scope: a tab being shown or hidden recomposes only this, and only while a page has its own back. */
@Composable
private fun <T> SharedAxisBack(seek: SeekableTransitionState<T>, page: T, backTo: T, back: (() -> Unit)?, reduced: Boolean) {
    val scope = rememberCoroutineScope()
    val shown = LocalTabShown.current
    val backNow = rememberUpdatedState(back)
    val pageNow = rememberUpdatedState(page)
    // Hidden tabs never take back, whichever dispatcher delivers it.
    val enabled by remember { derivedStateOf { backNow.value != null && shown.value } }
    PredictiveBackHandler(enabled) { events ->
        try {
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
            }
            throw cancelled
        }
    }
}

/** Deeper pages enter from the end and leave toward it; RTL mirrors through the layout direction. */
@Composable
internal fun <T> Transition<T>.SharedAxisContent(
    depth: (T) -> Int, modifier: Modifier = Modifier, content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val shift = with(LocalDensity.current) { Motion.AxisShift.roundToPx() }
    val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    AnimatedContent(modifier, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else {
            val along = if (depth(targetState) >= depth(initialState)) end * shift else -end * shift
            (slideInHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { along } +
                fadeIn(tween(Motion.AXIS - Motion.FADE_OUT, Motion.FADE_OUT, Motion.EaseOut))) togetherWith
                (slideOutHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { -along } +
                    fadeOut(tween(Motion.FADE_OUT, easing = Motion.EaseOut)))
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
