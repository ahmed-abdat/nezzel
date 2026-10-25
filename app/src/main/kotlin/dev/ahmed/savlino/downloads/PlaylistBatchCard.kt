package dev.ahmed.savlino.downloads

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut

enum class PlaylistBatchAction { PAUSE, RESUME, STOP, RETRY }

/**
 * One compact header per playlist: title, "Saved X of N" with the state, a thin bar, Pause/Resume and the rest
 * (Stop, Retry) in its menu. A finished batch with retryable failures keeps Retry in view. Tapping the header shows
 * every video in playlist order with its state; [jobs] only refines a queued video's word (downloading, paused, queued).
 */
@Composable
internal fun PlaylistBatchCard(batch: PlaylistBatch, language: String, onAction: (PlaylistBatchAction) -> Unit, busy: Boolean = false,
    modifier: Modifier = Modifier, jobs: List<DownloadProgress> = emptyList()) {
    var stopping by rememberSaveable(batch.id) { mutableStateOf(false) }
    var open by rememberSaveable(batch.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(if (open) 180f else 0f, easeOut(reduced), label = "playlist-chevron")
    val toggle = stringResource(if (open) R.string.playlist_hide_videos else R.string.playlist_show_videos)
    val context = LocalContext.current
    val saved = NezzelFormat.count(batch.saved, language)
    val total = NezzelFormat.count(batch.total, language)
    val stopped = batch.record.state in setOf(BatchState.STOPPED, BatchState.EXPIRED)
    val canRetry = batch.retryable > 0 && !stopped
    val title = batch.record.title.ifBlank { stringResource(R.string.playlist_progress, saved, total) }
    val status = when {
        batch.record.state == BatchState.WAITING_NETWORK -> stringResource(R.string.playlist_waiting_network)
        batch.slowed -> stringResource(R.string.playlist_slowed)
        batch.paused && batch.record.error != null -> DownloadNotifications.phase(context,
            DownloadProgress("batch", "", DownloadPhase.FAILED, error = batch.record.error))
        batch.paused -> stringResource(R.string.download_paused)
        stopped -> stringResource(R.string.playlist_stopped, NezzelFormat.count(batch.items.count { it.state == BatchItemState.SKIPPED }, language))
        batch.terminal && batch.failures.isEmpty() -> stringResource(R.string.dl_batch_saved, saved)
        batch.terminal -> stringResource(R.string.playlist_done, saved, NezzelFormat.count(batch.failures.size, language))
        else -> null
    }
    Surface(modifier.fillMaxWidth().testTag("playlist_batch_${batch.id}"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().animateContentSize(easeOut(reduced))) {
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = toggle, role = Role.Button) { open = !open }.testTag("playlist_header")
                .padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                        Text(NezzelFormat.isolate(title), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        Text(listOfNotNull(stringResource(R.string.dl_batch_saved_of, saved, total).takeIf { !batch.terminal }, status).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (!batch.terminal) {
                        val resume = batch.paused
                        val label = stringResource(if (resume) R.string.resume else R.string.playlist_pause_all)
                        IconButton({ onAction(if (resume) PlaylistBatchAction.RESUME else PlaylistBatchAction.PAUSE) }, enabled = !busy,
                            modifier = Modifier.size(48.dp).testTag(if (resume) "playlist_resume" else "playlist_pause").semantics { contentDescription = label }) {
                            if (resume) Icon(Icons.Default.PlayArrow, null) else Icon(painterResource(R.drawable.ic_pause), null)
                        }
                    } else if (canRetry) TextButton({ onAction(PlaylistBatchAction.RETRY) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_retry")) {
                        Text(stringResource(R.string.playlist_retry, NezzelFormat.count(batch.retryable, language)))
                    }
                    if (!batch.terminal) Box {
                        IconButton({ menu = true }, Modifier.size(48.dp).testTag("playlist_more")) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.saved_more, title))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.playlist_stop)) }, { menu = false; stopping = true },
                                enabled = !busy, modifier = Modifier.testTag("playlist_stop"))
                            if (canRetry) DropdownMenuItem({ Text(stringResource(R.string.playlist_retry, NezzelFormat.count(batch.retryable, language))) },
                                { menu = false; onAction(PlaylistBatchAction.RETRY) }, enabled = !busy, modifier = Modifier.testTag("playlist_retry"))
                        }
                    }
                    IconButton({ open = !open }, Modifier.size(48.dp).testTag("playlist_expand")) {
                        Icon(Icons.Default.KeyboardArrowDown, toggle, Modifier.size(24.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!batch.terminal) LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.finished.toFloat() / batch.total },
                    modifier = Modifier.fillMaxWidth().padding(end = Space.m, bottom = Space.xs))
            }
            if (open) Column(Modifier.fillMaxWidth().testTag("playlist_items").padding(start = Space.l, end = Space.l, top = Space.xs, bottom = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                val byJob = remember(jobs) { jobs.associateBy { it.jobId } }
                batch.items.forEach { item -> key(item.jobId) { PlaylistItemRow(item, byJob[item.jobId], batch.total, language) } }
            }
        }
    }
    if (stopping) AlertDialog(onDismissRequest = { stopping = false },
        text = { Text(stringResource(R.string.playlist_stop_confirm)) },
        confirmButton = { TextButton({ stopping = false; onAction(PlaylistBatchAction.STOP) }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_stop_confirm")) { Text(stringResource(R.string.playlist_stop)) } },
        dismissButton = { TextButton({ stopping = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

/** One video of an open playlist: a state glyph, its title and one short state word. Only real states; no made-up progress. */
@Composable
private fun PlaylistItemRow(item: BatchItemRecord, job: DownloadProgress?, total: Int, language: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    fun reason(error: DownloadError?) = DownloadNotifications.phase(context, DownloadProgress(item.jobId, "", DownloadPhase.FAILED, error = error))
    val active = job?.running == true
    val (word, tone) = when (item.state) {
        BatchItemState.DONE -> stringResource(R.string.download_saved) to colors.onSurfaceVariant
        BatchItemState.QUEUED -> (job?.let { DownloadNotifications.phase(context, it) } ?: stringResource(R.string.download_waiting)) to when {
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
    Row(Modifier.fillMaxWidth().testTag("playlist_item_${item.position}").semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
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
            Text(NezzelFormat.isolate(title), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = if (item.state == BatchItemState.SKIPPED) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(word, style = MaterialTheme.typography.bodySmall, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Dot(color: Color, filled: Boolean) {
    val shape = Modifier.size(10.dp).then(if (filled) Modifier.background(color, CircleShape) else Modifier.border(1.5.dp, color, CircleShape))
    Box(shape)
}
