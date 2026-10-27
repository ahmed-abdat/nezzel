package dev.ahmed.savlino.viewer

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    // Once a file has seeked, a passing gap (a relayout, a buffer) never calls it unseekable; a press made then waits a moment instead.
    var everSeekable by remember(item.uri) { mutableStateOf(false) }
    var queued by remember(item.uri) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var speed by remember(item.uri) { mutableFloatStateOf(1f) }
    var speedFailed by remember(item.uri) { mutableLongStateOf(0) }
    var skip by remember(item.uri) { mutableStateOf<Skip?>(null) }
    var seeking by remember(item.uri) { mutableStateOf<Float?>(null) }
    // Where a drag let go: the thumb and times stay there until the player gets close, so they never snap back first.
    var held by remember(item.uri) { mutableStateOf<Long?>(null) }
    // Short seek buffers pass without a ring; it shows only once loading lasts.
    var slow by remember(item.uri) { mutableStateOf(false) }
    var muted by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    var showResume by remember(item.uri, resumedAt) { mutableStateOf(resumedAt > 0) }
    val exploring = rememberTouchExploration()
    val volume = rememberVolumeLevel()
    LaunchedEffect(item.uri, resumedAt) { if (resumedAt > 0) { delay(6_000); showResume = false } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(current, lifecycle) { if (current) owner.foreground(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) }
    fun size(value: VideoSize) {
        if (value.width > 0 && value.height > 0) ratio = value.width * value.pixelWidthHeightRatio / value.height
        wide = !item.isAudio && isWide(value)
    }
    fun seekableNow(player: Player) { seekable = ViewerSeek.seekable(player); if (seekable) everSeekable = true }
    fun read(player: Player) {
        playing = player.playWhenReady; running = player.isPlaying
        position = player.currentPosition.coerceAtLeast(0)
        held?.let { if (kotlin.math.abs(position - it) < HELD_CLOSE) held = null }
        duration = player.duration.takeIf { it > 0 } ?: item.durationMillis ?: 0
        seekableNow(player); speed = player.playbackParameters.speed
        size(player.videoSize)
    }
    // In the bar the page isn't shown: no ticking, the bar follows the player itself.
    val host = LocalViewerHost.current
    val hidden = host?.collapsed == true
    LaunchedEffect(player, hidden) {
        if (hidden && player != null) return@LaunchedEffect
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
            override fun onEvents(player: Player, events: Player.Events) { seekableNow(player) }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener); ViewerScrub.set(player, false) }
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
    // Audio controls stay put: there is no picture under them to uncover.
    LaunchedEffect(current, controls.shown) { if (current && item.isAudio && !controls.shown) controls.show() }
    val canHide = current && !item.isAudio && running && !controls.frozen && !exploring && state == PlaybackState.Ready
    LaunchedEffect(controls.shown, controls.touches, canHide) { if (controls.shown && canHide) { delay(ViewerControls.HIDE_AFTER); controls.hide() } }
    LaunchedEffect(state, fullscreen) { if (fullscreen && state is PlaybackState.Failed) onFullscreen(false) }
    LaunchedEffect(held) { if (held != null) { delay(HELD_MAX); held = null } }
    LaunchedEffect(state, held) { if (state is PlaybackState.Failed) held = null }
    LaunchedEffect(state == PlaybackState.Loading, held) {
        slow = false
        if (state == PlaybackState.Loading && held == null) { delay(RING_AFTER); slow = true }
    }
    LaunchedEffect(skip) { skip?.let { delay(if (it.forward == null) 1_500 else 600); skip = null } }

    fun skipBy(forward: Boolean, gesture: Boolean): Boolean {
        // The coordinator's player right now, not the one this frame was drawn with.
        val live = owner.player.value.takeIf { owner.item?.uri == item.uri }
        val target = ViewerSeek.target(live, forward) ?: return false
        owner.seek(target); position = target; held = target
        usage.count(UsageKeys.PLAYER_SEEK)
        if (gesture) skip = Skip(forward)
        return true
    }
    fun seekBy(forward: Boolean, gesture: Boolean) {
        controls.touched()
        if (skipBy(forward, gesture)) return
        // Only a file that is ready and has never seeked is called unseekable. Read now, like the player: this may run from an old frame.
        val ready = owner.item?.uri == item.uri && owner.state.value == PlaybackState.Ready
        if (ready && !everSeekable) skip = Skip(null) else queued = forward to gesture
    }
    val latestSkip by rememberUpdatedState(::skipBy)
    // A press waiting out a gap belongs to this page: leaving it drops the press.
    LaunchedEffect(queued, current) {
        if (!current) { queued = null; return@LaunchedEffect }
        val (forward, gesture) = queued ?: return@LaunchedEffect
        withTimeoutOrNull(QUEUE_MAX) { while (!latestSkip(forward, gesture)) delay(100) }
        queued = null
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
    val togglePlay = {
        controls.touched()
        if (playing) owner.pause() else if (state is PlaybackState.Failed) owner.retry() else owner.play()
    }
    val failure = (state as? PlaybackState.Failed)?.failure?.takeIf { current }
    val loading = state == PlaybackState.Loading
    val ring = loading && slow
    val resume: @Composable (Modifier) -> Unit = { modifier ->
        if (resumedAt > 0 && showResume) Row(modifier.padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.viewer_resumed_at, "\u2066${NezzelFormat.duration(resumedAt / 1000)}\u2069"), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ controls.touched(); owner.startOver() }, Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) { Text(stringResource(R.string.viewer_start_over)) }
        }
    }
    // Landscape puts the resume note in the button row instead, so the bar stays short and clear of the centre buttons.
    val notes: @Composable (Boolean) -> Unit = { inline ->
        if (!inline) resume(Modifier.fillMaxWidth())
        if (speedFailed != 0L) Text(stringResource(R.string.viewer_speed_failed), Modifier.padding(horizontal = Space.s, vertical = Space.xs)
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    }
    // Media timelines read left to right in every language; menus keep the reader's own direction.
    val seekRow: @Composable () -> Unit = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val time = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr)
            Row(Modifier.fillMaxWidth().keepsDrags(host).padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                val shown = seeking?.toLong() ?: held ?: position
                Text(NezzelFormat.duration(shown / 1000), style = time)
                SeekBar(shown.coerceAtMost(duration.coerceAtLeast(0)).toFloat(), duration, seekable, colors,
                    Modifier.weight(1f).padding(horizontal = Space.s),
                    onChange = {
                        if (seeking == null) ViewerScrub.set(player, true)
                        seeking = it; controls.scrubbing = true; ViewerScrub.move(player, it.toLong())
                    },
                    onDone = { ViewerScrub.set(player, false); seeking?.let { held = it.toLong(); owner.seek(it.toLong()) }; seeking = null; controls.scrubbing = false; controls.touched() })
                Text(duration.takeIf { it > 0 }?.let { NezzelFormat.duration(it / 1000) } ?: "", style = time, color = Color.White.copy(alpha = .72f))
            }
        }
    }
    if (item.isAudio) {
        val context = LocalContext.current
        val fallback = stringResource(R.string.playback_audio)
        val title by produceState<String?>(null, item.uri) { value = audioTitle(context, item.uri) ?: fallback }
        // Music: the cover, the file's name and controls that stay put. Nothing to uncover, so nothing hides.
        // Shrinking into the bar: the name and controls fade as they follow the finger down, the cover flies to the bar.
        val details: @Composable () -> Unit = {
            Column(Modifier.widthIn(max = 480.dp).graphicsLayer {
                val p = host?.progress ?: 0f
                alpha = MiniPlayerLayout.at(p).title; translationY = p * (host?.travel ?: 0f)
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = Space.m).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.l))
                if (failure != null) FailureCard(failure, owner::retry, onOpenWith, onClose)
                else {
                    notes(false)
                    seekRow()
                    PlayCluster(true, playing, ring, seekable, colors, { seekBy(it, false) }, togglePlay, Modifier.padding(vertical = Space.s))
                    AnimatedContent(controls.volumeOpen && volume.device.adjustable, Modifier.fillMaxWidth().height(52.dp),
                        transitionSpec = { fadeIn(easeOut(reduced)) togetherWith fadeOut(easeOut(reduced)) }, label = "volume") { open ->
                        if (open) VolumePanel(muted, false, volume, controls, colors) { muted = it; controls.touched() }
                        else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            SpeedMenu(speed, player != null, controls, colors, language, direction, Modifier, ::chooseSpeed)
                            Spacer(Modifier.weight(1f))
                            VolumeButton(muted, false, volume, controls) { muted = it; controls.touched() }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controls.volumeOpen = false } }.padding(top = TOP_BAR)) {
            if (maxWidth > maxHeight) Row(Modifier.fillMaxSize().padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                AudioCover(poster, host.takeIf { current }, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = Space.l), contentAlignment = Alignment.Center) {
                    Box(Modifier.keepsDrags(host).verticalScroll(rememberScrollState())) { details() }
                }
            } else Column(Modifier.fillMaxSize().padding(horizontal = Space.l, vertical = Space.m),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                AudioCover(poster, host.takeIf { current }, Modifier.widthIn(max = 360.dp).weight(1f, fill = false).aspectRatio(1f))
                Spacer(Modifier.height(Space.l))
                details()
            }
        }
    } else BoxWithConstraints(Modifier.fillMaxSize()
        // Physical halves on purpose: the timeline is LTR, so left is always back. A pager drag cancels the tap.
        .pointerInput(current) {
            if (current) detectTapGestures(onTap = { if (controls.volumeOpen) { controls.volumeOpen = false; controls.touched() } else controls.toggle() }, onDoubleTap = { gesture(ViewerSeek.forward(it.x, size.width), true) })
        }
        .semantics {
            if (current) customActions = listOfNotNull(
                CustomAccessibilityAction(if (controls.shown) hideLabel else showLabel) { controls.toggle(); true },
                CustomAccessibilityAction(backLabel) { seekBy(false, false); true }.takeIf { seekable },
                CustomAccessibilityAction(forwardLabel) { seekBy(true, false); true }.takeIf { seekable })
        }, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        if (player != null && failure == null) {
            val width = minOf(maxWidth, maxHeight * ratio)
            PlayerSurface(player, Modifier.width(width).height(width / ratio.coerceAtLeast(.01f)))
        }
        if (player == null || loading || failure != null) poster?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        // Video keeps its centre buttons through loading and buffering, with a ring on Play; the plain spinner shows only without them.
        val centre = current && player != null && failure == null
        if (current && ring && !(centre && controls.shown)) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent)
            Text(stringResource(R.string.viewer_loading))
        }
        if (failure != null) FailureCard(failure, owner::retry, onOpenWith, onClose, Modifier.padding(Space.l))
        if (current) SkipFeedback(skip, reduced)
        // Gone from the tree the moment they hide: a fading button can't be reached by TalkBack or focus.
        val hidden = if (controls.shown) Modifier else Modifier.clearAndSetSemantics {}
        // The familiar big Back · Play · Forward in the middle, on a light dim so it reads over bright frames.
        // The dim takes no touches, so a tap anywhere else still reaches the canvas.
        if (centre)
            AnimatedVisibility(controls.shown, Modifier.matchParentSize(), enter = fadeIn(easeOut(reduced)), exit = fadeOut(easeOut(reduced))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)).then(hidden)
                    .onFocusChanged { controls.focus("centre", it.hasFocus) }, contentAlignment = Alignment.Center) {
                    PlayCluster(true, playing, ring, seekable, colors, { seekBy(it, false) }, togglePlay)
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
                notes(landscape)
                seekRow()
                AnimatedContent(controls.volumeOpen && volume.device.adjustable, Modifier.fillMaxWidth().height(52.dp),
                    transitionSpec = { fadeIn(easeOut(reduced)) togetherWith fadeOut(easeOut(reduced)) }, label = "volume") { open ->
                    if (open) VolumePanel(muted, true, volume, controls, colors) { muted = it; controls.touched() }
                    else Box(Modifier.fillMaxSize()) {
                        SpeedMenu(speed, player != null, controls, colors, language, direction, Modifier.align(Alignment.CenterStart), ::chooseSpeed)
                        if (landscape) resume(Modifier.align(Alignment.Center).padding(horizontal = 112.dp))
                        Row(Modifier.align(Alignment.CenterEnd)) {
                            VolumeButton(muted, true, volume, controls) { muted = it; controls.touched() }
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

/** The file's own cover, or the library's music tile, rounded like the app's cards. */
@Composable
private fun AudioCover(poster: Bitmap?, host: ViewerHostState?, modifier: Modifier) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Surface(modifier.then(if (host == null) Modifier else Modifier.onPlaced { bounds = it.boundsInRoot() }.graphicsLayer {
        val p = host.progress
        val slot = host.slot
        if (p > 0f && slot != null && bounds.width > 0f) {
            // Mapped onto the bar's own cover: at 1 it sits exactly where the bar draws it.
            val target = slot.cover(MiniPlayerLayout.INSET.toPx(), MiniPlayerLayout.COVER.toPx())
            val layout = MiniPlayerLayout.at(p, bounds, target)
            val scale = layout.cover.width / bounds.width
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale; scaleY = scale
            translationX = layout.cover.left - bounds.left; translationY = layout.cover.top - bounds.top
            shape = RoundedCornerShape(layout.corner / scale); clip = true
        }
    }), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        if (poster != null) Image(poster.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_music), null, Modifier.fillMaxSize(.3f)) }
    }
}

/** Why it stopped and what to do next, on the theme's own surface so it reads in light and dark alike. */
@Composable
private fun FailureCard(failure: PlaybackFailure, onRetry: () -> Unit, onOpenWith: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 360.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(when (failure) {
                PlaybackFailure.UNAVAILABLE -> R.string.viewer_file_unavailable
                PlaybackFailure.UNSUPPORTED -> R.string.viewer_playback_unsupported
                PlaybackFailure.OTHER -> R.string.viewer_playback_failed
            }), Modifier.padding(bottom = Space.s).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onRetry, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_retry)) }
            OutlinedButton(onOpenWith, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.saved_open_with)) }
            TextButton(onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.viewer_close)) }
        }
    }
}

