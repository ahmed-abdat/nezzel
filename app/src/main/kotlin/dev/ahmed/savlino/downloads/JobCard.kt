package dev.ahmed.savlino.downloads

import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.BatteryHelp
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.isLargeText
import dev.ahmed.savlino.ui.theme.motionSpec
import kotlinx.coroutines.delay

/** A measured byte or fragment fraction exists only during active transfer; every other phase spins. */
internal fun DownloadProgress.ringFraction(): Float? =
    visiblePercent?.let { it / 100f }

internal val DownloadProgress.saved get() = phase == DownloadPhase.COMPLETED

/**
 * Jobs that finished while this screen watched them stay as a "Saved" card for [Motion.SAVED_DWELL],
 * so the person sees the result before the card leaves. UI-only: the journal is untouched.
 */
@Composable
internal fun rememberWithSavedMoment(jobs: List<DownloadProgress>, visible: List<DownloadProgress>): List<DownloadProgress> {
    val seen = remember { mutableMapOf<String, DownloadPhase>() }
    val flashing = remember { mutableStateMapOf<String, DownloadProgress>() }
    jobs.forEach { job ->
        val before = seen.put(job.jobId, job.phase)
        if (job.saved && before != null && before != DownloadPhase.COMPLETED && before != DownloadPhase.FAILED &&
            before != DownloadPhase.CANCELLED) flashing[job.jobId] = job
        if (!job.saved) flashing.remove(job.jobId)
    }
    flashing.keys.toList().forEach { id ->
        key(id) { LaunchedEffect(id) { delay(Motion.SAVED_DWELL); flashing.remove(id) } }
    }
    if (flashing.isEmpty()) return visible
    val ids = visible.mapTo(HashSet()) { it.jobId }
    return (flashing.values.filter { it.jobId !in ids } + visible)
}


/** M3 two-line list item minimum (8dp padding, so the Arabic two-line stack fits); rows grow past it, never clip. */
internal val JobRowHeight = 72.dp

/**
 * One flat row for every job state, on Home and in Downloads: artwork or kind tile, filename over one meta line, Pause/Resume and
 * Cancel at the end. The card itself is the progress: a tonal layer grows from the start edge to the real fraction
 * (owner 2026-10-23, option C). No fraction, no fill: waiting and restarting rows show an indeterminate hairline at the
 * bottom instead. Level 0 on a tonal fill, no shadow. Large text drops the tile and grows the row; buttons never stack.
 * Failed is terminal and may grow to hold its reason, with Try again and Remove at the end.
 */
@Composable
internal fun JobRow(job: DownloadProgress, onCancel: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit,
                    modifier: Modifier = Modifier, onPause: () -> Unit = {}, onResume: () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val offline = LocalOffline.current
    // One tonal step off the page; light needs the higher step to keep an edge without a shadow.
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    val tone by animateColorAsState(if (job.saved) MaterialTheme.colorScheme.primaryContainer else rowBase(MaterialTheme.colorScheme, light),
        easeOut(reduced, 400), label = "row tone")
    val fraction = job.fillFraction()
    val spoken = if (fraction != null) progressAnnouncement(job) else null
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = tone) {
        BoxWithConstraints(Modifier.animateContentSize(easeOut(reduced)).semantics {
            if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            else if (!job.terminal && !job.saved && !job.waiting && job.phase != DownloadPhase.FAILED) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            spoken?.let { stateDescription = it }
        }) {
            // The tile gives way at large text and on narrow rows (phones under ~390dp), so the meta line keeps
            // percent · time left and "Joining audio and video…" keeps its one line.
            val tile = !isLargeText() && maxWidth >= 340.dp
            val art = if (tile) rememberJobArtwork(job.jobId) else null
            // A badge's ring takes the colour right behind the tile: the fill once it has passed the tile, else the row.
            val behind = if (fraction != null && maxWidth * fraction >= Space.rowH + 40.dp)
                fillColor(MaterialTheme.colorScheme, job.paused, light).compositeOver(tone) else tone
            if (fraction != null) JobFill(fraction, job.paused, light, Modifier.matchParentSize())
            // Waiting has nothing to show; every other live row without a fraction breathes so it never looks frozen.
            else if (!job.terminal && !job.saved && !job.waiting && job.phase != DownloadPhase.FAILED) JobPulse(reduced, Modifier.matchParentSize())
            if (job.phase == DownloadPhase.FAILED) FailedRow(job, tile, art, tone, onCancel, onRemove, onRetry)
            else {
                Row(Modifier.fillMaxWidth().heightIn(min = JobRowHeight).padding(horizontal = Space.rowH, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    val audio = job.mimeType.startsWith("audio/")
                    if (tile) JobThumb(art, job.kindIcon(),
                        if (audio) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        if (audio) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        if (audio) ThumbBadge.Audio else null, behind)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                        FilenameText(job.filename)
                        JobMeta(job)
                    }
                    if (job.saved) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SavedCheck(36.dp) }
                    else Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (job.paused || job.canPause) PauseResumeButton(job, onPause, onResume)
                        CancelButton(job, onCancel)
                    }
                }
            }
        }
    }
}

