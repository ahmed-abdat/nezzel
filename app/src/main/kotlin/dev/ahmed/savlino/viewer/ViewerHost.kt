package dev.ahmed.savlino.viewer

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
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
            if (value != null) lastSlot = value
            if (value != null && value.rect.top > 0f && value.rect.top != travel)
                size.updateAnchors(DraggableAnchors { PlayerSize.Full at 0f; PlayerSize.Bar at value.rect.top }, size.targetValue)
        }
    /** A file the screen was asked to open again, maybe while its viewer sits in the bar; that viewer takes it. */
    internal var tapped by mutableStateOf<ViewerTap?>(null)
    /** A finger is down on a slider or scroll inside the page: the page's own drag waits. */
    internal var held by mutableStateOf(false)
    /** A drag that started on the bar is still going: the bar stays placed (unseen) so it gets the rest of that drag. */
    internal var barDragging by mutableStateOf(false)
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
    /** The page is off its rest: shrinking into the bar, or pulled down to close. The app shows behind it either way. */
    internal val displaced by derivedStateOf { moving || pull > 0f && !pullLeavesFullscreen }
    /** The sheet has drawn back far enough to uncover the bar's row riding on its top edge. */
    internal val barShown by derivedStateOf { MiniPlayerLayout.bar(progress) }
    /** Placed only for the drag that started on it, and unseen: nothing on it answers a tap, a second finger or TalkBack. */
    internal val barHidden by derivedStateOf { barDragging && !barShown }
    /** What the bar grows over while it moves (the paste field, the footer): out as it leaves the slot, in once it settles. */
    internal val reveal = Animatable(1f)
    /** The sheet has left the status bar: the app's own status bar icons are back. */
    internal val uncovered by derivedStateOf { progress > .05f }
    /** The sheet has left the navigation bar too. */
    internal val away by derivedStateOf { progress > .6f }
    /** The name and controls have faded out: nothing on them can be focused or pressed until they come back. */
    internal val faded by derivedStateOf { MiniPlayerLayout.content(progress) <= 0f }
    internal val collapsed by derivedStateOf { progress >= 1f }
    internal val canCollapse get() = mini != null && slot != null
    /** The page's size, as last measured. */
    internal var page = Size.Zero
    /** Where the sheet is right now; null while there is no bar to shrink into. Read it only in layout, layer and draw lambdas. */
    internal fun motion(): MiniPlayerLayout? {
        // A session that ends mid-collapse takes its slot along: the page goes back up through the bar's last place, not in one jump.
        val at = (slot ?: lastSlot?.takeIf { request != null && progress > 0f })?.takeIf { page != Size.Zero } ?: return null
        val p = progress
        val landing = mini != null
        // Before the page's cover is placed, it is already the bar's.
        val from = art ?: at.cover(coverInset, coverSide)
        // The page's layer and its cover both ask each frame: one layout per frame, not one per caller.
        last?.let { if (p == lastP && at == lastAt && from == lastFrom && page == lastPage && landing == lastLanding) return it }
        return MiniPlayerLayout.at(p, page, at.rect, from, at.rtl, coverInset, landing).also {
            last = it; lastP = p; lastAt = at; lastFrom = from; lastPage = page; lastLanding = landing
        }
    }
    private var lastSlot: MiniSlot? = null
    private var last: MiniPlayerLayout? = null
    private var lastP = 0f
    private var lastAt: MiniSlot? = null
    private var lastFrom: Rect? = null
    private var lastPage = Size.Zero
    private var lastLanding = false
    /** The page's cover on the still page, in window pixels: what lands on the bar's cover. */
    internal var art by mutableStateOf<Rect?>(null)
    internal var coverInset = 0f
    /** The bar's colour, which the sheet turns into as it lands. */
    internal var barColor = Color.Black
    internal var coverSide = 0f

    /** The video or photo page that can be pulled down right now ([pullsDown]); null on music and while nothing is open. */
    internal var exit by mutableStateOf<PageExit?>(null)
    /** How far that page is pulled down, in pixels; 0 at rest. Read it only in layer and draw lambdas. */
    internal var pull by mutableFloatStateOf(0f); private set
    /** This pull leaves fullscreen rather than closing: the app stays hidden behind black. */
    internal var pullLeavesFullscreen by mutableStateOf(false); private set
    /** 0 at rest, 1 a full page height down; always 0 for a pull out of fullscreen, which neither shrinks nor fades. */
    internal val pulled: Float get() = if (pullLeavesFullscreen) 0f else PagePull.fraction(pull, page.height)
    /** A downward release this fast, in pixels per second, leaves the page. */
    internal var flingSpeed = Float.MAX_VALUE
    private var pullJob: Job? = null

    internal fun canPull() = request != null && mini == null && !held && exit?.can?.invoke() == true
    internal fun startPull(): Boolean {
        val page = exit?.takeIf { canPull() } ?: return false
        pullJob?.cancel()
        pullLeavesFullscreen = page.fullscreen()
        return true
    }
    /** Reduced motion: the page stays put under the finger, and only the release decides. */
    internal fun dragPull(distance: Float) {
        if (!reduced) pull = if (pullLeavesFullscreen) distance * PagePull.FULLSCREEN_FOLLOW else distance
    }
    internal fun releasePull(distance: Float, velocity: Float) {
        val page = exit
        when {
            page == null || !PagePull.letsGo(distance, velocity, this.page.height, flingSpeed) -> settlePull(velocity)
            pullLeavesFullscreen -> { page.leaveFullscreen(); settlePull(velocity) }
            else -> finishPull()
        }
    }
    /** Predictive back on a video or photo: the same pull, a little way down as the gesture goes. */
    internal fun backPull(progress: Float) {
        pullJob?.cancel(); pullLeavesFullscreen = false
        if (!reduced) pull = progress * PagePull.BACK * page.height
    }
    /** Carries a pulled page the rest of the way down and closes it; one that never moved closes with the usual fade. */
    internal fun finishPull() {
        val close = exit?.close ?: return settlePull(0f)
        pullJob?.cancel()
        if (reduced || pull <= 0f || page.height <= 0f) { pull = 0f; close(); return }
        pullJob = scope?.launch {
            animate(pull, page.height, animationSpec = tween(PagePull.OUT_MILLIS, easing = Motion.EaseOut)) { value, _ -> pull = value }
            close()
        } ?: run { pull = 0f; close(); null }
    }
    internal fun settlePull(velocity: Float) {
        pullJob?.cancel()
        if (pull == 0f) return
        pullJob = scope?.launch { animate(pull, 0f, velocity, if (reduced) snap() else SETTLE) { value, _ -> pull = value } }
            ?: run { pull = 0f; null }
    }

    internal fun show(next: ViewerRequest) {
        val old = request
        if (old != null && old.id != next.id) {
            // Another screen's viewer replaces this one (and its music), closed, and opens full screen.
            old.onClose(); mini = null; wantBar = false; tapped = null; settle(PlayerSize.Full, instant = true)
        }
        if (old == null) leaving = null
        pullJob?.cancel(); pull = 0f
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
    /** The music left mid-collapse (its player gone, another page settled): there is no bar to become, so the page goes back up. */
    internal fun lost() { if (request != null && mini == null && moving && !collapsed) expand() }
    /** A screen's tap on a file: with its viewer in the bar, the music there opens again, or the tapped file opens instead. */
    fun reopen(uri: android.net.Uri) { if (request != null) tapped = ViewerTap(uri) }
    /** A new intent brings the app forward: music shrinks into the bar, anything else closes. */
    fun yieldToApp() { if (request == null) return; if (canCollapse) collapse() else close() }

    private fun clear(fade: Boolean) {
        // A page pulled out is already gone: it doesn't come back to fade.
        leaving = request.takeIf { fade && !reduced && pull == 0f }
        // It also keeps its pull until the next show(): the page's layer can draw once more before it leaves the
        // composition, and at 0 that frame would flash the whole viewer back.
        pullJob?.cancel()
        request = null; mini = null; wantBar = false; tapped = null; art = null; lastSlot = null
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
    val navigation = LocalNavigationEventDispatcherOwner.current
    val registry = LocalSaveableStateRegistry.current
    // The app's own reduced motion is provided below this host, so read the system setting here too.
    val reduced = LocalReducedMotion.current || rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    SideEffect {
        state.scope = scope; state.reduced = reduced
        with(density) {
            state.coverInset = MiniPlayerLayout.INSET.toPx(); state.coverSide = MiniPlayerLayout.COVER.toPx()
            state.flingSpeed = PagePull.FLING.toPx()
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.collapsed }.collect { settled ->
            if (state.reduced) state.reveal.snapTo(if (settled) 1f else 0f)
            else state.reveal.animateTo(if (settled) 1f else 0f, tween(if (settled) Motion.TAB else 100, easing = Motion.EaseOut))
        }
    }
    // Only a session that goes away: one swapped for the next in the same frame, or not yet attached, never reads as gone.
    LaunchedEffect(state) { snapshotFlow { state.mini != null }.drop(1).collect { if (!it) state.lost() } }
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
            CoveredApp(state, appear, app)
            val mini = state.mini
            val slot = state.slot
            if (request != null && mini != null && slot != null) request.locals?.let { locals ->
                CompositionLocalProvider(locals) {
                    // Placed only once the sheet has narrowed into it: a hidden bar must not take the page's touches.
                    // Then it is the sheet's surface around the cover, so name and controls travel with the cover instead of
                    // waiting in the slot, and it shrinks with the cover into the slot.
                    MiniPlayerBar(state, mini, Modifier.layout { measurable, constraints ->
                        val surface = state.motion()?.surface?.takeIf { state.barShown } ?: slot.rect
                        val bar = measurable.measure(Constraints.fixed(slot.rect.width.roundToInt(), surface.height.roundToInt()))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            if (state.barShown) bar.place(slot.rect.left.roundToInt(), surface.top.roundToInt())
                            // A drag from the bar that has pulled the sheet past it keeps going to its end, or the sheet would stop there.
                            else if (state.barDragging) bar.placeWithLayer(slot.rect.left.roundToInt(), slot.rect.top.roundToInt()) { alpha = 0f }
                        }
                    })
                }
            }
            // A page pulled down moves over the viewer's black, which stays put and thins out so the app shows through.
            // Fullscreen keeps it whole: the app stays hidden until fullscreen is left.
            if (shown != null) Box(Modifier.fillMaxSize().drawBehind {
                if (state.pull > 0f) drawRect(SHEET_COLOR, alpha = if (state.pullLeavesFullscreen) 1f else PagePull.backdrop(state.pulled))
            })
            shown?.let { open ->
                val locals = open.locals ?: return@let
                val draggable = request != null && state.mini != null
                key(open.id) { Box(Modifier.fillMaxSize()
                    // Outside the page's layer: the finger is measured on the still screen, not on the moving, shrinking page.
                    // Music owns its page's drags (shrink into the bar): no pull recognizer there at all.
                    .then(if (state.mini == null) Modifier.pullsDown(state) else Modifier)
                    // Collapsed, the page stays composed (same player, same page) but is not placed: no drawing, touches or TalkBack.
                    .layout { measurable, constraints ->
                        val page = measurable.measure(constraints)
                        state.page = Size(page.width.toFloat(), page.height.toFloat())
                        layout(page.width, page.height) {
                            if (!state.collapsed) page.placeWithLayer(0, 0) {
                                // A video or photo pulled down: it follows the finger and shrinks a little (see PagePull).
                                val pulled = state.pulled
                                val grown = (.96f + .04f * appear.value) * PagePull.scale(pulled)
                                scaleX = grown; scaleY = grown
                                // Shrinking music: the whole page is one sheet that slides down and narrows into the bar.
                                val motion = state.motion()?.takeIf { state.progress > 0f }
                                alpha = appear.value * (motion?.fade ?: 1f) * PagePull.alpha(pulled)
                                // The page slides with the finger; the sheet's outline (shorter, then drawing into the cover) clips it.
                                val top = if (motion != null) state.progress * state.travel else 0f
                                translationY = top + state.pull
                                clip = motion != null
                                shape = motion?.run { SheetShape(sheet.translate(0f, -top), topCorner.toPx(), corner.toPx()) } ?: RectangleShape
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
                            CompositionLocalProvider(*listOfNotNull(back?.let { LocalOnBackPressedDispatcherOwner provides it },
                                navigation?.let { LocalNavigationEventDispatcherOwner provides it }).toTypedArray()) { open.content() }
                        }
                    }
                } }
            }
        }
    }
}

/** The app under the viewer. Its own scope: the page covering or uncovering it recomposes only this, not the host. */
@Composable
private fun CoveredApp(state: ViewerHostState, appear: Animatable<Float, *>, app: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val collapsed = state.collapsed
    val covered = state.request != null && !collapsed
    LaunchedEffect(covered) { if (covered) { focus.clearFocus(force = true); keyboard?.hide() } }
    // Collapsing leaves nothing focused inside the page that is no longer placed.
    LaunchedEffect(collapsed) { if (collapsed) focus.clearFocus(force = true) }
    // A keyboard or D-pad can't move into the covered app, and focus never leaves the viewer for it.
    // Fully covered and still, the app isn't drawn at all.
    Box(Modifier.fillMaxSize()
        .graphicsLayer { alpha = if (state.request != null && !state.displaced && appear.value >= 1f) 0f else 1f }
        .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
        .focusProperties { if (covered) onEnter = { cancelFocusChange() } }.focusGroup()) { app() }
}
