package dev.ahmed.savlino.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.ahmed.savlino.R
import dev.ahmed.savlino.engine.PreviewStream
import dev.ahmed.savlino.locale.NezzelFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The poster's play badge: a dark disc with a white triangle, the shape every video app uses for "this plays". */
@Composable
internal fun PlayBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(size * .62f), tint = Color.White)
    }
}

/** YouTube-style duration chip on the poster's end corner. */
@Composable
internal fun DurationChip(seconds: Long, modifier: Modifier = Modifier) {
    Text(NezzelFormat.isolate(NezzelFormat.duration(seconds)), modifier
        .clip(MaterialTheme.shapes.extraSmall).background(Color.Black.copy(alpha = .75f)).padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = Color.White)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun previewPlayer(context: Context, stream: PreviewStream): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.headers)
        .apply { stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { setUserAgent(it.value) } }
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
    val factory = ProgressiveMediaSource.Factory(http)
    val main = factory.createMediaSource(MediaItem.fromUri(stream.url))
    val source = stream.audioUrl?.let { MergingMediaSource(main, factory.createMediaSource(MediaItem.fromUri(it))) } ?: main
    return ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true).build().apply {
            setMediaSource(source); prepare(); playWhenReady = true
        }
}

/**
 * Plays [stream] in place, full width, before anything is saved. Poster until the first picture, a spinner while
 * buffering, tap for play/pause and the seek bar; [onClose] folds it back into the header row.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PreviewPlayer(stream: PreviewStream, poster: Bitmap?, onClose: () -> Unit, onResult: (failed: Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember(stream) { previewPlayer(context, stream) }
    val report by rememberUpdatedState(onResult)
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var ratio by remember { mutableFloatStateOf(16f / 9) }
    DisposableEffect(player) {
        var counted = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) { duration = player.duration.coerceAtLeast(0); if (!counted) { counted = true; report(false) } }
                if (ended) controls = true
            }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(.5f, 2.4f)
            }
            override fun onPlayerError(error: PlaybackException) { failed = true; buffering = false; if (!counted) { counted = true; report(true) } }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    // Home, lock or another app pauses the preview; it never plays in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) { while (isActive) { position = player.currentPosition; delay(250) } }
    // Controls hide 2.5 s after a tap while playing; paused or ended keeps them.
    LaunchedEffect(controls, playing, dragging) { if (controls && playing && dragging == null) { delay(2_500); controls = false } }

    val description = stringResource(R.string.preview_player)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(1f)).clip(MaterialTheme.shapes.medium).background(Color.Black)
        .semantics { contentDescription = description }.testTag("preview_player")
        .clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
        contentAlignment = Alignment.Center) {
        if (stream.hasVideo) PlayerSurface(player, Modifier.fillMaxSize().then(if (playing) Modifier.keepScreenOn() else Modifier),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
        if (poster != null && (!firstFrame || !stream.hasVideo)) Image(poster.asImageBitmap(), null, Modifier.fillMaxSize(),
            contentScale = if (stream.hasVideo) ContentScale.Crop else ContentScale.Fit)
        if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color.Black.copy(alpha = .7f)).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_error), null, tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.preview_failed), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        else if (buffering && !controls) CircularProgressIndicator(Modifier.size(44.dp), color = Color.White, strokeWidth = 3.dp)
        AnimatedVisibility(controls && !failed, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f))) {
                IconButton(onClick = onClose, Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, stringResource(R.string.preview_close), tint = Color.White)
                }
                val toggle = stringResource(if (playing) R.string.preview_pause else R.string.preview_play)
                Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f))
                    .clickable(onClickLabel = toggle) {
                        when { ended -> { player.seekTo(0); player.play() }; playing -> player.pause(); else -> player.play() }
                    }.semantics { contentDescription = toggle }, contentAlignment = Alignment.Center) {
                    when {
                        ended -> Icon(Icons.Default.Refresh, null, Modifier.size(36.dp), tint = Color.White)
                        playing -> Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(36.dp), tint = Color.White)
                        else -> Icon(Icons.Default.PlayArrow, null, Modifier.size(40.dp), tint = Color.White)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                    .padding(horizontal = 12.dp)) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: position
                    Text(NezzelFormat.isolate("${NezzelFormat.durationMillis(shown)} / ${NezzelFormat.durationMillis(duration)}"),
                        color = Color.White, style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                    Slider(value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it }, enabled = duration > 0,
                        onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
                        modifier = Modifier.fillMaxWidth().height(32.dp).testTag("preview_seek"),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = .3f)))
                }
            }
        }
        // While controls are hidden, a thin progress line along the bottom, like the big apps.
        if (!controls && !failed && duration > 0) LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = .25f), gapSize = 0.dp, drawStopIndicator = {})
    }
}
