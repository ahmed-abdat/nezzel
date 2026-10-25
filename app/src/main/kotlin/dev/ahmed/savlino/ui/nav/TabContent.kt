package dev.ahmed.savlino.ui.nav

import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
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

/** Peer destinations keep independent saveable state, including lazy and ordinary scroll positions. */
@Composable
fun TabContent(
    destination: String,
    reselections: TabReselections = remember { TabReselections() },
    stateHolder: SaveableStateHolder = rememberSaveableStateHolder(),
    content: @Composable (String) -> Unit,
) {
    if (LocalReducedMotion.current) {
        // No transition object: disposal and restoration happen in the destination's recomposition.
        key(destination) {
            stateHolder.SaveableStateProvider(destination) {
                CompositionLocalProvider(LocalTabReselections provides reselections.events(destination), LocalTabActive provides AlwaysActive) {
                    content(destination)
                }
            }
        }
        return
    }
    val tabTransition = updateTransition(destination, label = "destination")
    tabTransition.AnimatedContent(contentAlignment = Alignment.TopStart, transitionSpec = {
        ((fadeIn(Motion.TabFadeIn) + scaleIn(Motion.TabScale, initialScale = .96f)) togetherWith fadeOut(Motion.TabFadeOut))
            .using(null) // No animated size: screens fill the same viewport, only draw properties move.
    }) { route ->
        val active = remember(route, tabTransition) {
            derivedStateOf { tabTransition.currentState == route && tabTransition.targetState == route && !tabTransition.isRunning }
        }
        stateHolder.SaveableStateProvider(route) {
            CompositionLocalProvider(LocalTabReselections provides reselections.events(route), LocalTabActive provides active) {
                content(route)
            }
        }
    }
}
