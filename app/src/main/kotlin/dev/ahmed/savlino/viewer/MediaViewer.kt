package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.collapse
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.flow.first

/**
 * Full-screen swipe viewer over [items], opening at [start]. Images zoom (pinch, double tap) and the pager only swipes at 1x;
 * videos play with Media3 while their page is the settled one; a tap on them shows or hides the chrome, a double tap skips 10 s.
 * Back leaves fullscreen first, then dismisses, as does Close. Inside the app it is drawn by [ViewerHost], above the tabs.
 * [actions] adds screen-specific buttons (Statuses: Save) before Share and Open with. [library] is every file the screen
 * knows, filters and search aside: music in the bar whose file left it was deleted, so the bar closes.
 */
@Composable
fun MediaViewer(items: List<ViewerItem>, start: Int, language: String, onClose: () -> Unit,
                onShare: (ViewerItem) -> Unit, onOpenWith: (ViewerItem) -> Unit,
                library: Set<android.net.Uri>? = null, actions: @Composable RowScope.(ViewerItem) -> Unit = {}) {
    val host = LocalViewerHost.current
    // Tests and captures without the app host draw the viewer where it is composed.
    if (host == null) { if (items.isNotEmpty()) ViewerScreen(items, start, language, onClose, onShare, onOpenWith, actions); return }
    val id = androidx.compose.runtime.saveable.rememberSaveable { java.util.UUID.randomUUID().toString() }
    val request = remember(id) { ViewerRequest(id) }
    // The file playing in the bar was deleted: the bar and its music go with it. A filter or search that only hides it doesn't.
    val playing = host.mini?.item?.uri
    if (playing != null && library != null && host.collapsed && host.request === request && playing !in library) SideEffect { host.close() }
    if (items.isEmpty()) return
    val locals = currentCompositionLocalContext
    // A file tapped in the screen while its music sits in the bar: the music playing there just opens again; any other
    // file opens in its place, full screen (a new page, so the music stops).
    var shown by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableIntStateOf(start) }
    var turn by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableIntStateOf(0) }
    // A filter or search that hides the music in the bar leaves the viewer's own list as it was, so its page stays; the
    // screen's list applies again once the song shows, nothing plays, or a tap opens another file.
    val kept = remember(id) { KeptPages(items, turn) }
    if (playing == null || kept.turn != turn || items.any { it.uri == playing }) { kept.items = items; kept.turn = turn }
    // Read when drawn: a tap's new page draws in the same frame as this screen, before the new content is handed over.
    val content: @Composable () -> Unit = { key(shown, turn) { ViewerScreen(kept.items, shown, language, onClose, onShare, onOpenWith, actions) } }
    SideEffect { request.locals = locals; request.content = content; request.onClose = onClose }
    DisposableEffect(host, request) { host.show(request); onDispose { host.dismiss(request) } }
    val tap = host.tapped
    val latest by rememberUpdatedState(items)
    LaunchedEffect(start, tap) {
        val items = latest
        val asked = host.tapped?.let { t -> host.tapped = null; items.indexOfFirst { it.uri == t.uri }.takeIf { it >= 0 } }
        val index = asked ?: start.takeIf { it != shown && it in items.indices } ?: return@LaunchedEffect
        if (items[index].uri != host.mini?.item?.uri) { shown = index; turn++ }
        host.expand()
    }
}

/** The list the viewer last showed, and the open it belongs to. */
private class KeptPages(var items: List<ViewerItem>, var turn: Int)

