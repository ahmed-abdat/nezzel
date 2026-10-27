package dev.ahmed.savlino.viewer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.media3.common.Player
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.Space
import androidx.media3.session.R as Media3

/**
 * Where everything sits at progress [p] (0 = full screen, 1 = the bar). The page is one opaque sheet that follows the
 * finger down with everything on it; late in the move it narrows into the bar's own rect, the cover settles into the
 * bar's cover, and the sheet fades over the bar drawn under it. A pure function of [p], so expanding retraces collapsing.
 */
internal data class MiniPlayerLayout(val sheet: Rect, val corner: Dp, val content: Float, val fade: Float, val tabs: Float) {
    companion object {
        /** The sheet in window pixels, for a page of [page] size and the bar at [bar]. */
        fun at(p: Float, page: Size, bar: Rect): MiniPlayerLayout {
            val narrow = FastOutSlowInEasing.transform(span(p, NARROW, 1f))
            val top = p * bar.top
            return MiniPlayerLayout(Rect(lerp(0f, bar.left, narrow), top, lerp(page.width, bar.right, narrow), lerp(page.height, bar.bottom, narrow)),
                lerp(0.dp, CORNER, narrow), content(p), fade(p), tabs(p))
        }
        /** The name, seek bar, controls and top bar ride on the sheet and only fade near the end. */
        fun content(p: Float) = 1f - span(p, .55f, .8f)
        /** The sheet itself, over the bar under it. */
        fun fade(p: Float) = 1f - span(p, .85f, 1f)
        /** The tabs come up last, from under the sheet. */
        fun tabs(p: Float) = 1f - FastOutSlowInEasing.transform(span(p, NARROW, 1f))
        /** The cover rides on the sheet (from [from], its place on the still page) and then settles into the bar's [to]. */
        fun cover(p: Float, from: Rect, to: Rect, travel: Float) =
            lerp(from.translate(0f, p * travel), to, FastOutSlowInEasing.transform(span(p, .5f, 1f)))
        fun coverCorner(p: Float, from: Dp) = lerp(from, 8.dp, FastOutSlowInEasing.transform(span(p, .5f, 1f)))
        private fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
        private const val NARROW = .6f
        val BAR = 64.dp
        val COVER = 48.dp
        val INSET = 8.dp
        /** The bar's corners, which the sheet's become. */
        val CORNER = 16.dp
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/** The music page's colour, the sheet that shrinks into the bar. */
internal val SHEET_COLOR = Color.Black

/** The sheet's outline inside the page's own layer, which already sits [MiniPlayerLayout.sheet] top pixels down. */
internal data class SheetShape(val rect: Rect, val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

/** The bar's cover in window pixels: inset at the start side of the slot, centred vertically. */
internal fun MiniSlot.cover(inset: Float, side: Float): Rect {
    val left = if (rtl) rect.right - inset - side else rect.left + inset
    val top = rect.top + (rect.height - side) / 2
    return Rect(left, top, left + side, top + side)
}

/** Room above the tabs (or at the bottom of the rail layout) that the bar is drawn into while music can shrink. */
@Composable
fun MiniPlayerSlot(modifier: Modifier = Modifier) {
    val host = LocalViewerHost.current ?: return
    if (host.mini == null) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Spacer(modifier.fillMaxWidth().padding(bottom = Space.s).height(MiniPlayerLayout.BAR)
        .onGloballyPositioned { host.slot = MiniSlot(it.boundsInRoot(), rtl) })
    DisposableEffect(host) { onDispose { host.slot = null } }
}

/** The tabs drop away under a full-screen music page and rise back as it shrinks into the bar. */
@Composable
fun Modifier.miniPlayerTabs(): Modifier {
    val host = LocalViewerHost.current ?: return this
    val density = LocalDensity.current
    val below = WindowInsets.navigationBars.getBottom(density) + with(density) { Space.m.toPx() }
    return graphicsLayer {
        if (host.mini != null && host.request != null) translationY = MiniPlayerLayout.tabs(host.progress) * (size.height + below)
    }
}

/** The shrunk player: cover, name, Play/Pause and Close. A tap or an upward drag opens the full page again. */
@Composable
internal fun MiniPlayerBar(host: ViewerHostState, session: MiniSession, modifier: Modifier) {
    val context = LocalContext.current
    val fallback = stringResource(R.string.playback_audio)
    val title by produceState(fallback, session.item.uri) { value = audioTitle(context, session.item.uri) ?: fallback }
    val player by session.owner.player.collectAsState()
    var playing by remember(player) { mutableStateOf(player?.playWhenReady == true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val cover = rememberMediaPicture(session.item.uri, ThumbnailKind.AUDIO, with(LocalDensity.current) { (MiniPlayerLayout.COVER * 4).roundToPx() })
    val toggle = { if (playing) session.owner.pause() else session.owner.play() }
    val label = stringResource(if (playing) R.string.mini_playing else R.string.mini_paused, title)
    val open = stringResource(R.string.mini_open_player)
    val playLabel = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play)
    val closeLabel = stringResource(R.string.viewer_close)
    // Drawn under the sheet, which fades over it at the end.
    Surface(modifier.anchoredDraggable(host.size, Orientation.Vertical,
            flingBehavior = AnchoredDraggableDefaults.flingBehavior(host.size, { it * .5f }, if (host.reduced) snap() else ViewerHostState.SETTLE))
        .clickable { host.expand() }
        // One TalkBack stop that says what plays, with its actions; a double tap opens the player.
        .clearAndSetSemantics {
            testTag = MINI_PLAYER_TAG
            contentDescription = label
            role = Role.Button
            onClick(open) { host.expand(); true }
            expand { host.expand(); true }
            customActions = listOf(CustomAccessibilityAction(playLabel) { toggle(); true },
                CustomAccessibilityAction(closeLabel) { host.close(); true },
                CustomAccessibilityAction(open) { host.expand(); true })
        },
        shape = RoundedCornerShape(MiniPlayerLayout.CORNER), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = MiniPlayerLayout.INSET), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(MiniPlayerLayout.COVER), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(.5f)) }
            }
            // A file name reads left to right inside either language.
            Text("\u2066$title\u2069", Modifier.weight(1f).padding(horizontal = Space.m), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(toggle, Modifier.size(48.dp)) {
                Icon(painterResource(if (playing) Media3.drawable.media3_icon_pause else Media3.drawable.media3_icon_play), playLabel, Modifier.size(24.dp))
            }
            IconButton({ host.close() }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, closeLabel) }
        }
    }
}

/** The bar's node, for tests. */
const val MINI_PLAYER_TAG = "mini_player"
