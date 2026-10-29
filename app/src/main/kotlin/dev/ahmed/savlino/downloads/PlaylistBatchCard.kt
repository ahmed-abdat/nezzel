package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.jobs.waitingForNetwork

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

enum class PlaylistBatchAction { PAUSE, RESUME, STOP, RETRY }

/** "Waiting for network", "Paused", "Done: …": the batch's state in one phrase; null while it simply runs. */
@Composable
private fun batchStatus(batch: PlaylistBatch, language: String): String? {
    val context = LocalContext.current
    val saved = NezzelFormat.count(batch.saved, language)
    val stopped = batch.record.state in setOf(BatchState.STOPPED, BatchState.EXPIRED)
    return when {
        batch.record.state == BatchState.WAITING_NETWORK -> stringResource(R.string.playlist_waiting_network)
        batch.slowed -> stringResource(R.string.playlist_slowed)
        batch.paused && batch.record.error != null -> DownloadNotifications.phase(context,
            DownloadProgress("batch", "", DownloadPhase.FAILED, error = batch.record.error), LocalOffline.current)
        batch.paused -> stringResource(R.string.download_paused)
        stopped -> stringResource(R.string.playlist_stopped, NezzelFormat.count(batch.items.count { it.state == BatchItemState.SKIPPED }, language))
        batch.terminal && batch.failures.isEmpty() -> stringResource(R.string.dl_batch_saved, saved)
        batch.terminal -> stringResource(R.string.playlist_done, saved, NezzelFormat.count(batch.failures.size, language))
        else -> null
    }
}

/** "Saved X of N · state" while it runs; only the final phrase once it is done. */
@Composable
private fun batchMeta(batch: PlaylistBatch, language: String): String =
    listOfNotNull(stringResource(R.string.dl_batch_saved_of, NezzelFormat.count(batch.saved, language), NezzelFormat.count(batch.total, language))
        .takeIf { !batch.terminal }, batchStatus(batch, language)).joinToString(" · ")

@Composable
private fun batchTitle(batch: PlaylistBatch, language: String): String =
    // Link batches stored their title in the language of the day they were made; build "N links" now instead.
    if (batch.links) pluralStringResource(R.plurals.multi_link_title, batch.total, NezzelFormat.count(batch.total, language))
    else batch.record.title.ifBlank {
        stringResource(R.string.playlist_progress, NezzelFormat.count(batch.saved, language), NezzelFormat.count(batch.total, language))
    }

@Composable
private fun PauseResumeButton(batch: PlaylistBatch, busy: Boolean, onAction: (PlaylistBatchAction) -> Unit) {
    val resume = batch.paused
    val label = stringResource(if (resume) R.string.resume else R.string.playlist_pause_all)
    IconButton({ onAction(if (resume) PlaylistBatchAction.RESUME else PlaylistBatchAction.PAUSE) }, enabled = !busy,
        modifier = Modifier.size(48.dp).testTag(if (resume) "playlist_resume" else "playlist_pause").semantics { contentDescription = label }) {
        if (resume) Icon(Icons.Default.PlayArrow, null) else Icon(painterResource(R.drawable.ic_pause), null)
    }
}

/**
 * One flat row per playlist in Downloading (no card): title, "Saved X of N" with the state, a thin bar, Pause/Resume and a
 * chevron to the end side. The whole row opens [PlaylistVideosSheet], where every video, Stop and Retry live.
 */