@Composable
private fun ViewerScreen(items: List<ViewerItem>, start: Int, language: String, onClose: () -> Unit,
                         onShare: (ViewerItem) -> Unit, onOpenWith: (ViewerItem) -> Unit,
                         actions: @Composable RowScope.(ViewerItem) -> Unit) {
    val playback = rememberViewerPlayback()
    val openedBefore = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val restored = remember { openedBefore.value }
    val controls = androidx.compose.runtime.saveable.rememberSaveable(saver = ViewerControls.Saver) { ViewerControls() }
    val activity = LocalContext.current.findActivity()
    val reduced = LocalReducedMotion.current
    val usage = (LocalPlaybackEnvironment.current ?: dev.ahmed.savlino.playback.PlaybackEnvironment.get(LocalContext.current)).usage
    val fullscreen = { on: Boolean ->
        if (on && !controls.fullscreen) { ViewerFullscreen.enter(activity, controls); usage.count(UsageKeys.PLAYER_FULLSCREEN) }
        else if (!on) ViewerFullscreen.exit(activity, controls)
    }
    val close = { fullscreen(false); playback.close(); onClose() }
    val host = LocalViewerHost.current
    val ownedPlayer by playback.player.collectAsState()
    val ownedState by playback.state.collectAsState()
    // Rotation recreates the activity mid-fullscreen: only a real dismissal puts the old orientation back.
    DisposableEffect(activity) { onDispose { if (activity?.isChangingConfigurations != true) ViewerFullscreen.exit(activity, controls) } }
    LaunchedEffect(activity) { ViewerFullscreen.resume(activity, controls) }
    val collapsed = host?.collapsed == true
    // Back leaves fullscreen first, then closes; on music it shrinks the player into the bar instead.
    BackHandler(!collapsed) { if (controls.fullscreen) fullscreen(false) else close() }
    // Black page: light status and navigation icons whatever the app theme. Fullscreen hides the bars; a swipe shows them
    // briefly. The activity window is shared with the app, so closing puts its bars back as they were.
    val view = LocalView.current
    val window = activity?.window
    val appBars = remember(window, view) {
        window?.let { WindowCompat.getInsetsController(it, view).run { isAppearanceLightStatusBars to isAppearanceLightNavigationBars } } ?: (false to false)
    }
    if (window != null) DisposableEffect(window, view) {
        val bars = WindowCompat.getInsetsController(window, view)
        val behavior = bars.systemBarsBehavior
        onDispose {
            bars.isAppearanceLightStatusBars = appBars.first; bars.isAppearanceLightNavigationBars = appBars.second
            bars.show(WindowInsetsCompat.Type.systemBars()); bars.systemBarsBehavior = behavior
        }
    }
    // Shrinking past halfway uncovers the app, so its own bar icons come back.
    val away = host?.away == true
    LaunchedEffect(window, view, controls.fullscreen, away) {
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = away && appBars.first; isAppearanceLightNavigationBars = away && appBars.second
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls.fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val pager = rememberPagerState(start.coerceIn(0, items.lastIndex)) { items.size }
    val zooms = remember(items) { items.associate { it.uri to ZoomState() } }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    // The settled music page with its player can shrink into the bar; a player that attaches late registers it then.
    val shrinkable = items.getOrNull(pager.settledPage)?.takeIf { host != null && it.isAudio && ownedPlayer != null && playback.item?.uri == it.uri }
    val latestClose by rememberUpdatedState(close)
    if (host != null) DisposableEffect(host, shrinkable) {
        val session = shrinkable?.let { MiniSession(it, playback) { latestClose() } }
        if (session != null) host.mini = session
        onDispose { if (session != null && host.mini === session) host.mini = null }
    }
    // In the bar: the track ending or the service stopping takes the bar away; a failure opens the page with its card.
    // A page that arrives there with no player yet (restored after the app was killed, or another file opened from the
    // bar) opens full screen instead, paused until Play.
    var hadPlayer by remember { mutableStateOf(false) }
    if (ownedPlayer != null) hadPlayer = true
    LaunchedEffect(collapsed, ownedPlayer == null, ownedState) {
        if (!collapsed || host == null) return@LaunchedEffect
        if (ownedPlayer == null && !hadPlayer) host.expand()
        else if (ownedPlayer == null || ownedState == dev.ahmed.savlino.playback.PlaybackState.Ended) host.close()
        else if (ownedState is dev.ahmed.savlino.playback.PlaybackState.Failed) host.expand()
    }
    val minimize = host != null && shrinkable != null && host.mini?.owner === playback && host.slot != null
    // The music page's Back shrinks it, following the finger on Android 14+; a cancelled gesture puts it back.
    PredictiveBackHandler(minimize && !controls.fullscreen && !collapsed) { events ->
        val target = host ?: return@PredictiveBackHandler
        try {
            target.size.anchoredDrag { _ -> events.collect { dragTo(it.progress * .4f * target.travel) } }
            target.collapse()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { target.expand(); throw cancelled }
    }
    val moving = host?.moving == true
    // A page swiped away comes back unzoomed, and every newly settled page starts with its controls showing.
    LaunchedEffect(pager.settledPage, items) {
        val settledItem = items.getOrNull(pager.settledPage)
        settledItem?.let { playback.settle(it, initiallyTapped = playback.item == null && pager.settledPage == start.coerceIn(0, items.lastIndex), restoreOnly = restored && playback.item == null) }
        if (openedBefore.value) controls.show()
        openedBefore.value = true
        val settled = settledItem?.uri
        zooms.forEach { (uri, zoom) -> if (uri != settled) zoom.reset() }
    }
    // The black page slides and fades with the player as it shrinks; without the app host it is simply black.
    Surface(Modifier.fillMaxSize().testTag(VIEWER_TAG).drawBehind {
        val p = host?.progress ?: 0f
        drawRect(Color.Black.copy(alpha = MiniPlayerLayout.at(p).page), Offset(0f, p * (host?.travel ?: 0f)))
    }.then(if (minimize) Modifier.semantics { collapse { host?.collapse(); true } } else Modifier),
        color = Color.Transparent, contentColor = Color.White) {
        Box(Modifier.fillMaxSize()) {
            // Fullscreen runs edge to edge; the media page's own controls keep clear of cutouts and bars.
            // A rotation or hidden bars resize the pager without recreating it: settle back on the page so no neighbour peeks in.
            var width by remember { mutableIntStateOf(0) }
            LaunchedEffect(width) {
                snapshotFlow { pager.isScrollInProgress }.first { !it }
                if (pager.currentPageOffsetFraction != 0f) pager.scrollToPage(pager.currentPage)
            }
            HorizontalPager(pager, Modifier.fillMaxSize().then(if (controls.fullscreen) Modifier else Modifier.safeDrawingPadding())
                .onSizeChanged { width = it.width },
                key = { items[it].uri.toString() }, userScrollEnabled = zooms[current.uri]?.zoomed != true && !controls.fullscreen && !moving) { page ->
                // Full screen shows the playing file alone, and so does a page shrinking into the bar.
                if (controls.fullscreen && page != pager.currentPage) return@HorizontalPager
                val item = items[page]
                val description = stringResource(when { item.isVideo -> R.string.viewer_video_n; item.isAudio -> R.string.viewer_audio_n; else -> R.string.viewer_image_n },
                    NezzelFormat.count(page + 1, language), NezzelFormat.count(items.size, language))
                Box(Modifier.fillMaxSize().drawWithContent { if (host?.moving != true || page == pager.currentPage) drawContent() }
                    .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    if (item.isVideo || item.isAudio) ViewerVideo(item, current = pager.settledPage == page, playback, controls, language,
                        controls.fullscreen, fullscreen, onOpenWith = { onOpenWith(item) }, onClose = close)
                    // Pictures sit below the bar, as before; only playing media runs under its fading chrome.
                    else Box(Modifier.padding(top = TOP_BAR)) { ZoomableImage(item, zooms.getValue(item.uri)) }
                }
            }
            AnimatedVisibility(controls.shown, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(easeOut(reduced)), exit = fadeOut(easeOut(reduced))) {
                // The shade fades and travels with the controls it sits behind.
                Row(Modifier.fillMaxWidth().then(if (controls.shown) Modifier else Modifier.clearAndSetSemantics {})
                    .graphicsLayer { val p = host?.progress ?: 0f; alpha = MiniPlayerLayout.at(p).chrome; translationY = p * (host?.travel ?: 0f) }
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .onFocusChanged { controls.focus("top", it.hasFocus) }
                    .height(TOP_BAR).padding(horizontal = Space.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Playing music shrinks into the bar, which has its own Close; anything else closes here.
                    if (minimize) IconButton(onClick = { host?.collapse() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.viewer_minimize))
                    } else IconButton(onClick = close, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.viewer_close)) }
                    Spacer(Modifier.weight(1f))
                    actions(current)
                    IconButton(onClick = { controls.touched(); onShare(current) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_ms_share), stringResource(R.string.share_file))
                    }
                    IconButton(onClick = { controls.touched(); onOpenWith(current) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_ms_open_in_new), stringResource(R.string.saved_open_with))
                    }
                }
            }
        }
    }
}