/** The real fraction the card fills to: live while bytes move, the last one while paused. Never a guess. */
internal fun DownloadProgress.fillFraction(): Float? = ringFraction() ?: if (paused) percent?.let { it / 100f } else null

/** The row's own tone: one tonal step off the page. */
internal fun rowBase(scheme: ColorScheme, light: Boolean): Color = if (light) scheme.surfaceContainerHigh else scheme.surfaceContainer

/** Fill tone, drawn over [rowBase]: the primary container (half strength in light) running, a neutral step while paused. */
internal fun fillColor(scheme: ColorScheme, paused: Boolean, light: Boolean): Color = when {
    paused -> if (light) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
    else -> scheme.primaryContainer.copy(alpha = if (light) .5f else .7f)
}

/**
 * A tonal layer behind the content, [fraction] of the width from the start edge (the right edge in Arabic). The change
 * of tone is the edge; no stroke, so nothing cuts through the text.
 */
@Composable
private fun JobFill(fraction: Float, paused: Boolean, light: Boolean, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    // The last step snaps: a fill still easing toward 100% would trail the "Finishing…" label above it.
    val shown by animateFloatAsState(fraction, if (fraction >= .99f) snap() else motionSpec(reduced,
        tween(Motion.PROGRESS, easing = LinearEasing)), label = "fill")
    val color = fillColor(MaterialTheme.colorScheme, paused, light)
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.testTag("job-fill").fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).background(color))
    }
}

/** Working with no fraction to show (starting again, unknown size): the card breathes a faint tint. Reduced motion: nothing, like Waiting. */
@Composable
private fun JobPulse(still: Boolean, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    if (still) return
    val t = rememberInfiniteTransition(label = "pulse")
    val alpha by t.animateFloat(.02f, .08f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "pulse alpha")
    Box(modifier.background(tint.copy(alpha = alpha)))
}

/** A filename is LTR inside RTL layouts. The journal's confirmed filename is the identity; no synthetic title. */
@Composable
private fun FilenameText(filename: String) =
    Text(NezzelFormat.isolate(filename), Modifier.fillMaxWidth(), maxLines = if (isLargeText()) 2 else 1, overflow = TextOverflow.Ellipsis,
        // LTR text, but the line starts where the layout starts, so the title shares the meta's start edge in Arabic.
        textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))

/** Which glyph names the job's file kind; never a play arrow, since nothing here can be played yet. */
internal fun DownloadProgress.kindIcon(): Int = when {
    mimeType.startsWith("audio/") -> R.drawable.ic_music
    mimeType.startsWith("image/") -> R.drawable.ic_image
    else -> R.drawable.ic_movie
}

/** The job's row artwork by id, read off the main thread (`downloads.artwork::load`); null keeps the kind icon. */
val LocalJobArtwork = staticCompositionLocalOf<suspend (String) -> Bitmap?> { { null } }

@Composable
private fun rememberJobArtwork(jobId: String): ImageBitmap? {
    val load = LocalJobArtwork.current
    return produceState<ImageBitmap?>(null, jobId, load) { value = load(jobId)?.asImageBitmap() }.value
}

internal enum class ThumbBadge { Audio, Failed }

/**
 * The options sheet's own artwork, cropped to a square, when the job kept one; else a flat tile names the kind of file
 * instead of faking a frame. Over artwork a small badge keeps what the icon said (audio, failed), ringed in the colour
 * behind it. Decorative and not clickable: the filename names the row.
 */
@Composable
private fun JobThumb(art: ImageBitmap?, icon: Int, container: Color, content: Color, badge: ThumbBadge?, ring: Color) {
    if (art == null) return Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(container), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = content)
    }
    Box(Modifier.testTag("job-art").size(40.dp)) {
        Image(art, null, Modifier.matchParentSize().clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
        if (badge != null) Box(Modifier.testTag("job-art-badge-${badge.name.lowercase()}").align(Alignment.BottomEnd)
            .offset(3.dp, 3.dp).size(18.dp).background(ring, CircleShape).padding(2.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            val scheme = MaterialTheme.colorScheme
            when (badge) {
                ThumbBadge.Audio -> Box(Modifier.matchParentSize().background(scheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_music), null, Modifier.size(10.dp), tint = scheme.onTertiaryContainer)
                }
                // The error glyph's disc fills the badge; its "!" is cut out, so the light layer under it shows through.
                ThumbBadge.Failed -> Box(Modifier.matchParentSize().background(scheme.onError), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_error), null, Modifier.requiredSize(17.dp), tint = scheme.error)
                }
            }
        }
    }
}

