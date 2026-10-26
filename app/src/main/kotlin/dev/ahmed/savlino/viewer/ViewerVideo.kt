package dev.ahmed.savlino.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.playback.*
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.delay

/** Rendering only. Adjacent pages never allocate a player or connect to the audio service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ViewerVideo(item: ViewerItem, current: Boolean, owner: PlaybackCoordinator, onOpenWith: () -> Unit, onClose: () -> Unit) {
    val poster = rememberMediaPicture(item.uri, if (item.isAudio) ThumbnailKind.AUDIO else ThumbnailKind.VIDEO, screenPixels())
    val ownedPlayer by owner.player.collectAsState()
    val ownedState by owner.state.collectAsState()
    val resumedAt by owner.resumedAt.collectAsState()
    val player = ownedPlayer.takeIf { current && owner.item?.uri == item.uri }
    val state = if (current) ownedState else PlaybackState.Idle
    var playing by remember(item.uri) { mutableStateOf(false) }
    var position by remember(item.uri) { mutableLongStateOf(0) }
    var duration by remember(item.uri) { mutableLongStateOf(item.durationMillis ?: 0) }
    var ratio by remember(item.uri) { mutableFloatStateOf(1f) }
    var seeking by remember(item.uri) { mutableStateOf<Float?>(null) }
    var muted by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    var showResume by remember(item.uri, resumedAt) { mutableStateOf(resumedAt > 0) }
    LaunchedEffect(item.uri, resumedAt) { if (resumedAt > 0) { delay(6_000); showResume = false } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(current, lifecycle) { if (current) owner.foreground(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) }
    LaunchedEffect(player) {
        while (player != null) {
            playing = player.playWhenReady
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: item.durationMillis ?: 0
            val size = player.videoSize
            if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            delay(250)
        }
        playing = false
    }
    // The poll above refreshes position; Play/Pause follows the player at once so the button never lags a tap.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player, muted) { if (!item.isAudio) player?.volume = if (muted) 0f else 1f }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (player != null && !item.isAudio && state !is PlaybackState.Failed) {
            val width = minOf(maxWidth, maxHeight * ratio)
            PlayerSurface(player, Modifier.width(width).height(width / ratio.coerceAtLeast(.01f)))
        }
        if (item.isAudio) Box(Modifier.fillMaxWidth(.72f).widthIn(max = 360.dp).aspectRatio(1f).clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
            poster?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(painterResource(R.drawable.ic_music), null, Modifier.size(72.dp), tint = Color.White.copy(alpha = .7f))
        } else if (player == null || state == PlaybackState.Loading || state is PlaybackState.Failed) poster?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (current && state == PlaybackState.Loading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(stringResource(R.string.viewer_loading))
        }
        if (current && state is PlaybackState.Failed) Column(Modifier.background(Color.Black.copy(alpha = .85f)).padding(Space.m),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(when (state.failure) {
                PlaybackFailure.UNAVAILABLE -> R.string.viewer_file_unavailable
                PlaybackFailure.UNSUPPORTED -> R.string.viewer_playback_unsupported
                PlaybackFailure.OTHER -> R.string.viewer_playback_failed
            }))
            TextButton(owner::retry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_retry)) }
            TextButton(onOpenWith, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.saved_open_with)) }
            TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_close)) }
        }
        if (current) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f))
            .padding(horizontal = Space.s, vertical = Space.xs)) {
            if (resumedAt > 0 && showResume) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.viewer_resumed_at, "\u2066${NezzelFormat.duration(resumedAt / 1000)}\u2069"), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(owner::startOver, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_start_over)) }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val positionLabel = stringResource(R.string.viewer_position)
                Slider(seeking ?: position.coerceAtMost(duration.coerceAtLeast(0)).toFloat(), { seeking = it },
                    Modifier.fillMaxWidth().semantics { contentDescription = positionLabel }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = player != null && duration > 0, onValueChangeFinished = { seeking?.let { owner.seek(it.toLong()) }; seeking = null })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (playing) owner.pause() else if (state is PlaybackState.Failed) owner.retry() else owner.play() }, Modifier.size(48.dp)) {
                        if (playing) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.viewer_pause))
                        else Icon(Icons.Default.PlayArrow, stringResource(R.string.viewer_play))
                    }
                    Text(listOfNotNull(NezzelFormat.duration((seeking?.toLong() ?: position) / 1000), duration.takeIf { it > 0 }?.let { NezzelFormat.duration(it / 1000) }).joinToString(" / "),
                        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                    Spacer(Modifier.weight(1f))
                    if (!item.isAudio) IconButton(onClick = { muted = !muted }, Modifier.size(48.dp)) {
                        Icon(painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute))
                    }
                }
            }
        }
    }
}
