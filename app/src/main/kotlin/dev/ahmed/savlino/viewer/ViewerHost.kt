package dev.ahmed.savlino.viewer

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * Music can shrink into a bar above the tabs ([PlayerSize.Bar]) and keeps playing there; one drag offset drives it all.
 */
@Stable
class ViewerHostState internal constructor(collapsed: Boolean) {
    constructor() : this(false)
    internal var request by mutableStateOf<ViewerRequest?>(null); private set
    /** A closed page still fading out. */
    internal var leaving by mutableStateOf<ViewerRequest?>(null)
    val open get() = request != null
    // A 1 px stand-in until the bar's slot is measured, so the state always has both anchors to settle on.
    internal val size = AnchoredDraggableState(if (collapsed) PlayerSize.Bar else PlayerSize.Full,
        DraggableAnchors { PlayerSize.Full at 0f; PlayerSize.Bar at 1f })
    /** The music page that can shrink into the bar right now, registered by the viewer once its player is there. */
    internal var mini by mutableStateOf<MiniSession?>(null)
    private var slotRect by mutableStateOf<MiniSlot?>(null)
    /** Where the bar sits, in window pixels, reported by the slot above the tabs; null while no tabs are shown. */
    internal var slot: MiniSlot?
        get() = slotRect
        set(value) {
            slotRect = value
            if (value != null && value.rect.top > 0f && value.rect.top != travel)
                size.updateAnchors(DraggableAnchors { PlayerSize.Full at 0f; PlayerSize.Bar at value.rect.top }, size.targetValue)
        }
    /** A file the screen was asked to open again, maybe while its viewer sits in the bar; that viewer takes it. */
    internal var tapped by mutableStateOf<ViewerTap?>(null)
    /** A finger is down on a slider or scroll inside the page: the page's own drag waits. */
    internal var held by mutableStateOf(false)
    internal var scope: CoroutineScope? = null
    internal var reduced = false
    // Set the moment a collapse starts: a screen disposing in the same frame must not take the music with it.
    private var wantBar = collapsed

    /** Pixels from full screen to the bar. */
    internal val travel get() = size.anchors.positionOf(PlayerSize.Bar).takeUnless { it.isNaN() } ?: 0f
    /** 0 = full screen, 1 = the bar. Read it only in layout, layer and draw lambdas. */
    internal val progress: Float get() {
        val offset = size.offset
        val bar = travel
        return if (offset.isNaN() || bar <= 0f) (if (size.currentValue == PlayerSize.Bar) 1f else 0f) else (offset / bar).coerceIn(0f, 1f)
    }
    internal val moving by derivedStateOf { progress > 0f }
    /** Past halfway: the app's own bar icons are back. */
    internal val away by derivedStateOf { progress > .5f }
    internal val collapsed by derivedStateOf { progress >= 1f }
    internal val canCollapse get() = mini != null && slot != null

    internal fun show(next: ViewerRequest) {
        val old = request
        if (old != null && old.id != next.id) {
            // Another screen's viewer replaces this one (and its music), closed, and opens full screen.
            old.onClose(); mini = null; wantBar = false; tapped = null; settle(PlayerSize.Full, instant = true)
        }
        if (old == null) leaving = null
        request = next
    }
    internal fun dismiss(old: ViewerRequest) {
        if (request !== old) return
        // Music in the bar outlives the screen that opened it; the bar's Close or the track's end clears it.
        if (mini != null && (wantBar || size.targetValue == PlayerSize.Bar)) return
        clear(fade = !collapsed)
    }
    /** The bar's Close, or a session that ended: stops it the way the viewer's own Close does. */
    internal fun close() {
        val closing = request ?: return
        val session = mini
        clear(fade = !collapsed && !moving)
        session?.close?.invoke() ?: closing.onClose()
    }
    internal fun collapse() {
        if (!canCollapse) return
        wantBar = true; settle(PlayerSize.Bar)
    }
    internal fun expand() { wantBar = false; settle(PlayerSize.Full) }
    /** A screen's tap on a file: with its viewer in the bar, the music there opens again, or the tapped file opens instead. */
    fun reopen(uri: android.net.Uri) { if (request != null) tapped = ViewerTap(uri) }
    /** A new intent brings the app forward: music shrinks into the bar, anything else closes. */
    fun yieldToApp() { if (request == null) return; if (canCollapse) collapse() else close() }

    private fun clear(fade: Boolean) {
        leaving = request.takeIf { fade && !reduced }
        request = null; mini = null; wantBar = false; tapped = null
        settle(PlayerSize.Full, instant = true)
    }
    private fun settle(to: PlayerSize, instant: Boolean = false) {
        val launch = scope ?: return
        launch.launch(start = CoroutineStart.UNDISPATCHED) {
            if (instant || reduced) size.snapTo(to) else size.animateTo(to, SETTLE)
        }
    }

    companion object {
        /** Spatial, no bounce, about 380 ms: it carries the release speed of a fling. */
        internal val SETTLE: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 400f)
        internal val Saver = Saver<ViewerHostState, Boolean>({ it.wantBar || it.collapsed }, { ViewerHostState(it) })
    }
}