/**
 * The journal's numbers, by field. The percent is dropped whenever the ring is not showing a real fraction
 * (stalled, waiting), so the text can never claim more than the ring does.
 */
@Composable
private fun transfer(job: DownloadProgress): TransferParts? =
    DownloadNotifications.transferParts(LocalContext.current, job)?.let { if (job.ringFraction() == null) it.copy(percent = null) else it }

/** One piece of the meta line; each stays its own text (and its own TalkBack stop). [trailing] sits at the line's end. */
private class MetaPart(val text: String, val color: Color, val bold: Boolean = false,
                       val direction: TextDirection = TextDirection.Ltr, val live: Boolean = false, val trailing: Boolean = false,
                       /** Stays on the line however narrow it gets: percent and time left. */
                       val keep: Boolean = false,
                       /** A shorter form that keeps the meaning, tried before the part is dropped ("42 MB" for "42 of 100 MB"). */
                       val short: String? = null)

private fun exactTotal(job: DownloadProgress) = (job.total as? Size.Exact)?.bytes?.takeIf { it > 0 }

/** "12 of 30 MB" when both share a unit, "900 KB of 1.2 GB" otherwise; only against an exact total. */
@Composable
private fun rowBytes(job: DownloadProgress, language: String): String? {
    val total = job.exactTransferTotal ?: return null
    val done = job.bytes?.takeIf { it in 0..total } ?: return null
    val a = NezzelFormat.size(done, language); val b = NezzelFormat.size(total, language)
    val unit = a.substringAfter(' ', "")
    val shared = unit.isNotEmpty() && unit == b.substringAfter(' ', "")
    return stringResource(R.string.download_row_bytes, NezzelFormat.isolate(if (shared) a.substringBefore(' ') else a), NezzelFormat.isolate(b))
}

/** Just what is done, "42 MB": the first thing a narrow row keeps of "42 of 100 MB". */
@Composable
private fun rowBytesDone(job: DownloadProgress, language: String): String? {
    val total = job.exactTransferTotal ?: return null
    return job.bytes?.takeIf { it in 0..total }?.let { NezzelFormat.size(it, language) }
}

/**
 * The meta line's parts, most important first. While the ring shows a real fraction: how much (percent · bytes) at the
 * start and how long (time left) at the end, so each reads at a glance (owner 2026-10-21); a narrow line drops bytes
 * first, then time left. Speed stays in the notification. Every other phase: its word, then the bytes or size it knows.
 */
@Composable
private fun metaParts(job: DownloadProgress, word: String): List<MetaPart> {
    val scheme = MaterialTheme.colorScheme
    val language = LocalConfiguration.current.locales[0].language
    val quiet = scheme.onSurfaceVariant
    // AnimatedContent retains the outgoing phase word. Never pair it with the incoming byte tick.
    if (word != DownloadNotifications.phase(LocalContext.current, job, LocalOffline.current))
        return listOf(MetaPart(word, scheme.primary, direction = TextDirection.Content))
    // Paused keeps the last real numbers only: percent and bytes the journal had, no speed, no time left.
    if (job.paused) return listOfNotNull(MetaPart(word, scheme.primary, direction = TextDirection.Content, live = true),
        job.percent?.let { MetaPart(NezzelFormat.isolate(NezzelFormat.percent(it, language)), scheme.onSurface, bold = true) },
        job.bytes?.takeIf { it >= 0 }?.let { MetaPart(NezzelFormat.isolate(NezzelFormat.size(it, language)), quiet, direction = TextDirection.Content) })
    if (job.saved) return listOfNotNull(MetaPart(word, scheme.onPrimaryContainer, bold = true, direction = TextDirection.Content, live = true),
        job.qualityHeight?.let { MetaPart(stringResource(R.string.video_quality, NezzelFormat.count(it, language)), scheme.onPrimaryContainer) },
        exactTotal(job)?.let { MetaPart(NezzelFormat.size(it, language), scheme.onPrimaryContainer) })
    val parts = transfer(job)
    // At 100% the word "Finishing…" says more than a percent that can't move.
    val percent = parts?.percent?.takeIf { job.percent != 100 }
    if (parts != null && percent != null) return listOfNotNull(MetaPart(percent, scheme.onSurface, bold = true, keep = true),
        parts.remaining?.let { job.remainingTransferSeconds?.let { seconds -> MetaPart(stringResource(R.string.download_row_remaining,
            NezzelFormat.isolate(NezzelFormat.duration(seconds))), quiet, direction = TextDirection.Content, keep = true) } },
        MetaPart(rowBytes(job, language) ?: parts.bytes, quiet, direction = TextDirection.Content, short = rowBytesDone(job, language)))
    val bytes = if (parts != null) rowBytes(job, language) ?: parts.bytes else null
    // Without bytes yet (queued, preparing), the chosen quality tells two rows of the same video apart.
    val quality = job.qualityHeight?.let { MetaPart(stringResource(R.string.video_quality, NezzelFormat.count(it, language)), quiet) }
    return listOfNotNull(MetaPart(word, scheme.primary, direction = TextDirection.Content, live = true), bytes?.let { MetaPart(it, quiet, keep = true) } ?: quality)
}

