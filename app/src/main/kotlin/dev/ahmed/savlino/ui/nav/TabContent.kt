package dev.ahmed.savlino.ui.nav

import android.os.Looper
import androidx.activity.FullyDrawnReporter
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow

/** Events, rather than a remembered counter: returning to a tab must never replay an old scroll request. */
@Stable
class TabReselections {
    private val events = mutableMapOf<String, MutableSharedFlow<Unit>>()
    internal fun events(destination: String): Flow<Unit> = events.getOrPut(destination) { MutableSharedFlow(extraBufferCapacity = 1) }
    fun reselect(destination: String) { events[destination]?.tryEmit(Unit) }
}

private val LocalTabReselections = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }
private val AlwaysActive: State<Boolean> = mutableStateOf(true)
/** Read only inside effects: animation state never invalidates the scaffold or navigation. */
val LocalTabActive = staticCompositionLocalOf { AlwaysActive }

@Composable
fun ScrollOnTabReselect(scrollToTop: suspend () -> Unit) {
    val events = LocalTabReselections.current
    val action by rememberUpdatedState(scrollToTop)
    LaunchedEffect(events) { events.collectLatest { action() } }
}

/**
 * An endless animation from [from] to [to] that runs only while [enabled] and the tab is shown and settled; [rest]
 * otherwise, so a kept-but-hidden tab asks for no frames. Read it while drawing: the tab settling or hiding never
 * recomposes the caller.
 */
@Composable
fun rememberTabLoop(from: Float, to: Float, spec: DurationBasedAnimationSpec<Float>, repeatMode: RepeatMode = RepeatMode.Restart,
                    rest: Float = from, enabled: Boolean = true): State<Float> {
    val active = LocalTabActive.current
    val value = remember { Animatable(rest) }
    val run = rememberUpdatedState(enabled)
    LaunchedEffect(active, from, to, spec, repeatMode, rest) {
        snapshotFlow { active.value && run.value }.collectLatest { running ->
            if (!running) value.snapTo(rest)
            else { value.snapTo(from); value.animateTo(to, infiniteRepeatable(spec, repeatMode)) }
        }
    }
    return value.asState()
}

/** Holds the current image when inactive, cancels pending IO, and starts automatic work only after the tab settles. */
@Composable
fun <T> rememberTabResource(initial: T, vararg keys: Any?, load: suspend () -> T): State<T> {
    val active = LocalTabActive.current
    val latestLoad by rememberUpdatedState(load)
    return produceState(initial, active, *keys) {
        var loadedForKeys = false
        snapshotFlow { active.value }.collectLatest { enabled ->
            if (enabled && !loadedForKeys) {
                value = latestLoad()
                loadedForKeys = value != null
            }
        }
    }
}

/** Off-window tabs refuse keyboard focus and leave the semantics tree. */
private val HiddenTab = Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup().clearAndSetSemantics {}

/** A pre-warm step needs this long without a touch or switch (so 400-800 ms after the last one), then an idle main thread. */
private const val PREWARM_QUIET_MS = 400L

/** The tab a pre-warm step is composing, withdrawn by a touch or a switch until it has composed. */
private class PreWarm(private val visited: Set<String>) {
    val pending = mutableStateOf<String?>(null)
    var interruptions = 0
    fun interrupt() {
        interruptions++
        pending.value?.let { if (it !in visited) pending.value = null }
    }
}

private suspend fun FullyDrawnReporter.awaitReported() {
    if (isFullyDrawnReported) return
    suspendCancellableCoroutine { done ->
        val listener = { done.resume(Unit) }
        addOnReportDrawnListener(listener)
        done.invokeOnCancellation { removeOnReportDrawnListener(listener) }
    }
}

/** Nothing is due on the main thread: no input, frame or message waits behind a pre-warm step. */
private suspend fun awaitMainIdle() { while (!Looper.getMainLooper().queue.isIdle) delay(16) }

/**
 * Peer destinations keep independent saveable state, including lazy and ordinary scroll positions. A visited tab stays
 * composed and placed while hidden (off-window and transparent, no touch, focus, semantics or back handling), so switching
 * back to it never composes or re-records a whole screen in one frame. Only draw properties animate.
 *
 * Once the app reported fully drawn, [prewarm] tabs not visited yet are composed the same way, hidden and inactive (so
 * none of their loads start), one per idle window; a touch or a switch puts the next one off.
 */
