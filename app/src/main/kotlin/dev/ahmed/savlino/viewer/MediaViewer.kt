package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.Space

/**
 * Full-screen swipe viewer over [items], opening at [start]. Images zoom (pinch, double tap) and the pager only swipes at 1x;
 * videos play with the platform player while their page is the settled one. Back or Close dismisses.
 * [actions] adds screen-specific buttons (Statuses: Save) before Share and Open with.
 */
@Composable
fun MediaViewer(items: List<ViewerItem>, start: Int, language: String, onClose: () -> Unit,
                onShare: (ViewerItem) -> Unit, onOpenWith: (ViewerItem) -> Unit,
                actions: @Composable RowScope.(ViewerItem) -> Unit = {}) {
    if (items.isEmpty()) return
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Black page: light status and navigation icons whatever the app theme.
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
        }
        val pager = rememberPagerState(start.coerceIn(0, items.lastIndex)) { items.size }
        val zooms = remember(items) { items.associate { it.uri to ZoomState() } }
        val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        // A page swiped away comes back unzoomed.
        LaunchedEffect(pager.settledPage, items) {
            val settled = items.getOrNull(pager.settledPage)?.uri
            zooms.forEach { (uri, zoom) -> if (uri != settled) zoom.reset() }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.viewer_close)) }
                    Spacer(Modifier.weight(1f))
                    actions(current)
                    IconButton(onClick = { onShare(current) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_ms_share), stringResource(R.string.share_file))
                    }
                    IconButton(onClick = { onOpenWith(current) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_ms_open_in_new), stringResource(R.string.saved_open_with))
                    }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { items[it].uri.toString() },
                    userScrollEnabled = zooms[current.uri]?.zoomed != true) { page ->
                    val item = items[page]
                    val description = stringResource(when { item.isVideo -> R.string.viewer_video_n; item.isAudio -> R.string.viewer_audio_n; else -> R.string.viewer_image_n },
                        NezzelFormat.count(page + 1, language), NezzelFormat.count(items.size, language))
                    Box(Modifier.fillMaxSize().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                        if (item.isVideo || item.isAudio) ViewerVideo(item, current = pager.settledPage == page)
                        else ZoomableImage(item, zooms.getValue(item.uri))
                    }
                }
            }
        }
    }
}

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