@Composable
internal fun PlaylistBatchRow(batch: PlaylistBatch, language: String, onAction: (PlaylistBatchAction) -> Unit, onOpen: () -> Unit,
    busy: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().testTag("playlist_batch_${batch.id}")) {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.playlist_show_videos), role = Role.Button, onClick = onOpen)
            .testTag("playlist_header").heightIn(min = JobRowHeight).padding(start = Space.xs, top = Space.s, bottom = Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                    Text(NezzelFormat.isolate(batchTitle(batch, language)), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text(batchMeta(batch, language), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (!batch.terminal) BatchBar(batch)
            }
            if (!batch.terminal) PauseResumeButton(batch, busy, onAction)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(horizontal = Space.s).size(24.dp).testTag("playlist_chevron"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A playlist's line under «Not saved», shaped like a failed download row: its title over "N of M not saved" (with the reason
 * when every failed video shares one), its own Retry and a chevron. The row opens [PlaylistVideosSheet], whose failed videos come first.
 */
@Composable
internal fun PlaylistFailureRow(batch: PlaylistBatch, jobs: List<DownloadProgress>, language: String, onRetry: () -> Unit, onOpen: () -> Unit,
    busy: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val failed = batch.failedVideos(jobs)
    val byJob = remember(jobs) { jobs.associateBy { it.jobId } }
    val title = batchTitle(batch, language)
    val reason = failed.map { it.error ?: byJob[it.jobId]?.error }.distinct().singleOrNull()?.let {
        DownloadNotifications.phase(context, DownloadProgress(batch.id, "", DownloadPhase.FAILED, error = it), LocalOffline.current)
    }
    val meta = listOfNotNull(stringResource(R.string.dl_playlist_failed, NezzelFormat.count(failed.size, language), NezzelFormat.count(batch.total, language)),
        reason).joinToString(" · ")
    val light = scheme.surface.luminance() > .5f
    Surface(modifier.fillMaxWidth().testTag("playlist_failed_${batch.id}"), shape = MaterialTheme.shapes.large, color = rowBase(scheme, light)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.playlist_show_videos), role = Role.Button, onClick = onOpen)
            .heightIn(min = JobRowHeight).padding(start = Space.rowH, top = Space.s, bottom = Space.s, end = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.inline), verticalAlignment = Alignment.CenterVertically) {
            if (!dev.ahmed.savlino.ui.theme.isLargeText()) Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(scheme.errorContainer),
                contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_list), null, Modifier.size(24.dp), tint = scheme.onErrorContainer) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                Text(NezzelFormat.isolate(title), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = scheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (batch.canRetryFailed) {
                val retry = stringResource(R.string.playlist_retry, NezzelFormat.count(batch.userRetryable, language))
                FilledTonalIconButton(onClick = onRetry, enabled = !busy, modifier = Modifier.size(48.dp).testTag("playlist_failed_retry")
                    .semantics { contentDescription = retry }) { Icon(Icons.Default.Refresh, null, Modifier.size(24.dp)) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BatchBar(batch: PlaylistBatch, modifier: Modifier = Modifier) =
    LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.finished.toFloat() / batch.total }, modifier = modifier.fillMaxWidth())

/** Failed first (or about to be retried), then the rest in playlist order. A failed job not yet recorded counts as failed. */
internal fun playlistSections(batch: PlaylistBatch, byJob: Map<String, DownloadProgress>): Pair<List<BatchItemRecord>, List<BatchItemRecord>> =
    batch.items.partition { it.state == BatchItemState.FAILED || (it.state == BatchItemState.QUEUED && byJob[it.jobId]?.phase == DownloadPhase.FAILED) }

/**
 * Every video of a playlist, half height first and full by drag. [batch] is the latest snapshot the caller has; one that
 * finished or left the store keeps its last states, and a terminal one offers only Retry. [focusJob] scrolls to that video.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistVideosSheet(batch: PlaylistBatch, jobs: List<DownloadProgress>, language: String, onAction: (PlaylistBatchAction) -> Unit,
    onDismiss: () -> Unit, busy: Boolean = false, focusJob: String? = null,
    state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), onRetryVideo: (Int) -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        PlaylistVideosContent(batch, jobs, language, onAction, busy, focusJob, onRetryVideo = onRetryVideo)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistVideosContent(batch: PlaylistBatch, jobs: List<DownloadProgress>, language: String, onAction: (PlaylistBatchAction) -> Unit,
    busy: Boolean = false, focusJob: String? = null, modifier: Modifier = Modifier, onRetryVideo: (Int) -> Unit = {}) {
    var stopping by rememberSaveable(batch.id) { mutableStateOf(false) }
    val byJob = remember(jobs) { jobs.associateBy { it.jobId } }
    val (failed, rest) = remember(batch.items, byJob) { playlistSections(batch, byJob) }
    val stopped = batch.record.state in setOf(BatchState.STOPPED, BatchState.EXPIRED)
    val canRetry = batch.canRetryFailed
    val list = rememberLazyListState()
    LaunchedEffect(focusJob) {
        val failedAt = failed.indexOfFirst { it.jobId == focusJob }
        val restAt = rest.indexOfFirst { it.jobId == focusJob }
        // Items: header, then (when any failed) the failed heading, the failed rows and the rest's heading, then the rest.
        val index = when {
            failedAt >= 0 -> 2 + failedAt
            restAt >= 0 -> if (failed.isEmpty()) 1 + restAt else 3 + failed.size + restAt
            else -> -1
        }
        // A video near the top is already in view under the header and its buttons; one further down comes up with the row above it.
        if (index > 3) list.scrollToItem(index - 1)
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(modifier.fillMaxWidth().testTag("playlist_sheet_list"), state = list, contentPadding = PaddingValues(bottom = Space.l + bottom)) {
        item(key = "header", contentType = "header") {
            Column(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                // Stop sits beside the title, apart from Pause and Retry, so the two everyday buttons share one line.
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                        Text(NezzelFormat.isolate(batchTitle(batch, language)), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("playlist_sheet_title").semantics { heading() })
                        Text(batchMeta(batch, language), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (!batch.terminal) TextButton({ stopping = true }, enabled = !busy,
                        modifier = Modifier.padding(start = Space.s).heightIn(min = 48.dp).testTag("playlist_stop")) {
                        Text(stringResource(R.string.playlist_stop))
                    }
                }
                if (!batch.terminal) BatchBar(batch)
                if (!batch.terminal || canRetry) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (!batch.terminal) {
                        val resume = batch.paused
                        FilledTonalButton({ onAction(if (resume) PlaylistBatchAction.RESUME else PlaylistBatchAction.PAUSE) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(if (resume) "playlist_resume" else "playlist_pause")) {
                            if (resume) Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) else Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.s))
                            Text(stringResource(if (resume) R.string.resume else R.string.playlist_pause_all))
                        }
                    }
                    if (canRetry) OutlinedButton({ onAction(PlaylistBatchAction.RETRY) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_retry")) {
                        Text(stringResource(R.string.playlist_retry, NezzelFormat.count(batch.userRetryable, language)))
                    }
                }
            }
        }
        if (failed.isNotEmpty()) {
            item(key = "failed-heading", contentType = "heading") { SheetHeading(stringResource(R.string.playlist_failed_heading), error = true) }
            items(failed, key = { "video-${it.position}" }, contentType = { "video" }) { item ->
                // A failed video with its link still stored has its own Retry; one without says how to try again.
                val retry: (() -> Unit)? = if (item.state == BatchItemState.FAILED && item.hasSource && !stopped) { { onRetryVideo(item.position) } } else null
                PlaylistItemRow(item, byJob[item.jobId], batch.total, language, item.jobId == focusJob, retry, busy)
            }
            if (rest.isNotEmpty()) item(key = "rest-heading", contentType = "heading") { SheetHeading(stringResource(R.string.playlist_other_videos)) }
        }
        items(rest, key = { "video-${it.position}" }, contentType = { "video" }) { item ->
            PlaylistItemRow(item, byJob[item.jobId], batch.total, language, item.jobId == focusJob)
        }
    }
    if (stopping) AlertDialog(onDismissRequest = { stopping = false },
        text = { Text(stringResource(R.string.playlist_stop_confirm)) },
        confirmButton = { TextButton({ stopping = false; onAction(PlaylistBatchAction.STOP) }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_stop_confirm")) { Text(stringResource(R.string.playlist_stop)) } },
        dismissButton = { TextButton({ stopping = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SheetHeading(text: String, error: Boolean = false) =
    Text(text, Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.l, bottom = Space.xs).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

/** One video of a playlist: a state glyph, its title and one short state word. Only real states; no made-up progress. */
@Composable
private fun PlaylistItemRow(item: BatchItemRecord, job: DownloadProgress?, total: Int, language: String, focused: Boolean,
    onRetry: (() -> Unit)? = null, busy: Boolean = false) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val offline = LocalOffline.current
    fun reason(error: DownloadError?) = DownloadNotifications.phase(context, DownloadProgress(item.jobId, "", DownloadPhase.FAILED, error = error), offline)
    val active = job?.running == true
    val (word, tone) = when (item.state) {
        BatchItemState.DONE -> stringResource(R.string.download_saved) to colors.onSurfaceVariant
        BatchItemState.QUEUED -> (job?.let { DownloadNotifications.phase(context, it, offline) } ?: stringResource(R.string.download_waiting)) to when {
            job?.phase == DownloadPhase.FAILED -> colors.error
            active -> colors.primary
            else -> colors.onSurfaceVariant
        }
        BatchItemState.WAITING -> stringResource(R.string.playlist_item_waiting) to colors.onSurfaceVariant
        BatchItemState.FAILED -> reason(item.error) to colors.error
        BatchItemState.SKIPPED -> (if (item.error == null || item.error == DownloadError.Cancelled) stringResource(R.string.playlist_item_skipped)
            else reason(item.error)) to colors.onSurfaceVariant
    }
    val title = item.title.ifBlank { stringResource(R.string.viewer_video_n, NezzelFormat.count(item.position + 1, language), NezzelFormat.count(total, language)) }
    Row(Modifier.fillMaxWidth().testTag("playlist_item_${item.position}").heightIn(min = 56.dp)
        .then(if (focused) Modifier.background(colors.secondaryContainer.copy(alpha = .6f)) else Modifier)
        .semantics(mergeDescendants = true) {}.padding(horizontal = Space.xl, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                item.state == BatchItemState.DONE -> Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = colors.primary)
                item.state == BatchItemState.FAILED || job?.phase == DownloadPhase.FAILED ->
                    Icon(painterResource(R.drawable.ic_error), null, Modifier.size(20.dp), tint = colors.error)
                item.state == BatchItemState.SKIPPED -> Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = colors.outline)
                active -> Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp), tint = colors.primary)
                job?.paused == true -> Icon(painterResource(R.drawable.ic_pause), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                item.state == BatchItemState.QUEUED -> Dot(colors.onSurfaceVariant, filled = true)
                else -> Dot(colors.outline, filled = false)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            Text(NezzelFormat.isolate(title), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = if (item.state == BatchItemState.SKIPPED) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(word, style = MaterialTheme.typography.bodyMedium, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.state == BatchItemState.FAILED && onRetry == null)
                Text(stringResource(R.string.download_retry_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (onRetry != null) {
            val label = stringResource(R.string.try_again)
            FilledTonalIconButton(onClick = onRetry, enabled = !busy, modifier = Modifier.size(48.dp).testTag("playlist_item_retry_${item.position}")
                .semantics { contentDescription = label }) { Icon(Icons.Default.Refresh, null, Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Dot(color: Color, filled: Boolean) {
    val shape = Modifier.size(10.dp).then(if (filled) Modifier.background(color, CircleShape) else Modifier.border(1.5.dp, color, CircleShape))
    Box(shape)
}
