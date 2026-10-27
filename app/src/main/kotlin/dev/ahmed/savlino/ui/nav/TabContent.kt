package dev.ahmed.savlino.ui.nav

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
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

/**
 * Peer destinations keep independent saveable state, including lazy and ordinary scroll positions. A visited tab stays
 * composed while hidden (not measured, not placed, no semantics, no back handling), so switching back to it never
 * composes a whole screen in one frame. Only draw properties animate.
 */
@Composable
fun TabContent(
    destination: String,
    reselections: TabReselections = remember { TabReselections() },
    stateHolder: SaveableStateHolder = rememberSaveableStateHolder(),
    content: @Composable (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val first = remember { destination }
    // Not snapshot state: a new destination recomposes this scope anyway.
    val visited = remember { linkedSetOf<String>() }
    visited += destination
    val focus = LocalFocusManager.current
    var switched by remember { mutableStateOf(false) }
    LaunchedEffect(destination) { if (switched) focus.clearFocus() else switched = true }
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detachedBack = remember(lifecycle) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle = lifecycle
        }
    }
    Box(Modifier.fillMaxSize()) {
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
                // Hidden tabs keep their composition but skip measure and placement; the flag flips only twice per switch.
                .layout { measurable, constraints ->
                    if (!shown && (reduced || !placed.value)) layout(0, 0) {}
                    else measurable.measure(constraints).let { layout(it.width, it.height) { it.place(0, 0) } }
                }
                .graphicsLayer { this.alpha = if (reduced) 1f else alpha.value; scaleX = scale.value; scaleY = scale.value }
                .then(if (shown) Modifier else Modifier.clearAndSetSemantics {})) {
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