internal val TOP_BAR = 56.dp
/** The viewer page's root, for tests that tell its nodes from the screen below. */
const val VIEWER_TAG = "media_viewer"

/** Longest screen side in pixels: decode no larger than the screen can show. */
@Composable
internal fun screenPixels(): Int {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) { maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx() }.coerceIn(320, 4096)
}

@Composable
fun rememberMediaPicture(uri: android.net.Uri, kind: ThumbnailKind, size: Int): Bitmap? {
    val context = LocalContext.current
    val pictures = LocalMediaPictures.current ?: remember(context) { systemPictures(context) }
    return produceState<Bitmap?>(null, uri, size, pictures) { value = pictures.load(uri, kind, size) }.value
}

/** Automatic list/grid artwork waits for tab motion; explicit viewer pictures above never wait. */
@Composable
fun rememberMediaThumbnail(uri: android.net.Uri, kind: ThumbnailKind, size: Int): Bitmap? {
    val context = LocalContext.current
    val pictures = LocalMediaPictures.current ?: remember(context) { systemPictures(context) }
    return dev.ahmed.savlino.ui.rememberCachedThumbnail(dev.ahmed.savlino.ui.ThumbnailCache.key(uri, size)) { pictures.load(uri, kind, size) }
}

@Composable
private fun ZoomableImage(item: ViewerItem, zoom: ZoomState) {
    val bitmap = rememberMediaPicture(item.uri, ThumbnailKind.IMAGE, screenPixels())
    Box(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle(it, size.toSize()) }) }
        .pointerInput(zoom) {
            // One finger at 1x is left to the pager; two fingers, or any drag while zoomed, belong to the image.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1 || zoom.zoomed) {
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid != Offset.Unspecified) zoom.transform(event.calculateZoom(), event.calculatePan(), centroid, size.toSize())
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.offset.x; translationY = zoom.offset.y
            }, contentScale = ContentScale.Fit)
        } ?: Icon(painterResource(R.drawable.ic_image), null, tint = LocalContentColor.current.copy(alpha = .6f))
    }
}