internal enum class PlayerSize { Full, Bar }

/**
 * A drag that starts here stays here (a steep seek, a details scroll): while a finger is down on it the page doesn't
 * shrink into the bar. Only watches, so the slider or scroll inside still gets every move.
 */
internal fun Modifier.keepsDrags(host: ViewerHostState?) = if (host == null) this else pointerInput(host) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        host.held = true
        try { do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed }) }
        finally { host.held = false }
    }
}

/** One tap: a new object each time, so tapping the same file twice still reads as a change. */
internal class ViewerTap(val uri: android.net.Uri)

/** The page that can sit in the bar: its file, its playback and its own Close. */
internal class MiniSession(val item: ViewerItem, val owner: dev.ahmed.savlino.playback.PlaybackCoordinator, val close: () -> Unit)

/** The bar's place in window pixels, and which side its cover sits on. */
internal data class MiniSlot(val rect: Rect, val rtl: Boolean)

val LocalViewerHost = staticCompositionLocalOf<ViewerHostState?> { null }

/** Keeps the bar across rotation: a collapsed player comes back collapsed. */
@Composable
fun rememberViewerHost(): ViewerHostState = rememberSaveable(saver = ViewerHostState.Saver) { ViewerHostState() }

/** Wraps the app: while a viewer is open the app below takes no touches, focus, keys or TalkBack, as under a Dialog. */
@Composable
fun ViewerHost(state: ViewerHostState = remember { ViewerHostState() }, app: @Composable () -> Unit) {
    val request = state.request
    val shown = request ?: state.leaving
    val back = LocalOnBackPressedDispatcherOwner.current
    val registry = LocalSaveableStateRegistry.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The app's own reduced motion is provided below this host, so read the system setting here too.
    val reduced = LocalReducedMotion.current || rememberReducedMotion()
    val scope = rememberCoroutineScope()
    SideEffect { state.scope = scope; state.reduced = reduced }
    val covered = request != null && !state.collapsed
    LaunchedEffect(covered) { if (covered) { focus.clearFocus(force = true); keyboard?.hide() } }
    // Collapsing leaves nothing focused inside the page that is no longer placed.
    LaunchedEffect(state.collapsed) { if (state.collapsed) focus.clearFocus(force = true) }
    // Opening grows the page in from slightly smaller; closing fades it out. Both draw-only.
    val appear = remember { Animatable(if (request != null) 1f else 0f) }
    LaunchedEffect(request?.id) {
        if (request == null) return@LaunchedEffect
        if (reduced) appear.snapTo(1f) else if (appear.value < 1f) appear.animateTo(1f, tween(Motion.TAB, easing = Motion.EaseOut))
    }
    LaunchedEffect(state.leaving) {
        if (state.leaving == null) return@LaunchedEffect
        if (!reduced) appear.animateTo(0f, tween(160, easing = Motion.EaseOut)) else appear.snapTo(0f)
        state.leaving = null
    }
    CompositionLocalProvider(LocalViewerHost provides state) {
        Box(Modifier.fillMaxSize()) {
            // A keyboard or D-pad can't move into the covered app, and focus never leaves the viewer for it.
            // Fully covered and still, the app isn't drawn at all.
            Box(Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (state.request != null && !state.moving && appear.value >= 1f) 0f else 1f }
                .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { if (covered) onEnter = { cancelFocusChange() } }.focusGroup()) { app() }
            val mini = state.mini
            val slot = state.slot
            if (request != null && mini != null && slot != null) request.locals?.let { locals ->
                CompositionLocalProvider(locals) {
                    // Placed only once the page starts to shrink: an invisible bar must not take the page's touches.
                    MiniPlayerBar(state, mini, Modifier.layout { measurable, constraints ->
                        val bar = measurable.measure(Constraints.fixed(slot.rect.width.roundToInt(), slot.rect.height.roundToInt()))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            if (state.moving) bar.place(slot.rect.left.roundToInt(), slot.rect.top.roundToInt())
                        }
                    })
                }
            }
            shown?.let { open ->
                val locals = open.locals ?: return@let
                val draggable = request != null && state.mini != null
                key(open.id) { Box(Modifier.fillMaxSize()
                    // Collapsed, the page stays composed (same player, same page) but is not placed: no drawing, touches or TalkBack.
                    .layout { measurable, constraints ->
                        val page = measurable.measure(constraints)
                        layout(page.width, page.height) {
                            if (!state.collapsed) page.placeWithLayer(0, 0) {
                                val grown = .96f + .04f * appear.value
                                alpha = appear.value; scaleX = grown; scaleY = grown
                            }
                        }
                    }
                    .anchoredDraggable(state.size, Orientation.Vertical, enabled = draggable && !state.held,
                        flingBehavior = AnchoredDraggableDefaults.flingBehavior(state.size, { it * .5f },
                            if (reduced) snap() else ViewerHostState.SETTLE))
                    .then(if (request == null) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusProperties { if (!state.collapsed) onExit = { cancelFocusChange() } }.focusGroup()) {
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
