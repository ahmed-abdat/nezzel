package dev.ahmed.savlino.viewer

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics

/** One screen's open viewer: its id survives rotation, and its locals travel with it as they would into a Dialog. */
internal class ViewerRequest(val id: String) {
    var locals by mutableStateOf<CompositionLocalContext?>(null)
    var content by mutableStateOf<@Composable () -> Unit>({})
}

/**
 * The app-level viewer slot. A screen's [MediaViewer] hands its page here, and the host draws it above the whole app in
 * the activity window, so the tabs underneath can take part in the player's motion. One viewer at a time.
 */
@Stable
class ViewerHostState {
    internal var request by mutableStateOf<ViewerRequest?>(null); private set
    val open get() = request != null
    internal fun show(next: ViewerRequest) { request = next }
    internal fun dismiss(old: ViewerRequest) { if (request === old) request = null }
}

val LocalViewerHost = staticCompositionLocalOf<ViewerHostState?> { null }

/** Wraps the app: while a viewer is open the app below takes no touches, focus or TalkBack, as under a Dialog. */
@Composable
fun ViewerHost(state: ViewerHostState = remember { ViewerHostState() }, app: @Composable () -> Unit) {
    val request = state.request
    val back = LocalOnBackPressedDispatcherOwner.current
    val registry = LocalSaveableStateRegistry.current
    val focus = LocalFocusManager.current
    LaunchedEffect(request != null) { if (request != null) focus.clearFocus(force = true) }
    CompositionLocalProvider(LocalViewerHost provides state) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (request != null) Modifier.clearAndSetSemantics {} else Modifier)) { app() }
            request?.let { open ->
                val locals = open.locals ?: return@let
                key(open.id) {
                    // The screen's locals, then the app's own Back and saved state: a hidden tab's detached Back or a
                    // tab's state holder must not own the viewer.
                    CompositionLocalProvider(locals) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            if (back != null) CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides back) { open.content() }
                            else open.content()
                        }
                    }
                }
            }
        }
    }
}
