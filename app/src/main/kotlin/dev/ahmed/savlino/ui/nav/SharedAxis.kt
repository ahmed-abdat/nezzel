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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One level down and back inside a tab, as Material's shared axis X: both pages move along the reading direction while
 * the old one fades out quickly and the new one fades in. [back] (null when [page] has no back of its own) leads to
 * [backTo]; on Android 14+ the gesture lifts the page off the one below it under the finger, and a cancelled gesture
 * returns. Reduced motion swaps pages at once. Draw [SharedAxisContent] from the result, once per
 * slot that moves.
 */
@Composable
internal fun <T> rememberSharedAxis(page: T, backTo: T, back: (() -> Unit)?, label: String): SharedAxis<T> {
    val seek = remember { SeekableTransitionState(page) }
    val dragging = remember { mutableStateOf(false) }
    val edge = remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(page) {
        // A committed back gesture already chose its motion for this page; any other move goes along the axis.
        if (page != seek.targetState) dragging.value = false
        if (reduced) seek.snapTo(page) else seek.animateTo(page)
        dragging.value = false
    }
    SharedAxisBack(seek, dragging, edge, page, backTo, back, reduced)
    val transition = rememberTransition(seek, label)
    return remember(transition) { SharedAxis(transition, dragging, edge) }
}

/** The pages' transition, whether a back gesture holds it under the finger, and the screen edge that gesture came from. */
internal class SharedAxis<T>(val transition: Transition<T>, val dragging: State<Boolean>, val edge: IntState)

/** Where a slot meets the slot drawn by the other [SharedAxisContent] call, so both lift off as one page. */
internal enum class Joined { Nowhere, Bottom, Top }

/** The finger holds this share of a back gesture's motion; the rest plays only once it lets go. */
private const val HELD = .5f

/** Its own scope: a tab being shown or hidden recomposes only this, and only while a page has its own back. */
@Composable
private fun <T> SharedAxisBack(
    seek: SeekableTransitionState<T>, dragging: MutableState<Boolean>, edge: MutableIntState, page: T, backTo: T,
    back: (() -> Unit)?, reduced: Boolean,
) {
    val scope = rememberCoroutineScope()
    val shown = LocalTabShown.current
    val backNow = rememberUpdatedState(back)
    val pageNow = rememberUpdatedState(page)
    // Hidden tabs never take back, whichever dispatcher delivers it.
    val enabled by remember { derivedStateOf { backNow.value != null && shown.value } }
    PredictiveBackHandler(enabled) { events ->
        try {
            // A back without a gesture (button, older Android) sends no progress and keeps the axis motion.
            events.collect {
                if (reduced) return@collect
                edge.intValue = it.swipeEdge
                dragging.value = true
                seek.seekTo(it.progress * HELD, backTo)
            }
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
 * the leaving page stays opaque above the page below and follows the finger linearly, as Material's predictive back:
 * it shrinks toward .9, rounds its corners and moves with the finger until 8dp short of the far edge, while the page
 * below waits behind it, dimmed a little. Letting go fades it out; one page never shows through the other mid-swipe.
 * [joined] names the edge this slot shares with another slot of the same pages: it stays square and pinned there.
 */
@Composable
internal fun <T> SharedAxis<T>.SharedAxisContent(
    depth: (T) -> Int, modifier: Modifier = Modifier, joined: Joined = Joined.Nowhere,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val shift = with(LocalDensity.current) { Motion.AxisShift.roundToPx() }
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    transition.AnimatedContent(modifier, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None using SizeTransform { _, _ -> snap() }
        else if (dragging.value) {
            val held = (Motion.AXIS * HELD).toInt()
            val withFinger = if (edge.intValue == BackEventCompat.EDGE_RIGHT) -1 else 1
            (EnterTransition.None togetherWith
                (slideOutHorizontally(tween(held, easing = LinearEasing)) { withFinger * (it / 20 - margin).coerceAtLeast(0) } +
                    scaleOut(tween(held, easing = LinearEasing), LIFTED, joined.pivot) +
                    fadeOut(tween(Motion.AXIS - held, held, LinearEasing))) using
                SizeTransform(clip = false) { _, _ -> tween(Motion.AXIS, easing = LinearEasing) })
                // The page below waits behind the one lifting off it.
                .apply { targetContentZIndex = -1f }
        } else {
            val along = if (depth(targetState) >= depth(initialState)) end * shift else -end * shift
            (slideInHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { along } +
                fadeIn(tween(Motion.AXIS - Motion.FADE_OUT, Motion.FADE_OUT, Motion.EaseOut))) togetherWith
                (slideOutHorizontally(tween(Motion.AXIS, easing = Motion.EaseOut)) { -along } +
                    fadeOut(tween(Motion.FADE_OUT, easing = Motion.EaseOut))) using
                // A taller or shorter page resizes with the same motion and ends with it, instead of springing on after.
                SizeTransform(clip = false) { _, _ -> tween(Motion.AXIS, easing = Motion.EaseOut) }
        }
    }) { page -> Box(lifted(this, joined), propagateMinConstraints = true) { content(page) } }
}

private const val LIFTED = .9f
private const val DIM = .12f

private val Joined.pivot get() = when (this) {
    Joined.Nowhere -> TransformOrigin.Center
    Joined.Bottom -> TransformOrigin(.5f, 1f)
    Joined.Top -> TransformOrigin(.5f, 0f)
}

/** Under a back gesture: the leaving page is an opaque card whose corners round as it lifts; the page below is dimmed. */
@Composable
private fun <T> SharedAxis<T>.lifted(scope: AnimatedContentScope, joined: Joined): Modifier {
    // -1 below, 0 resting, 1 lifted: read only while drawing, so a moving finger never recomposes the page.
    val lift = scope.transition.animateFloat({
        if (dragging.value) tween((Motion.AXIS * HELD).toInt(), easing = LinearEasing) else snap()
    }, "lift") { when (it) { EnterExitState.PreEnter -> -1f; EnterExitState.Visible -> 0f; EnterExitState.PostExit -> 1f } }
    if (!dragging.value) return Modifier
    if (scope.transition.targetState != EnterExitState.PostExit) {
        val scrim = MaterialTheme.colorScheme.scrim
        return Modifier.drawWithContent { drawContent(); if (lift.value < 0f) drawRect(scrim, alpha = -lift.value * DIM) }
    }
    val corner = MaterialTheme.shapes.extraLarge.topStart
    return Modifier.graphicsLayer {
        val r = corner.toPx(size, this) * lift.value.coerceIn(0f, 1f)
        val top = if (joined == Joined.Top) 0f else r
        val bottom = if (joined == Joined.Bottom) 0f else r
        shape = RoundedCornerShape(top, top, bottom, bottom)
        clip = true
    }.background(MaterialTheme.colorScheme.background)
}

/** A leaving page keeps showing what it had instead of morphing into the next page's data while it fades. */
@Composable
internal fun <T> AnimatedVisibilityScope.heldWhileLeaving(value: T): T {
    val held = remember { Held(value) }
    if (transition.targetState == EnterExitState.Visible) held.value = value
    return held.value
}

private class Held<T>(var value: T)
