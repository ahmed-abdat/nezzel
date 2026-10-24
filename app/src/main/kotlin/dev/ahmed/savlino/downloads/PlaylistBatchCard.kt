package dev.ahmed.savlino.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

enum class PlaylistBatchAction { PAUSE, RESUME, STOP, RETRY }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistBatchCard(batch: PlaylistBatch, language: String, expanded: Boolean, onExpand: () -> Unit,
    onAction: (PlaylistBatchAction) -> Unit, busy: Boolean = false) {
    var stopping by rememberSaveable(batch.id) { mutableStateOf(false) }
    var why by rememberSaveable(batch.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val saved = NezzelFormat.count(batch.saved, language)
    val total = NezzelFormat.count(batch.total, language)
    Surface(Modifier.fillMaxWidth().testTag("playlist_batch_${batch.id}"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.playlist_progress, saved, total), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (batch.record.title.isNotBlank()) Text(NezzelFormat.isolate(batch.record.title),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.finished.toFloat() / batch.total },
                modifier = Modifier.fillMaxWidth())
            val status = when {
                batch.record.state == BatchState.WAITING_NETWORK -> stringResource(R.string.playlist_waiting_network)
                batch.slowed -> stringResource(R.string.playlist_slowed)
                batch.paused && batch.record.error != null -> DownloadNotifications.phase(context,
                    DownloadProgress("batch", "", DownloadPhase.FAILED, error = batch.record.error))
                batch.paused -> stringResource(R.string.download_paused)
                batch.record.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) -> stringResource(R.string.playlist_stopped,
                    NezzelFormat.count(batch.items.count { it.state == BatchItemState.SKIPPED }, language))
                batch.terminal && batch.failures.isEmpty() -> stringResource(R.string.playlist_done_saved, saved)
                batch.terminal -> stringResource(R.string.playlist_done, saved, NezzelFormat.count(batch.failures.size, language))
                else -> stringResource(R.string.download_transferring)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!batch.terminal) {
                    val resume = batch.paused
                    TextButton({ onAction(if (resume) PlaylistBatchAction.RESUME else PlaylistBatchAction.PAUSE) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (resume) "playlist_resume" else "playlist_pause")) {
                        Text(stringResource(if (resume) R.string.resume else R.string.playlist_pause_all))
                    }
                    TextButton({ stopping = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_stop")) {
                        Text(stringResource(R.string.playlist_stop))
                    }
                }
                if (batch.retryable > 0 && batch.record.state !in setOf(BatchState.STOPPED, BatchState.EXPIRED)) {
                    TextButton({ onAction(PlaylistBatchAction.RETRY) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_retry")) {
                        Text(stringResource(R.string.playlist_retry, NezzelFormat.count(batch.retryable, language)))
                    }
                }
                if (batch.failures.isNotEmpty()) TextButton({ why = !why }, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_why")) {
                    Text(stringResource(R.string.playlist_see_why))
                }
                TextButton(onExpand, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_expand")) {
                    Text(stringResource(if (expanded) R.string.playlist_hide_videos else R.string.playlist_show_videos))
                }
            }
            if (why) batch.failures.forEach { item ->
                Column {
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
