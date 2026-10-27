package dev.ahmed.savlino.viewer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
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
import java.text.Bidi
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
import dev.ahmed.savlino.ui.theme.Dock
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import dev.ahmed.savlino.ui.theme.Space
import androidx.media3.session.R as Media3

/**
 * Where everything sits at progress [p] (0 = full screen, 1 = the bar), as YouTube Music does it. The page is one opaque
 * sheet, always full width while it travels: its top follows the finger, its bottom rises with the tabs onto the bar's bottom,
 * so it only gets shorter, and its colour turns from black into the bar's. The cover rides on it, shrinking to fit and moving to
 * the start edge; the sheet then narrows into [surface], the bar itself at full bar width around the cover, which shrinks with
 * the cover until it is the bar. A pure function of [p], so expanding retraces collapsing.
 */
internal data class MiniPlayerLayout(val sheet: Rect, val topCorner: Dp, val corner: Dp, val art: Rect, val content: Float, val fade: Float,
    val tabs: Float, val tint: Float, val surface: Rect) {
    companion object {
        /**
         * In window pixels, for a page of [page] size, its cover at [from] on the still page, and the bar at [bar] with its cover
         * [inset] from the start edge ([rtl]: the right). With no bar to land on ([landing] false) the sheet fades out instead.
         */
        fun at(p: Float, page: Size, bar: Rect, from: Rect, rtl: Boolean, inset: Float, landing: Boolean = true): MiniPlayerLayout {
            val rise = FastOutSlowInEasing.transform(span(p, 0f, RISE))
            val band = Rect(0f, p * bar.top, page.width, lerp(page.height, bar.bottom, rise))
            // On the sheet until LAND, then straight onto the bar's cover: it never sits bigger than the bar over the bar.
            val art = lerp(cover(minOf(p, LAND), page, bar, from, rtl, inset), cover(1f, page, bar, from, rtl, inset),
                FastOutSlowInEasing.transform(span(p, LAND, 1f)))
            val crown = lerp(0.dp, SHEET_CORNER, FastOutSlowInEasing.transform(span(p, 0f, .1f)))
            val draw = FastOutSlowInEasing.transform(span(p, .3f, RISE))
            // The bar's own colour by the time it is the bar.
            val tint = draw
            // No bar to land on: the cover stays where it sits on the page and the sheet fades.
            if (!landing) return MiniPlayerLayout(band, crown, 0.dp, from.translate(0f, band.top), content(p), 1f - span(p, LAND, 1f), tabs(p), tint, band)
            // One surface from the start edge of the bar to its end: the cover with its inset all round, never shorter than the bar.
            val surface = Rect(bar.left, art.top - inset, bar.right, maxOf(art.bottom + inset, art.top - inset + bar.height))
            // Narrowed into the surface while the name and controls are gone; then the bar is the surface and the sheet only the
            // cover on it, so nothing from the page shows between the cover and the bar.
            if (p > RISE) return MiniPlayerLayout(art, COVER_CORNER, COVER_CORNER, art, content(p), 1f, tabs(p), tint, surface)
            return MiniPlayerLayout(lerp(band, surface, draw), lerp(crown, Dock.CORNER, draw), lerp(0.dp, Dock.CORNER, draw), art, content(p), 1f,
                tabs(p), tint, surface)
        }
        /** The cover on the sheet at [p]: as big as it was while it fits, then as tall as the band allows (48dp in the 64dp bar), at
         * the start edge [inset] in from the bar's start side, so it always fits on the bar's surface. */
        private fun cover(p: Float, page: Size, bar: Rect, from: Rect, rtl: Boolean, inset: Float): Rect {
            val top = p * bar.top
            val height = lerp(page.height, bar.bottom, FastOutSlowInEasing.transform(span(p, 0f, RISE))) - top
            val side = minOf(from.width, height - 2 * inset, bar.width - 2 * inset).coerceAtLeast(0f)
            val edge = if (rtl) bar.right - inset - side else bar.left + inset
            val left = lerp(from.center.x - side / 2, edge, FastOutSlowInEasing.transform(span(p, .3f, .7f)))
            val y = top + minOf(from.top, (height - side) / 2)
            return Rect(left, y, left + side, y + side)
        }
        /** The name, seek bar, controls and top bar ride on the sheet and fade early, as the finger starts. */
        fun content(p: Float) = 1f - span(p, 0f, .3f)
        /** The tabs rise from under the sheet with its bottom edge. */
        fun tabs(p: Float) = 1f - FastOutSlowInEasing.transform(span(p, 0f, RISE))
        /** The bar is the sheet's surface from when the sheet has narrowed into it: only then is it placed. */
        fun bar(p: Float) = p > RISE
        /** The name and controls fade in together from when the bar is placed, under the cover that is still landing on them. */
        fun row(p: Float) = FastOutSlowInEasing.transform(span(p, RISE, 1f))
        /** A long name scrolls only in the settled bar, and never with animations off. */
        fun marquee(p: Float, reduced: Boolean) = p >= 1f && !reduced
        /** The name scrolls this long after the bar settles. */
        const val MARQUEE_DELAY = 3000
        val MARQUEE_SPEED = 24.dp
        private fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
        private const val RISE = .6f
        private const val LAND = .85f
        val BAR = 64.dp
        val COVER = 48.dp
        val INSET = 8.dp
        /** The sheet's top corners once it leaves full screen, like a bottom sheet's. */
        val SHEET_CORNER = 28.dp
        /** Inside the bar's round end: the cover's corner arc stays within the bar's; the page's cover has it too. */
        val COVER_CORNER = 16.dp
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/** Name and controls riding on the sheet: once faded out, TalkBack skips them and a new press doesn't reach them. */
internal fun Modifier.fadesWithSheet(host: ViewerHostState?): Modifier =
    if (host?.faded != true) this else clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (it.changedToDown()) it.consume() } }
    }