@Composable
private fun JobMeta(job: DownloadProgress) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    AnimatedContent(DownloadNotifications.phase(context, job, LocalOffline.current), Modifier.fillMaxWidth(), transitionSpec = {
        (fadeIn(easeOut(reduced, Motion.FADE_IN, 50)) togetherWith fadeOut(easeOut(reduced, Motion.FADE_OUT)))
            .using(SizeTransform(clip = false))
    }, label = "phase") { word ->
        // Updates in place: numbers never animate, so they can't run ahead of the journal. Which parts fit is decided
        // on the busiest numbers this job can show (99%, 59:59 left), so bytes don't blink in and out as digits change.
        val total = exactTotal(job)
        val busiest = if (total == null || job.ringFraction() == null) job else job.copy(bytes = total * 99 / 100,
            remainingTransferSeconds = job.remainingTransferSeconds?.coerceAtLeast(3599))
        MetaLine(metaParts(job, word), metaParts(busiest, word), lines = if (isLargeText()) 2 else 1)
    }
}

/**
 * A slot of exactly [lines] lines, so the row keeps one height whatever the phase says. Parts that don't fit (in
 * [all] or in its [busiest] twin) drop from the end: bytes, then time left; the first part always stays. Measured,
 * never ellipsized mid-number.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(all: List<MetaPart>, busiest: List<MetaPart>, lines: Int) {
    val measurer = rememberTextMeasurer()
    val separator = stringResource(R.string.separator)
    val base = MaterialTheme.typography.bodyMedium
    fun style(part: MetaPart) = base.copy(textDirection = part.direction, fontWeight = if (part.bold) FontWeight.SemiBold else null)
    // Each reserved line holds a digit and a word of the UI language, so Arabic lines are reserved at Arabic height.
    // The slot fits both shapes the lines can take: separate texts stacked by FlowRow, or one wrapped phase word
    // (its inner lines keep the full line height that a lone line trims).
    val sample = "0 " + stringResource(R.string.download_saved)
    val slot = with(LocalDensity.current) { maxOf(measurer.measure(sample, base).size.height * lines,
        measurer.measure(List(lines) { sample }.joinToString("\n"), base).size.height).toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = slot), contentAlignment = Alignment.CenterStart) {
        val max = constraints.maxWidth
        val gap = measurer.measure(separator, base).size.width
        fun fits(shown: List<MetaPart>): Boolean {
            var used = 1; var x = 0
            shown.forEachIndexed { index, part ->
                val text = measurer.measure(part.text, style(part), constraints = Constraints(maxWidth = max.coerceAtLeast(1)))
                val w = (if (text.lineCount > 1) max else text.size.width) + if (index < shown.lastIndex) gap else 0
                if (x > 0 && x + w > max) { used++; x = 0 }
                used += text.lineCount - 1; x += w
            }
            return used <= lines
        }
        // A narrow line gives up detail in this order: "of 100 MB", then the bytes. Percent and time left stay.
        val floor = maxOf(1, all.indexOfLast { it.keep } + 1)
        fun ladder(parts: List<MetaPart>): List<List<MetaPart>> = buildList {
            add(parts)
            parts.lastOrNull()?.short?.let { short -> parts.last().let { add(parts.dropLast(1) + MetaPart(short, it.color, it.bold, it.direction)) } }
            for (n in parts.size - 1 downTo minOf(floor, parts.size)) add(parts.take(n))
        }
        val steps = ladder(all); val busiestSteps = ladder(busiest)
        val shown = steps.indices.firstOrNull { fits(steps[it]) && fits(busiestSteps.getOrElse(it) { busiestSteps.last() }) }
            ?.let { steps[it] } ?: steps.last()
        // Large text flows every part in order; one line splits how much (start) from how long (end).
        val split = lines == 1
        val lead = if (split) shown.filterNot { it.trailing } else shown
        @Composable fun part(part: MetaPart) =
            // Numbers are already measured to fit; only a long phase word needs the cap.
            Text(part.text, style = style(part), color = part.color, maxLines = if (part.live) lines else Int.MAX_VALUE,
                modifier = if (part.live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f)) {
                lead.forEachIndexed { index, it ->
                    // Row follows layout direction, so in Arabic the dot still lands between two parts.
                    Row(Modifier.width(IntrinsicSize.Max)) {
                        part(it)
                        if (index < lead.lastIndex) Text(separator, Modifier.clearAndSetSemantics {}, style = base, color = it.color)
                    }
                }
            }
            // Time left keeps its own end of the line; the separator's width is its gap, already counted by fits().
            if (split) shown.filter { it.trailing }.forEach {
                Row(Modifier.width(IntrinsicSize.Max).padding(start = with(LocalDensity.current) { gap.toDp() })) { part(it) }
            }
        }
    }
}

/** Cancel: a plain 48dp button at the row's end, named after the file. */
@Composable
private fun CancelButton(job: DownloadProgress, onCancel: () -> Unit) {
    val label = stringResource(R.string.download_cancel_accessibility, NezzelFormat.isolate(job.filename))
    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Icon(Icons.Default.Close, null, Modifier.size(20.dp))
    }
}

