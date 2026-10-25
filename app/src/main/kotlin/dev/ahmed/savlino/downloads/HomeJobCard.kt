package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.DownloadNotifications
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.nav.LocalTabActive
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.motionSpec

/** One card of Home's recents row. A finished job's file keeps the job's key, so its slot stays put while the card changes. */
internal sealed interface HomeSlot { val key: String }
internal data class JobSlot(val jobId: String, val landing: Boolean = false) : HomeSlot { override val key get() = jobKey(jobId) }
internal data class FileSlot(val item: SavedMedia, override val key: String) : HomeSlot

internal fun jobKey(jobId: String) = "job:$jobId"

/** The job publishes to content://media/external_primary/…, the library lists content://media/external/…: same row id. */
internal fun fileKey(uri: Uri): String =
    if (uri.authority == "media") "media/" + uri.pathSegments.drop(1).joinToString("/") else uri.toString()

/** Most download cards the row leads with; the rest wait in Downloads. */
internal const val HOME_JOB_CARDS = 8

/**
 * The row's cards: downloads first (running in start order, then the queue, then failures), then files that finished
 * here but are not listed yet ([landing]), then the saved files. A file whose job is still in the journal takes the job's key.
 */
internal fun homeSlots(jobs: List<DownloadProgress>, recent: List<SavedMedia>, listed: Set<String>, started: List<String>,
                       landing: Set<String>, fileJobs: MutableMap<String, String> = HashMap()): List<HomeSlot> {
    val shownJobs = visibleJobs(jobs, started).take(HOME_JOB_CARDS)
    val live = shownJobs.map { JobSlot(it.jobId) }
    // Listed before its job reports saved: the job's card already stands for the file, so it is not shown twice.
    val writing = shownJobs.mapNotNullTo(HashSet()) { job -> job.targetUri?.takeIf { !job.saved }?.let(::fileKey) }
    val landed = jobs.filter { it.jobId in landing && it.saved && it.publishedUri?.let { uri -> fileKey(uri) in listed } != true }
        .map { JobSlot(it.jobId, landing = true) }
    // Remembered past the job's own life: when the finished job leaves the list, its file keeps the job's key, so the
    // card never swaps keys (a remove plus an insert that slides the row).
    jobs.forEach { job -> if (job.saved) job.publishedUri?.let { fileJobs[fileKey(it)] = job.jobId } }
    val byFile = fileJobs
    val used = HashSet<String>()
    return live + landed + recent.filter { fileKey(it.uri) !in writing }.map { item ->
        FileSlot(item, byFile[fileKey(item.uri)]?.let(::jobKey)?.takeIf(used::add) ?: item.uri.toString())
    }
}

/**
 * A download as a recents card, the same size as a saved file's card, so the row never changes height (owner 2026-10-25):
 * the job's picture with the ring and big percent over it, the title, then time left or the phase word. Phases without
 * real numbers spin; waiting shows the bare track. No buttons: the card opens Downloads, where pause, cancel and retry live.
 */
@Composable
internal fun HomeJobCard(job: DownloadProgress, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val failed = job.phase == DownloadPhase.FAILED && !job.awaitingSchedule
    // Paused keeps its last real fraction on the arc only; the words say Paused.
    val fraction = if (job.paused && !job.terminal) null else job.ringFraction()
    val word = DownloadNotifications.phase(context, job, LocalOffline.current, LocalHot.current)
    // Time left only beside a real fraction, as the Downloads row prints it; at 100% "Finishing…" says more.
    val left = job.remainingTransferSeconds?.takeIf { fraction != null && job.percent != 100 &&
        DownloadNotifications.transferParts(context, job)?.remaining != null }
    val meta = left?.let { stringResource(R.string.download_row_remaining, NezzelFormat.isolate(NezzelFormat.duration(it))) } ?: word
    val percent = fraction?.takeIf { job.percent != 100 }?.let { NezzelFormat.isolate(NezzelFormat.percent(job.percent ?: 0, language)) }
    val label = stringResource(R.string.home_job_open)
    Column(modifier.width(196.dp).clip(MaterialTheme.shapes.large).semantics(mergeDescendants = true) {
        // The ring's number is read here, once; time left is the meta line's own text.
        percent?.let { stateDescription = it }
        if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        else if (!job.terminal && !job.waiting && !job.paused) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }.clickable(onClickLabel = label, onClick = onClick), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        val scheme = MaterialTheme.colorScheme
        val art = rememberJobArtwork(job.jobId, job.terminal)
        val audio = job.mimeType.startsWith("audio/")
        // Same box as MediaThumbnail in the saved card: 16:10 with the 4dp gap inside it.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(bottom = Space.xs).clip(MaterialTheme.shapes.large)
            .background(when { failed -> scheme.errorContainer; audio -> scheme.tertiaryContainer; else -> scheme.secondaryContainer }),
            contentAlignment = Alignment.Center) {
            if (art != null) Image(art, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            JobDial(job, failed, fraction, percent)
        }
        Column(Modifier.padding(start = Space.xs, end = Space.xs, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(NezzelFormat.firstStrong(job.filename.removeSuffix(savedExtension(job.filename))),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = when { failed -> scheme.error; left != null -> scheme.onSurfaceVariant; else -> scheme.primary },
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The ring on a surface disc, so it reads over any picture (as on the widget). It grows with large text so the percent fits;
 * the arc eases to each real fraction and is drawn without recomposing.
 */
@Composable
private fun JobDial(job: DownloadProgress, failed: Boolean, fraction: Float?, percent: String?) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val size = 56.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    if (job.saved) return Box(Modifier.testTag("home-job-saved")) { SavedCheck(size) }
    Box(Modifier.testTag("home-job-dial").size(size).background(scheme.surfaceContainerLowest.copy(alpha = .92f), CircleShape)
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (failed) return@Box Icon(painterResource(R.drawable.ic_error), null, Modifier.size(28.dp), tint = scheme.error)
        val paused = job.paused && !job.terminal
        val filled = fraction ?: job.fillFraction()
        val track = scheme.surfaceContainerHighest
        val arc = if (paused) scheme.outline else scheme.primary
        // The last step snaps, as the row's fill does: an arc still easing toward 100% would trail "Finishing…".
        val shown = animateFloatAsState(filled ?: 0f, if ((filled ?: 0f) >= .99f) snap() else motionSpec(reduced,
            tween(Motion.PROGRESS, easing = LinearEasing)), label = "home ring")
        // Working without a fraction spins a quarter arc; waiting, paused, reduced motion and a hidden tab never ask for frames.
        val spins = filled == null && !job.waiting && !paused && !reduced && LocalTabActive.current.value
        val turn = if (spins) rememberInfiniteTransition(label = "home spin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "home turn") else null
        Canvas(Modifier.matchParentSize().padding(Space.xs).graphicsLayer { rotationZ = turn?.value ?: 0f }) {
            val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(track, style = Stroke(4.dp.toPx()))
            val sweep = when {
                filled != null -> shown.value * 360f
                spins -> 90f
                else -> 0f
            }
            if (sweep > 0f) drawArc(arc, -90f, sweep, false, style = stroke)
        }
        when {
            percent != null -> Text(percent, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Ltr), color = scheme.onSurface, maxLines = 1)
            paused -> Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        }
    }
}
