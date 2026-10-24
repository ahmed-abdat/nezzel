package dev.ahmed.savlino.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

enum class PlaylistBatchAction { PAUSE, RESUME, STOP, RETRY }

/**
 * One compact header per playlist: title, "Saved X of N" with the state, a thin bar, Pause/Resume at the end and the rest
 * (Stop, Retry, See why) in its menu. A finished batch with retryable failures keeps Retry in view.
 */
@Composable
internal fun PlaylistBatchCard(batch: PlaylistBatch, language: String, onAction: (PlaylistBatchAction) -> Unit, busy: Boolean = false,
    modifier: Modifier = Modifier) {
    var stopping by rememberSaveable(batch.id) { mutableStateOf(false) }
    var why by rememberSaveable(batch.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
        Column(Modifier.padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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
                if (!batch.terminal || batch.failures.isNotEmpty()) Box {
                    IconButton({ menu = true }, Modifier.size(48.dp).testTag("playlist_more")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.saved_more, title))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        if (!batch.terminal) DropdownMenuItem({ Text(stringResource(R.string.playlist_stop)) }, { menu = false; stopping = true },
                            enabled = !busy, modifier = Modifier.testTag("playlist_stop"))
                        if (canRetry && !batch.terminal) DropdownMenuItem({ Text(stringResource(R.string.playlist_retry, NezzelFormat.count(batch.retryable, language))) },
                            { menu = false; onAction(PlaylistBatchAction.RETRY) }, enabled = !busy, modifier = Modifier.testTag("playlist_retry"))
                        if (batch.failures.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.playlist_see_why)) }, { menu = false; why = !why },
                            modifier = Modifier.testTag("playlist_why"))
                    }
                }
            }
            if (!batch.terminal) LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.finished.toFloat() / batch.total },
                modifier = Modifier.fillMaxWidth().padding(end = Space.m, bottom = Space.xs))
            if (why) batch.failures.forEach { item ->
                Column(Modifier.padding(end = Space.m)) {
                    Text(NezzelFormat.isolate(item.title), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                    Text(DownloadNotifications.phase(context, DownloadProgress(item.jobId, "", DownloadPhase.FAILED, error = item.error)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (stopping) AlertDialog(onDismissRequest = { stopping = false },
        text = { Text(stringResource(R.string.playlist_stop_confirm)) },
        confirmButton = { TextButton({ stopping = false; onAction(PlaylistBatchAction.STOP) }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_stop_confirm")) { Text(stringResource(R.string.playlist_stop)) } },
        dismissButton = { TextButton({ stopping = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}
