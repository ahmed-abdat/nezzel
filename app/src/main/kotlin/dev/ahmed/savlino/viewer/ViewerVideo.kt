package dev.ahmed.savlino.viewer

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.playback.*
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.delay
import androidx.media3.session.R as Media3

/** A double tap's brief answer: which way it skipped, or null when the file can't skip yet. */
private data class Skip(val forward: Boolean?, val id: Long = System.nanoTime())

/** Rendering only. Adjacent pages never allocate a player or connect to the audio service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ViewerVideo(item: ViewerItem, current: Boolean, owner: PlaybackCoordinator, controls: ViewerControls, language: String,
                         fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onOpenWith: () -> Unit, onClose: () -> Unit) {
    val usage = (LocalPlaybackEnvironment.current ?: PlaybackEnvironment.get(LocalContext.current)).usage
    val reduced = LocalReducedMotion.current
    val colors = viewerColors()
    val poster = rememberMediaPicture(item.uri, if (item.isAudio) ThumbnailKind.AUDIO else ThumbnailKind.VIDEO, screenPixels())
    val ownedPlayer by owner.player.collectAsState()
    val ownedState by owner.state.collectAsState()
    val resumedAt by owner.resumedAt.collectAsState()
    val player = ownedPlayer.takeIf { current && owner.item?.uri == item.uri }
    val state = if (current) ownedState else PlaybackState.Idle
    val uri = item.uri.toString()
    var playing by remember(item.uri) { mutableStateOf(false) }
    // Actually moving: not buffering, not paused. Only this time counts toward hiding the controls.
    var running by remember(item.uri) { mutableStateOf(false) }
    var position by remember(item.uri) { mutableLongStateOf(0) }
    var duration by remember(item.uri) { mutableLongStateOf(item.durationMillis ?: 0) }
    var ratio by remember(item.uri) { mutableFloatStateOf(1f) }
    var wide by remember(item.uri) { mutableStateOf(false) }
    var seekable by remember(item.uri) { mutableStateOf(false) }
    var speed by remember(item.uri) { mutableFloatStateOf(1f) }
    var speedFailed by remember(item.uri) { mutableLongStateOf(0) }
    var skip by remember(item.uri) { mutableStateOf<Skip?>(null) }
    var seeking by remember(item.uri) { mutableStateOf<Float?>(null) }
    var muted by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    var showResume by remember(item.uri, resumedAt) { mutableStateOf(resumedAt > 0) }
    val exploring = rememberTouchExploration()
    LaunchedEffect(item.uri, resumedAt) { if (resumedAt > 0) { delay(6_000); showResume = false } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(current, lifecycle) { if (current) owner.foreground(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) }
    fun size(value: VideoSize) {
        if (value.width > 0 && value.height > 0) ratio = value.width * value.pixelWidthHeightRatio / value.height
        wide = !item.isAudio && isWide(value)
    }
    fun read(player: Player) {
        playing = player.playWhenReady; running = player.isPlaying
        position = player.currentPosition.coerceAtLeast(0)
        duration = player.duration.takeIf { it > 0 } ?: item.durationMillis ?: 0
        seekable = ViewerSeek.seekable(player); speed = player.playbackParameters.speed
        size(player.videoSize)
    }
    LaunchedEffect(player) {
        while (player != null) { read(player); delay(250) }
        playing = false; running = false; seekable = false
    }
    // The poll above refreshes position; these follow the player at once so buttons never lag a tap.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onIsPlayingChanged(isPlaying: Boolean) { running = isPlaying }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { speed = parameters.speed }
            override fun onVideoSizeChanged(videoSize: VideoSize) { size(videoSize) }
            override fun onEvents(player: Player, events: Player.Events) { seekable = ViewerSeek.seekable(player) }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    // Speed belongs to one file: a recreated viewer keeps it, any other file starts at 1×.
    LaunchedEffect(player) {
        val attached = player ?: return@LaunchedEffect
        val wanted = controls.speedOf(uri)
        if (controls.speedFor != uri) controls.choose(uri, 1f)
        ViewerSpeed.apply(attached, wanted)
        speed = attached.playbackParameters.speed
    }
    LaunchedEffect(player, muted) { if (!item.isAudio) player?.volume = if (muted) 0f else 1f }
    // Paused, ended or failed: the controls come back. Playing: they leave after 3 s of actual playback.
    LaunchedEffect(current, playing, state) { if (current && (!playing || state == PlaybackState.Ended || state is PlaybackState.Failed)) controls.show() }
    val canHide = current && running && !controls.frozen && !exploring && state == PlaybackState.Ready
    LaunchedEffect(controls.shown, controls.touches, canHide) { if (controls.shown && canHide) { delay(ViewerControls.HIDE_AFTER); controls.hide() } }
    LaunchedEffect(state, fullscreen) { if (fullscreen && state is PlaybackState.Failed) onFullscreen(false) }
    LaunchedEffect(skip) { skip?.let { delay(if (it.forward == null) 1_500 else 600); skip = null } }

    fun seekBy(forward: Boolean, gesture: Boolean) {
        controls.touched()
        val target = ViewerSeek.target(player, forward)
        if (target == null) { skip = Skip(null); return }
        owner.seek(target); position = target
        usage.count(UsageKeys.PLAYER_SEEK)
        if (gesture) skip = Skip(forward)
    }
    fun chooseSpeed(value: Float) {
        val attached = player ?: return
        val before = speed
        if (ViewerSpeed.apply(attached, value)) {
            controls.choose(uri, value); speed = value
            if (!ViewerSpeed.same(before, value)) usage.count(UsageKeys.PLAYER_SPEED)
        } else { speed = attached.playbackParameters.speed; speedFailed = System.nanoTime() }
    }
    LaunchedEffect(speedFailed) { if (speedFailed != 0L) { delay(3_000); speedFailed = 0 } }

    val gesture by rememberUpdatedState(::seekBy)
    val direction = LocalLayoutDirection.current
    val showLabel = stringResource(R.string.viewer_show_controls)
    val hideLabel = stringResource(R.string.viewer_hide_controls)
    val backLabel = stringResource(R.string.viewer_seek_back_10)
    val forwardLabel = stringResource(R.string.viewer_seek_forward_10)
    BoxWithConstraints(Modifier.fillMaxSize()
        // Physical halves on purpose: the timeline is LTR, so left is always back. A pager drag cancels the tap.
        .pointerInput(current) {
            if (current) detectTapGestures(onTap = { controls.toggle() }, onDoubleTap = { gesture(ViewerSeek.forward(it.x, size.width), true) })
        }
        .semantics {
            if (current) customActions = listOfNotNull(
                CustomAccessibilityAction(if (controls.shown) hideLabel else showLabel) { controls.toggle(); true },
                CustomAccessibilityAction(backLabel) { seekBy(false, false); true }.takeIf { seekable },
                CustomAccessibilityAction(forwardLabel) { seekBy(true, false); true }.takeIf { seekable })
        }, contentAlignment = Alignment.Center) {
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
        // Video keeps its centre buttons through loading and buffering, with a ring on Play; the plain spinner shows only without them.
        val centre = current && !item.isAudio && player != null && state !is PlaybackState.Failed
        if (current && state == PlaybackState.Loading && !(centre && controls.shown)) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent)
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
        if (current) SkipFeedback(skip, reduced)
        val togglePlay = {
            controls.touched()
            if (playing) owner.pause() else if (state is PlaybackState.Failed) owner.retry() else owner.play()
        }
        // Gone from the tree the moment they hide: a fading button can't be reached by TalkBack or focus.
        val hidden = if (controls.shown) Modifier else Modifier.clearAndSetSemantics {}
        // Video: the familiar big Back · Play · Forward in the middle, on a light dim so it reads over bright frames.
        // The dim takes no touches, so a tap anywhere else still reaches the canvas.
        if (centre)
            AnimatedVisibility(controls.shown, Modifier.matchParentSize(), enter = fadeIn(easeOut(reduced)), exit = fadeOut(easeOut(reduced))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)).then(hidden)
                    .onFocusChanged { controls.focus("centre", it.hasFocus) }, contentAlignment = Alignment.Center) {
                    PlayCluster(true, playing, state == PlaybackState.Loading, seekable, colors, { seekBy(it, false) }, togglePlay)
                }
            }
        if (current) AnimatedVisibility(controls.shown, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(easeOut(reduced)), exit = fadeOut(easeOut(reduced))) {
            Column(Modifier.fillMaxWidth().then(hidden)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .onFocusChanged { controls.focus("bottom", it.hasFocus) }
                // Taps on the bar's own background keep it up rather than falling through to the canvas.
                .pointerInput(Unit) { detectTapGestures { controls.touched() } }
                .padding(start = Space.s, end = Space.s, top = Space.xl, bottom = Space.xs)) {
                if (resumedAt > 0 && showResume) Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viewer_resumed_at, "\u2066${NezzelFormat.duration(resumedAt / 1000)}\u2069"), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton({ controls.touched(); owner.startOver() }, Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) { Text(stringResource(R.string.viewer_start_over)) }
                }
                if (speedFailed != 0L) Text(stringResource(R.string.viewer_speed_failed), Modifier.padding(horizontal = Space.s, vertical = Space.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                // Media timelines read left to right in every language; menus keep the reader's own direction.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val time = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr)
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(NezzelFormat.duration((seeking?.toLong() ?: position) / 1000), style = time)
                        SeekBar(seeking ?: position.coerceAtMost(duration.coerceAtLeast(0)).toFloat(), duration, seekable, colors,
                            Modifier.weight(1f).padding(horizontal = Space.s),
                            onChange = { seeking = it; controls.scrubbing = true },
                            onDone = { seeking?.let { owner.seek(it.toLong()) }; seeking = null; controls.scrubbing = false; controls.touched() })
                        Text(duration.takeIf { it > 0 }?.let { NezzelFormat.duration(it / 1000) } ?: "", style = time, color = Color.White.copy(alpha = .72f))
                    }
                    Box(Modifier.fillMaxWidth().height(if (item.isAudio) 72.dp else 52.dp)) {
                        SpeedMenu(speed, player != null, controls, colors, language, direction, Modifier.align(Alignment.CenterStart), ::chooseSpeed)
                        if (item.isAudio) PlayCluster(false, playing, state == PlaybackState.Loading, seekable, colors, { seekBy(it, false) }, togglePlay, Modifier.align(Alignment.Center))
                        Row(Modifier.align(Alignment.CenterEnd)) {
                            VolumeButton(muted, !item.isAudio, controls, colors, direction) { muted = it; controls.touched() }
                            if (wide || fullscreen) IconButton(onClick = { controls.touched(); onFullscreen(!fullscreen) }, Modifier.size(48.dp)) {
                                Icon(if (fullscreen) FullscreenExitIcon else FullscreenIcon,
                                    stringResource(if (fullscreen) R.string.viewer_exit_fullscreen else R.string.viewer_fullscreen))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Back 10 · Play/Pause · Forward 10. Big and centred on video, compact under the cover for audio. Always LTR. */