@Composable
fun TabContent(
    destination: String,
    reselections: TabReselections = remember { TabReselections() },
    stateHolder: SaveableStateHolder = rememberSaveableStateHolder(),
    prewarm: List<String> = emptyList(),
    content: @Composable (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val first = remember { destination }
    // Not snapshot state: a new destination recomposes this scope anyway.
    val visited = remember { linkedSetOf<String>() }
    val warm = remember { PreWarm(visited) }
    // A switch landing in the same frame as a pre-warm step wins; the step retries in the next idle window.
    val lastDestination = remember { arrayOf(destination) }
    if (destination == lastDestination[0]) warm.pending.value?.let { visited += it }
    lastDestination[0] = destination
    visited += destination
    val focus = LocalFocusManager.current
    var switched by remember { mutableStateOf(false) }
    LaunchedEffect(destination) {
        warm.interrupt()
        if (switched) focus.clearFocus() else switched = true
    }
    val fullyDrawn = LocalFullyDrawnReporterOwner.current?.fullyDrawnReporter
    LaunchedEffect(fullyDrawn, prewarm) {
        fullyDrawn?.awaitReported() ?: return@LaunchedEffect
        for (route in prewarm) while (route !in visited) {
            val seen = warm.interruptions
            delay(PREWARM_QUIET_MS)
            awaitMainIdle()
            if (seen != warm.interruptions || route in visited) continue
            warm.pending.value = route
            // The next frame composes it; a touch before then withdraws it.
            withFrameNanos {}
            withFrameNanos {}
            if (route !in visited) warm.pending.value = null
        }
    }
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detachedBack = remember(lifecycle) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle = lifecycle
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(warm) {
        // Watches without consuming: every touch on the shown tab puts pre-warming off.
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); warm.interrupt() } }
    }) {
        for (route in visited) key(route) {
            val shown = route == destination
            val alpha = remember { Animatable(if (route == first) 1f else 0f) }
            val scale = remember { Animatable(1f) }
            LaunchedEffect(shown, reduced) {
                when {
                    // Reduced motion never reads these: the tab is placed or not in the same frame.
                    reduced -> { alpha.snapTo(if (shown) 1f else 0f); scale.snapTo(1f) }
                    shown -> {
                        if (alpha.value == 0f) scale.snapTo(.96f)
                        launch { scale.animateTo(1f, Motion.TabScale) }
                        alpha.animateTo(1f, Motion.TabFadeIn)
                    }
                    else -> alpha.animateTo(0f, Motion.TabFadeOut)
                }
            }
            val placed = remember { derivedStateOf { alpha.value > 0f } }
            // One State per tab for its whole life: effects keyed on it never restart on a switch.
            val shownNow = rememberUpdatedState(shown)
            val reducedNow = rememberUpdatedState(reduced)
            val active = remember {
                derivedStateOf {
                    shownNow.value && (reducedNow.value || (alpha.value == 1f && scale.value == 1f && !alpha.isRunning && !scale.isRunning))
                }
            }
            Box(Modifier.zIndex(if (shown) 1f else 0f)
                // Hidden tabs stay placed, just outside the window: Compose drops every layer of an unplaced subtree, so
                // showing it again would re-record the whole screen in one frame. Moving it back only moves its layer.
                // Out there no touch reaches it; the states are read while placing, so a switch never re-measures.
                .layout { measurable, constraints ->
                    val tab = measurable.measure(constraints)
                    layout(tab.width, tab.height) {
                        val onScreen = shownNow.value || (!reducedNow.value && placed.value)
                        tab.place(if (onScreen) 0 else -2 * tab.width, 0)
                    }
                }
                .graphicsLayer {
                    this.alpha = if (reducedNow.value) (if (shownNow.value) 1f else 0f) else alpha.value
                    scaleX = scale.value; scaleY = scale.value
                }
                // Nor does keyboard focus, and TalkBack never sees it.
                .then(if (shown) Modifier else HiddenTab)) {
                stateHolder.SaveableStateProvider(route) {
                    CompositionLocalProvider(LocalTabReselections provides reselections.events(route), LocalTabActive provides active,
                        LocalOnBackPressedDispatcherOwner provides if (shown && backOwner != null) backOwner else detachedBack) {
                        content(route)
                    }
                }
            }
        }
    }
}