private const val HELD_CLOSE = 1_000L
private const val HELD_MAX = 1_500L
private const val RING_AFTER = 500L
private const val QUEUE_MAX = 1_500L

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

/** The phone's media volume as the viewer shows it: follows the volume keys and other apps, not only its own slider. */
@Stable
private class VolumeLevel(val device: DeviceVolume) {
    var level by mutableIntStateOf(device.level)
    fun read() { level = device.level }
}

@Composable
private fun rememberVolumeLevel(): VolumeLevel {
    val context = LocalContext.current
    val volume = remember(context) { VolumeLevel(DeviceVolume(context)) }
    // Android reports volume changes as system setting changes.
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(self: Boolean) { volume.read() } }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) volume.read() }
    return volume
}

/** Opens the volume strip in place of its row; a phone with fixed volume gets a plain mute toggle (video only). */
@Composable
private fun VolumeButton(muted: Boolean, canMute: Boolean, volume: VolumeLevel, controls: ViewerControls, onMute: (Boolean) -> Unit) {
    val silent = muted || volume.device.adjustable && volume.level == 0
    val icon = painterResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    if (!volume.device.adjustable) {
        if (canMute) IconButton({ onMute(!muted) }, Modifier.size(48.dp)) {
            Icon(icon, stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute))
        }
        return
    }
    val mutedLabel = stringResource(R.string.viewer_volume_muted)
    IconButton({ controls.touched(); controls.volumeOpen = true }, Modifier.size(48.dp)
        .semantics { if (silent) stateDescription = mutedLabel }) { Icon(icon, stringResource(R.string.viewer_volume)) }
}