/** The music page's colour, the sheet that shrinks into the bar. */
internal val SHEET_COLOR = Color.Black

/** The sheet's outline inside the page's own layer, which already sits [MiniPlayerLayout.sheet] top pixels down. */
internal data class SheetShape(val rect: Rect, val top: Float, val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(rect, CornerRadius(top), CornerRadius(top), CornerRadius(bottom), CornerRadius(bottom)))
}

/** The sheet's colour at [tint]: black on the page, the bar's own colour by the time it lands. */
internal fun ViewerHostState.sheetColor(tint: Float) = androidx.compose.ui.graphics.lerp(SHEET_COLOR, barColor, tint)

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
    Spacer(modifier.fillMaxWidth().padding(bottom = Dock.GAP).height(MiniPlayerLayout.BAR)
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
        // A session that ends mid-collapse: they follow the page back up instead of jumping.
        if (host.request != null && (host.mini != null || host.moving)) translationY = MiniPlayerLayout.tabs(host.progress) * (size.height + below)
    }
}

/** The paste field and the footer: the bar grows over them while it moves, so they fade out as it leaves and back in once it settles. */
@Composable
fun Modifier.miniPlayerReveal(): Modifier {
    val host = LocalViewerHost.current ?: return this
    return graphicsLayer { if (host.request != null && (host.mini != null || host.moving)) alpha = host.reveal.value }
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
    val bar = Dock.color
    SideEffect { host.barColor = bar }
    val reduced = LocalReducedMotion.current || rememberReducedMotion()
    // Name and controls as one layer alpha: they come in under the landing cover, no recomposition per frame.
    val row = remember(host) { Modifier.graphicsLayer { alpha = MiniPlayerLayout.row(host.progress) } }
    val toggle = { if (playing) session.owner.pause() else session.owner.play() }
    val label = stringResource(if (playing) R.string.mini_playing else R.string.mini_paused, title)
    val open = stringResource(R.string.mini_open_player)
    val playLabel = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play)
    val closeLabel = stringResource(R.string.viewer_close)
    // The sheet's surface once the sheet has narrowed into it, under the page's cover until that lands.
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
        shape = Dock.SHAPE, color = Dock.color, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = Dock.SHADOW) {
        // The row keeps the settled bar's height at the surface's top, beside the cover's top while the surface is taller.
        Row(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(MiniPlayerLayout.BAR)
            .padding(horizontal = MiniPlayerLayout.INSET), verticalAlignment = Alignment.CenterVertically) {
            // While it moves, the page's own cover is the cover: it lands exactly here.
            Surface(Modifier.size(MiniPlayerLayout.COVER).graphicsLayer { alpha = if (host.progress < 1f) 0f else 1f }, shape = RoundedCornerShape(MiniPlayerLayout.COVER_CORNER), color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(.5f)) }
            }
            // A Latin file name reads left to right inside either language, an Arabic one right to left; it scrolls the same way.
            val ltr = remember(title) { Bidi(title, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight() }
            val scrolls = MiniPlayerLayout.marquee(if (host.collapsed) 1f else 0f, reduced)
            Box(Modifier.weight(1f).padding(horizontal = Space.m).then(row), contentAlignment = Alignment.CenterStart) {
                CompositionLocalProvider(LocalLayoutDirection provides if (ltr) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                    Text(title, if (scrolls) Modifier.basicMarquee(initialDelayMillis = MiniPlayerLayout.MARQUEE_DELAY,
                        velocity = MiniPlayerLayout.MARQUEE_SPEED) else Modifier,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = if (scrolls) TextOverflow.Clip else TextOverflow.Ellipsis)
                }
            }
            IconButton(toggle, Modifier.size(48.dp).then(row)) {
                Icon(painterResource(if (playing) Media3.drawable.media3_icon_pause else Media3.drawable.media3_icon_play), playLabel, Modifier.size(24.dp))
            }
            IconButton({ host.close() }, Modifier.size(48.dp).then(row)) { Icon(Icons.Default.Close, closeLabel) }
        }
    }
}

/** The bar's node, for tests. */
const val MINI_PLAYER_TAG = "mini_player"