@Composable
private fun PlayCluster(big: Boolean, playing: Boolean, loading: Boolean, seekable: Boolean, colors: ViewerColors, onSkip: (Boolean) -> Unit,
                        onToggle: () -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (big) 40.dp else Space.m), verticalAlignment = Alignment.CenterVertically) {
            SkipButton(false, seekable, big) { onSkip(false) }
            val size = if (big) 72.dp else 60.dp
            Box(contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(size + 10.dp), color = Color.White, strokeWidth = 3.dp)
                FilledIconButton(onToggle, Modifier.size(size),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                    val glyph = Modifier.size(if (big) 36.dp else 30.dp)
                    if (playing) Icon(painterResource(Media3.drawable.media3_icon_pause), stringResource(R.string.viewer_pause), glyph)
                    else Icon(painterResource(Media3.drawable.media3_icon_play), stringResource(R.string.viewer_play), glyph)
                }
            }
            SkipButton(true, seekable, big) { onSkip(true) }
        }
    }
}

/** Mute for video plus the phone's media volume, the same level the volume keys move. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeButton(muted: Boolean, canMute: Boolean, controls: ViewerControls, colors: ViewerColors, direction: LayoutDirection,
                         onMute: (Boolean) -> Unit) {
    val context = LocalContext.current
    val volume = remember(context) { DeviceVolume(context) }
    var level by remember { mutableIntStateOf(volume.level) }
    // Follows the volume keys and other apps: Android reports volume changes as system setting changes.
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) { level = volume.level } }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) level = volume.level }
    val silent = muted || volume.adjustable && level == 0
    val title = stringResource(R.string.viewer_volume)
    val mutedLabel = stringResource(R.string.viewer_volume_muted)
    val icon = painterResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    if (!volume.adjustable) {
        if (canMute) IconButton({ onMute(!muted) }, Modifier.size(48.dp)) {
            Icon(icon, stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute))
        }
        return
    }
    Box {
        IconButton({ controls.touched(); controls.volumeOpen = true }, Modifier.size(48.dp)
            .semantics { if (silent) stateDescription = mutedLabel }) { Icon(icon, title) }
        DropdownMenu(controls.volumeOpen, { controls.volumeOpen = false; controls.touched() },
            Modifier.semantics { paneTitle = title }, containerColor = colors.menu) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Row(Modifier.width(272.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (canMute) IconButton({ onMute(!muted) }, Modifier.size(48.dp)) {
                        Icon(painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                            stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute), tint = if (muted) colors.accent else colors.onMenu)
                    } else Icon(painterResource(R.drawable.ic_volume_up), null, Modifier.padding(Space.m), tint = colors.onMenu)
                    val slider = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.onMenu.copy(alpha = .24f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
                    Slider(level.toFloat(), { value ->
                        val wanted = kotlin.math.round(value).toInt()
                        if (wanted != level && volume.set(wanted)) level = volume.level
                        if (muted && wanted > 0) onMute(false)
                    }, Modifier.weight(1f).padding(end = Space.s).semantics { contentDescription = title },
                        valueRange = 0f..volume.max.toFloat(), steps = (volume.max - 1).coerceAtLeast(0), colors = slider,
                        thumb = { Box(Modifier.size(16.dp).background(colors.accent, CircleShape)) },
                        track = { state -> SliderDefaults.Track(state, Modifier.height(4.dp), colors = slider, drawStopIndicator = null, thumbTrackGapSize = 0.dp) })
                }
            }
        }
    }
}

@Composable
private fun SkipButton(forward: Boolean, enabled: Boolean, big: Boolean, onClick: () -> Unit) {
    val circle = if (big) Color.Black.copy(alpha = .4f) else Color.Transparent
    IconButton(onClick, Modifier.size(if (big) 56.dp else 48.dp), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = circle, contentColor = Color.White,
            disabledContainerColor = circle, disabledContentColor = Color.White.copy(alpha = .38f))) {
        Icon(painterResource(if (forward) Media3.drawable.media3_icon_skip_forward_10 else Media3.drawable.media3_icon_skip_back_10),
            stringResource(if (forward) R.string.viewer_seek_forward_10 else R.string.viewer_seek_back_10), Modifier.size(if (big) 32.dp else 24.dp))
    }
}

/** A thin teal line with a round thumb: progress reads at a glance on black, played part bright, the rest dim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(value: Float, duration: Long, enabled: Boolean, colors: ViewerColors, modifier: Modifier,
                    onChange: (Float) -> Unit, onDone: () -> Unit) {
    val label = stringResource(R.string.viewer_position)
    val slider = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = Color.White.copy(alpha = .24f),
        disabledThumbColor = Color.White.copy(alpha = .6f), disabledActiveTrackColor = Color.White.copy(alpha = .6f),
        disabledInactiveTrackColor = Color.White.copy(alpha = .24f))
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(value, onChange, modifier.semantics { contentDescription = label }, enabled = enabled,
        valueRange = 0f..duration.coerceAtLeast(1).toFloat(), onValueChangeFinished = onDone, colors = slider, interactionSource = interaction,
        thumb = { Box(Modifier.size(16.dp).background(if (enabled) colors.accent else Color.White.copy(alpha = .6f), CircleShape)) },
        track = { state -> SliderDefaults.Track(state, Modifier.height(4.dp), enabled = enabled, colors = slider,
            drawStopIndicator = null, thumbTrackGapSize = 0.dp) })
}

@Composable
private fun SpeedMenu(speed: Float, enabled: Boolean, controls: ViewerControls, colors: ViewerColors, language: String,
                      direction: LayoutDirection, modifier: Modifier, onChoose: (Float) -> Unit) {
    val title = stringResource(R.string.viewer_speed)
    val normal = stringResource(R.string.viewer_speed_normal)
    val value = stringResource(R.string.viewer_speed_value, ViewerSpeed.number(speed, language))
    Box(modifier) {
        TextButton({ controls.touched(); controls.menuOpen = true }, Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .semantics { contentDescription = title; stateDescription = if (ViewerSpeed.same(speed, 1f)) normal else value },
            enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .38f))) {
            Text(value, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
        }
        DropdownMenu(controls.menuOpen, { controls.menuOpen = false; controls.touched() },
            Modifier.semantics { paneTitle = title }, containerColor = colors.menu) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Text(title, Modifier.padding(horizontal = Space.l, vertical = Space.s), style = MaterialTheme.typography.labelLarge,
                    color = colors.onMenu.copy(alpha = .72f))
                ViewerSpeed.choices.forEach { choice ->
                    val selected = ViewerSpeed.same(choice, speed)
                    DropdownMenuItem(
                        text = { Text(if (choice == 1f) normal else ViewerSpeed.label(stringResource(R.string.viewer_speed_value, ViewerSpeed.number(choice, language))),
                            fontWeight = if (selected) FontWeight.SemiBold else null) },
                        onClick = { controls.menuOpen = false; controls.touched(); onChoose(choice) },
                        modifier = Modifier.semantics { this.selected = selected },
                        trailingIcon = if (selected) { { Icon(Icons.Default.Check, null) } } else null,
                        colors = MenuDefaults.itemColors(textColor = if (selected) colors.accent else colors.onMenu, trailingIconColor = colors.accent))
                }
            }
        }
    }
}

/** Where the double tap landed: a soft circle on that physical half; reduced motion = shown and gone, no fade. */
@Composable
private fun BoxScope.SkipFeedback(skip: Skip?, reduced: Boolean) {
    for (forward in listOf(false, true)) {
        AnimatedVisibility(skip?.forward == forward, Modifier.fillMaxHeight().fillMaxWidth(.5f)
            .align(if (forward) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft),
            enter = fadeIn(easeOut(reduced, 120)), exit = fadeOut(easeOut(reduced, 240))) {
            Box(contentAlignment = Alignment.Center) {
                // Light and see-through, so it reads on dark frames and bright ones alike.
                Box(Modifier.size(88.dp).background(Color.White.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (forward) Media3.drawable.media3_icon_skip_forward_10 else Media3.drawable.media3_icon_skip_back_10),
                        null, Modifier.size(40.dp), tint = Color.White)
                }
            }
        }
    }
    AnimatedVisibility(skip != null && skip.forward == null, Modifier.align(Alignment.Center),
        enter = fadeIn(easeOut(reduced, 120)), exit = fadeOut(easeOut(reduced, 240))) {
        Text(stringResource(R.string.viewer_seek_unavailable), Modifier.background(Color.Black.copy(alpha = .72f), RoundedCornerShape(50))
            .padding(horizontal = Space.l, vertical = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium)
    }
}