/**
 * Mute plus the phone's media volume, in the row the button sat in, so it never covers the seek bar. Quiet left, loud
 * right in every language, the same way as the seek bar above it. Closes after 3 s untouched or on a tap elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumePanel(muted: Boolean, canMute: Boolean, volume: VolumeLevel, controls: ViewerControls, colors: ViewerColors,
                        onMute: (Boolean) -> Unit) {
    val title = stringResource(R.string.viewer_volume)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(volume.level, muted, dragged || pressed) {
        if (!dragged && !pressed) { delay(ViewerControls.HIDE_AFTER); controls.volumeOpen = false; controls.touched() }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxSize().semantics { paneTitle = title }, verticalAlignment = Alignment.CenterVertically) {
            val silent = muted || volume.level == 0
            if (canMute) IconButton({ onMute(!muted) }, Modifier.size(48.dp)) {
                Icon(painterResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                    stringResource(if (muted) R.string.viewer_unmute else R.string.viewer_mute), tint = if (muted) colors.accent else Color.White)
            } else Icon(painterResource(if (silent) R.drawable.ic_volume_off else R.drawable.ic_volume_up), null, Modifier.padding(Space.m), tint = Color.White)
            val slider = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent,
                inactiveTrackColor = Color.White.copy(alpha = .24f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
            Slider(volume.level.toFloat(), { value ->
                val wanted = kotlin.math.round(value).toInt()
                if (wanted != volume.level && volume.device.set(wanted)) volume.read()
                if (muted && wanted > 0) onMute(false)
            }, Modifier.weight(1f).padding(horizontal = Space.s).semantics { contentDescription = title },
                valueRange = 0f..volume.device.max.toFloat(), steps = (volume.device.max - 1).coerceAtLeast(0), colors = slider, interactionSource = interaction,
                thumb = { Box(Modifier.size(16.dp).background(colors.accent, CircleShape)) },
                track = { state -> SliderDefaults.Track(state, Modifier.height(4.dp), colors = slider, drawStopIndicator = null, thumbTrackGapSize = 0.dp) })
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

/** The lock screen's title too: the file's name without its extension. Text tags inside the file are never read. */
internal suspend fun audioTitle(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
        }
    } catch (_: Exception) { null }
}?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
