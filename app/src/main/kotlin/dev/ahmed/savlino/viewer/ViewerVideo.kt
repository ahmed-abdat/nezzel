package dev.ahmed.savlino.viewer

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * One video page: the platform VideoView, its poster until the first frame, and play/pause, seek and mute.
 * Plays only while [current]; swiping away pauses it, and leaving the pager releases the player.
 */
@Composable
internal fun ViewerVideo(item: ViewerItem, current: Boolean) {
    val poster = rememberMediaPicture(item.uri, ThumbnailKind.VIDEO, screenPixels())
    var video by remember { mutableStateOf<VideoView?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var rendering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.durationMillis ?: 0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var muted by rememberSaveable { mutableStateOf(false) }
    val latestCurrent by rememberUpdatedState(current)
    val latestMuted by rememberUpdatedState(muted)

    fun play(on: Boolean) {
        val view = video ?: return
        if (on && player != null) view.start() else view.pause()
        playing = on && player != null
    }
    LaunchedEffect(current, player) { play(current) }
    LaunchedEffect(muted, player) { player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    LaunchedEffect(playing) {
        while (playing) {
            video?.let { position = it.currentPosition.toLong() }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView({ context ->
            VideoView(context).apply {
                setOnPreparedListener { prepared ->
                    prepared.isLooping = true
                    prepared.setVolume(if (latestMuted) 0f else 1f, if (latestMuted) 0f else 1f)
                    duration = prepared.duration.toLong().takeIf { it > 0 } ?: duration
                    player = prepared
                    if (latestCurrent) { start(); playing = true }
                }
                setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true; false }
                setOnErrorListener { _, _, _ -> playing = false; player = null; true }
                setOnClickListener { play(!playing) }
                setVideoURI(item.uri)
                video = this
            }
        }, onRelease = { it.stopPlayback(); video = null; player = null })
        if (!rendering) poster?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }

        // Media controls keep left-to-right time in RTL too, like the phone's own players.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .55f))
            .padding(horizontal = Space.s, vertical = Space.xs)) {
            val end = duration.coerceAtLeast(1L).toFloat()
            val positionLabel = stringResource(R.string.viewer_position)
            Slider(seeking ?: position.coerceAtMost(duration).toFloat(), { seeking = it }, Modifier.fillMaxWidth()
                .semantics { contentDescription = positionLabel }, enabled = duration > 0,
                valueRange = 0f..end, onValueChangeFinished = {
                    seeking?.let { target -> video?.seekTo(target.toInt()); position = target.toLong() }
                    seeking = null
                }, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = .3f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { play(!playing) }, Modifier.size(48.dp)) {
                    if (playing) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.viewer_pause))
                    else Icon(Icons.Default.PlayArrow, stringResource(R.string.viewer_play))
                }
                Text(listOfNotNull(NezzelFormat.duration((seeking?.toLong() ?: position) / 1000),
                    duration.takeIf { it > 0 }?.let { NezzelFormat.duration(it / 1000) }).joinToString(" / "),
                    style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { muted = !muted }, Modifier.size(48.dp)) {
                    Icon(painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                        stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute))
                }
            }
        }
        }
    }
}
