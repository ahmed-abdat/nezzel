package dev.ahmed.savlino.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.BatchItemState
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.PlaylistBatch
import dev.ahmed.savlino.jobs.running
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.delay

enum class DownloadsTab { RUNNING, SAVED }

/** Something moves or will move: queued, running, paused, awaiting a schedule, or a playlist still feeding. */
internal fun hasActiveWork(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch>): Boolean =
    runningCount(jobs) > 0 || playlists.any { !it.terminal }

/** Entering Downloads opens Downloading only while something is active; failures and warnings alone open Saved. */
internal fun entryTab(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch>): DownloadsTab =
    if (hasActiveWork(jobs, playlists)) DownloadsTab.RUNNING else DownloadsTab.SAVED

/** A new active job opens Running; completion never changes the person's current sub-tab. */
internal fun nextDownloadsTab(current: DownloadsTab, newActive: Boolean): DownloadsTab =
    if (newActive) DownloadsTab.RUNNING else current

/** Work still to do in Downloading: unfinished standalone jobs plus every playlist video not saved or failed yet. */
internal fun downloadingCount(standalone: List<DownloadProgress>, playlists: List<PlaylistBatch>): Int =
    runningCount(standalone) + playlists.filter { !it.terminal }.sumOf { it.total - it.finished }

/** A finished batch stays only while it has something to retry; a clean or final one leaves after its moment. */
internal fun PlaylistBatch.keepsRow(): Boolean = !terminal || retryable > 0

/** Members shown under a batch header: moving or paused ones (at most the parallel limit). Waiting ones are counted. */
internal fun batchMemberRows(batch: PlaylistBatch, jobs: List<DownloadProgress>): List<DownloadProgress> {
    val queued = batch.items.filter { it.state == BatchItemState.QUEUED }.mapTo(HashSet()) { it.jobId }
    return jobs.filter { it.jobId in queued && !it.terminal && (it.running || it.paused) }
}

/** Waiting for a slot: queued, not paused, nothing running yet. */
internal val DownloadProgress.queuedOnly: Boolean get() = !terminal && !awaitingSchedule && waiting && !paused

internal const val BATCH_DWELL = 3000L

/** Batches that finished while this screen watched them stay for [BATCH_DWELL] with "Saved N", then leave. */
@Composable
internal fun rememberVisibleBatches(playlists: List<PlaylistBatch>): List<PlaylistBatch> {
    val seen = remember { mutableMapOf<String, Boolean>() }
    val flashing = remember { mutableStateMapOf<String, Unit>() }
    playlists.forEach { batch ->
        val before = seen.put(batch.id, batch.terminal)
        if (batch.terminal && before == false && !batch.keepsRow()) flashing[batch.id] = Unit
        if (!batch.terminal) flashing.remove(batch.id)
    }
    flashing.keys.toList().forEach { id ->
        key(id) { LaunchedEffect(id) { delay(BATCH_DWELL); flashing.remove(id) } }
    }
    return playlists.filter { it.keepsRow() || it.id in flashing }
}

/** Selected tab: entry rule once, kept across rotation, then [nextDownloadsTab] on every change. */
@Composable
internal fun rememberDownloadsTab(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch>): MutableState<DownloadsTab> {
    val tab = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(entryTab(jobs, playlists)) }
    // A job that becomes active while the screen watches (new, or the same id on a new attempt) counts as started.
    val activeNow = jobs.filter { !it.terminal || it.awaitingSchedule }.mapTo(HashSet()) { it.jobId }
    val before = remember { HashSet(activeNow) }
    val started = (activeNow - before).isNotEmpty()
    before.clear(); before += activeNow
    val next = nextDownloadsTab(tab.value, started)
    if (next != tab.value) SideEffect { tab.value = next }
    return tab
}

/** Downloading · Saved. A count on Downloading; a dot (and TalkBack "Needs a look") while a check warning is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsTabRow(selected: DownloadsTab, count: Int, attention: Boolean, language: String, onSelect: (DownloadsTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surface, divider = {}) {
        val needsLook = stringResource(R.string.check_needs_look)
        Tab(selected == DownloadsTab.RUNNING, { onSelect(DownloadsTab.RUNNING) },
            Modifier.heightIn(min = 48.dp).testTag("tab_running").then(if (attention) Modifier.semantics { stateDescription = needsLook } else Modifier),
            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
            Row(Modifier.padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.dl_tab_running), style = MaterialTheme.typography.titleSmall)
                Text(NezzelFormat.count(count.coerceAtLeast(1), language), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.graphicsLayer { alpha = if (count > 0) 1f else 0f }
                        .then(if (count == 0) Modifier.clearAndSetSemantics {} else Modifier)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        .padding(horizontal = Space.s, vertical = 2.dp))
                // Keep the count and warning slots: removing either must not unwrap the label at large text.
                Box(Modifier.size(8.dp).graphicsLayer { alpha = if (attention) 1f else 0f }
                    .background(MaterialTheme.colorScheme.error, CircleShape).then(if (attention) Modifier.testTag("tab_attention") else Modifier))
            }
        }
        Tab(selected == DownloadsTab.SAVED, { onSelect(DownloadsTab.SAVED) }, Modifier.heightIn(min = 48.dp).testTag("tab_saved"),
            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
            Text(stringResource(R.string.dl_tab_saved), Modifier.padding(vertical = Space.m), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** "N waiting" folds queued rows into one line; tapping it shows or hides them. */
@Composable
internal fun WaitingLine(count: Int, open: Boolean, language: String, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val reduced = dev.ahmed.savlino.ui.theme.LocalReducedMotion.current
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f,
        dev.ahmed.savlino.ui.theme.easeOut(reduced), label = "waiting-chevron")
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle).testTag("waiting_line")
        .semantics(mergeDescendants = true) { expandedOrCollapsed(open) }
        .padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.dl_waiting_count, NezzelFormat.count(count, language)), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, null, Modifier.size(24.dp).rotate(turn),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** TalkBack hears "expanded"/"collapsed" from the platform strings; no new copy. */
private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.expandedOrCollapsed(open: Boolean) {
    if (open) expand { false } else collapse { false }
}
