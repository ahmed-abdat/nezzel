package dev.ahmed.savlino.viewer

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics

/** One screen's open viewer: its id survives rotation, and its locals travel with it as they would into a Dialog. */
internal class ViewerRequest(val id: String) {
    var locals by mutableStateOf<CompositionLocalContext?>(null)
    var content by mutableStateOf<@Composable () -> Unit>({})
    /** The screen's own close: a viewer opened elsewhere supersedes this one and closes it, so no screen thinks it is open. */
    var onClose: () -> Unit = {}
}

/**
 * The app-level viewer slot. A screen's [MediaViewer] hands its page here, and the host draws it above the whole app in
 * the activity window, so the tabs underneath can take part in the player's motion. One viewer at a time.
 */
@Stable
class ViewerHostState {
    internal var request by mutableStateOf<ViewerRequest?>(null); private set
    val open get() = request != null
    internal fun show(next: ViewerRequest) {
        // Same id = the same screen's viewer after recreation; another screen's viewer replaces it, closed.
        request?.takeIf { it.id != next.id }?.onClose?.invoke()
        request = next
    }
    internal fun dismiss(old: ViewerRequest) { if (request === old) request = null }
}

val LocalViewerHost = staticCompositionLocalOf<ViewerHostState?> { null }

/** Wraps the app: while a viewer is open the app below takes no touches, focus, keys or TalkBack, as under a Dialog. */
@Composable
fun ViewerHost(state: ViewerHostState = remember { ViewerHostState() }, app: @Composable () -> Unit) {
    val request = state.request
    val back = LocalOnBackPressedDispatcherOwner.current
    val registry = LocalSaveableStateRegistry.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val covered = request != null
    LaunchedEffect(covered) { if (covered) { focus.clearFocus(force = true); keyboard?.hide() } }
    CompositionLocalProvider(LocalViewerHost provides state) {
        Box(Modifier.fillMaxSize()) {
            // A keyboard or D-pad can't move into the covered app, and focus never leaves the viewer for it.
            Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { if (covered) onEnter = { cancelFocusChange() } }.focusGroup()) { app() }
            request?.let { open ->
                val locals = open.locals ?: return@let
                key(open.id) { Box(Modifier.fillMaxSize().focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
                    // The screen's locals, then the app's own Back and saved state: a hidden tab's detached Back or a
                    // tab's state holder must not own the viewer.
                    CompositionLocalProvider(locals) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            if (back != null) CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides back) { open.content() }
                            else open.content()
                        }
                    }
                } }
            }
        }
    }
}