/** Pause while bytes move, Resume while paused (tinted primary: the action that needs you): tonal 48dp, named after the file. */
@Composable
private fun PauseResumeButton(job: DownloadProgress, onPause: () -> Unit, onResume: () -> Unit) {
    val name = NezzelFormat.isolate(job.filename)
    val label = stringResource(if (job.paused) R.string.download_resume_accessibility else R.string.download_pause_accessibility, name)
    FilledTonalIconButton(onClick = if (job.paused) onResume else onPause, modifier = Modifier.size(48.dp)
        .semantics { contentDescription = label },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            contentColor = if (job.paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer)) {
        if (job.paused) Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp))
        else Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(24.dp))
    }
}

/** TalkBack reads the card as "42%, about 0:30 left": the same numbers the row prints, nothing more. */
@Composable
internal fun progressAnnouncement(job: DownloadProgress): String? {
    val parts = transfer(job) ?: return null
    val percent = parts.percent ?: return null
    return parts.remaining?.let { stringResource(R.string.download_progress_state, percent, it) } ?: percent
}

/** The one authored moment: the ring resolves into a check. */
@Composable
private fun SavedCheck(size: androidx.compose.ui.unit.Dp) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else .6f,
        if (reduced) snap() else spring(dampingRatio = .75f, stiffness = 400f), label = "saved")
    Surface(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }, shape = CircleShape,
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(24.dp)) }
    }
}

/**
 * Failed is terminal, so the row may grow: the reason gets the full width, the "can't retry" hint a second line.
 * Try again is a tonal ↻ button beside Remove (or Cancel while awaiting its schedule); no pill under the text.
 */
@Composable
private fun FailedRow(job: DownloadProgress, tile: Boolean, art: ImageBitmap?, ring: Color, onCancel: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = JobRowHeight).padding(horizontal = Space.rowH, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.inline), verticalAlignment = Alignment.CenterVertically) {
        if (tile) JobThumb(art, R.drawable.ic_error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
            ThumbBadge.Failed, ring)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            FilenameText(job.filename)
            Text(DownloadNotifications.phase(context, job), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!job.awaitingSchedule && !job.retryable)
                Text(stringResource(R.string.download_retry_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            BatteryHelp(job.error, onBatterySettings = { BatterySettings.open(context) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            if (job.awaitingSchedule || job.retryable) {
                val retry = stringResource(R.string.try_again)
                FilledTonalIconButton(onClick = onRetry, modifier = Modifier.size(48.dp).semantics { contentDescription = retry }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(24.dp))
                }
            }
            val label = if (job.awaitingSchedule) stringResource(R.string.download_cancel_accessibility, NezzelFormat.isolate(job.filename))
                else stringResource(R.string.download_remove)
            IconButton(onClick = if (job.awaitingSchedule) onCancel else onRemove, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
                Icon(Icons.Default.Close, null, Modifier.size(20.dp))
            }
        }
    }
}
