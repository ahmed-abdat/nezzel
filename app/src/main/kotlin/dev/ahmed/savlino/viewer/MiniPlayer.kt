package dev.ahmed.savlino.viewer

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
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
 * Where everything sits at progress [p] (0 = full screen, 1 = the bar). Controls go first, the page's black goes next,
 * and the bar arrives last, so only the cover is seen travelling from [from] (the page's cover) to [to] (the bar's).
 */
internal data class MiniPlayerLayout(val chrome: Float, val title: Float, val page: Float, val bar: Float, val tabs: Float,
                                     val cover: Rect, val corner: Dp) {
    companion object {
        fun at(p: Float, from: Rect = Rect.Zero, to: Rect = Rect.Zero) = MiniPlayerLayout(
            chrome = 1f - span(p, 0f, .3f), title = 1f - span(p, 0f, .2f), page = 1f - span(p, .3f, .9f),
            bar = span(p, .75f, 1f), tabs = 1f - p, cover = lerp(from, to, p), corner = lerp(16.dp, 8.dp, p))
        private fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)
        val BAR = 64.dp
        val COVER = 48.dp
        val INSET = 8.dp
    }
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
        if (host.mini != null && host.request != null) translationY = MiniPlayerLayout.at(host.progress).tabs * (size.height + below)
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
    Surface(modifier.graphicsLayer { alpha = MiniPlayerLayout.at(host.progress).bar }
        .anchoredDraggable(host.size, Orientation.Vertical,
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
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
