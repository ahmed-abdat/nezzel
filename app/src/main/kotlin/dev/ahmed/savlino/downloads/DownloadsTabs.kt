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
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.easeOut
import kotlinx.coroutines.launch
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
import dev.ahmed.savlino.jobs.BatchItemRecord
import dev.ahmed.savlino.jobs.BatchItemState
import dev.ahmed.savlino.jobs.BatchState
import dev.ahmed.savlino.jobs.DownloadPhase
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

/** Entering Downloads opens Downloading while something is active or did not save; warnings alone open Saved. */
internal fun entryTab(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch>): DownloadsTab =
    if (hasActiveWork(jobs, playlists) || failureCount(jobs, playlists) > 0) DownloadsTab.RUNNING else DownloadsTab.SAVED

/** Downloads under «Not saved»: failed and not waiting for a scheduled retry. [jobs] are the standalone ones. */
internal fun failedJobs(jobs: List<DownloadProgress>): List<DownloadProgress> =
    jobs.filter { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }

/** A playlist's videos that did not save: recorded failures plus queued ones whose job already failed. */
internal fun PlaylistBatch.failedVideos(jobs: List<DownloadProgress>): List<BatchItemRecord> =
    playlistSections(this, jobs.associateBy { it.jobId }).first

/** Playlists with a «Not saved» line: still listed and holding at least one video that did not save. */
internal fun failedPlaylists(playlists: List<PlaylistBatch>, jobs: List<DownloadProgress>): List<PlaylistBatch> =
    playlists.filter { it.keepsRow() && it.failedVideos(jobs).isNotEmpty() }

/** A playlist's own Retry: something to retry and not stopped by the person. */
internal val PlaylistBatch.canRetryFailed: Boolean get() = userRetryable > 0 && record.state !in setOf(BatchState.STOPPED, BatchState.EXPIRED)

/** Failed videos a person's Retry can resolve again: their link is still stored, whatever the automatic flag says. */
internal val PlaylistBatch.userRetryable: Int get() = items.count { it.state == BatchItemState.FAILED && it.hasSource }

/** Downloads and playlist videos not saved yet: the tab's error dot and its TalkBack count. [jobs] are all jobs. */
internal fun failureCount(jobs: List<DownloadProgress>, playlists: List<PlaylistBatch>): Int {
    val members = playlists.flatMapTo(HashSet()) { batch -> batch.items.map { it.jobId } }
    return failedJobs(jobs.filter { it.jobId !in members }).size + failedPlaylists(playlists, jobs).sumOf { it.failedVideos(jobs).size }
}

/** Retry all under «Not saved»: the rows that offer their own Retry, in display order. */
internal data class FailureRetry(val jobs: List<String>, val playlists: List<String>)

/** Null under two such rows: the row's own button covers one. Rows that cannot be retried are left out. */
internal fun failureRetry(failed: List<DownloadProgress>, playlists: List<PlaylistBatch>): FailureRetry? {
    val ids = failed.filter { it.batchRetryable }.map { it.jobId }
    val lists = playlists.filter { it.canRetryFailed }.map { it.id }
    return FailureRetry(ids, lists).takeIf { ids.size + lists.size >= 2 }
}

/** A new active job opens Running; completion never changes the person's current sub-tab. */
internal fun nextDownloadsTab(current: DownloadsTab, newActive: Boolean): DownloadsTab =
    if (newActive) DownloadsTab.RUNNING else current

/** Work still to do in Downloading: unfinished standalone jobs plus every playlist video not saved or failed yet. */
internal fun downloadingCount(standalone: List<DownloadProgress>, playlists: List<PlaylistBatch>): Int =
    runningCount(standalone) + playlists.filter { !it.terminal }.sumOf { it.total - it.finished }

/** A finished batch stays only while it has something to retry; a clean or final one leaves after its moment. */
internal fun PlaylistBatch.keepsRow(): Boolean = !terminal || userRetryable > 0

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
    // A job or playlist that becomes active while the screen watches (new, or the same id on a new attempt) counts as
    // started: a playlist still resolving has no job yet but already lives on Downloading.
    val activeNow = jobs.filter { !it.terminal || it.awaitingSchedule }.mapTo(HashSet()) { it.jobId }
    playlists.filter { !it.terminal }.mapTo(activeNow) { "playlist:${it.id}" }
    val before = remember { HashSet(activeNow) }
    val started = (activeNow - before).isNotEmpty()
    before.clear(); before += activeNow
    val next = nextDownloadsTab(tab.value, started)
    if (next != tab.value) SideEffect { tab.value = next }
    return tab
}

/**
 * Downloading · Saved. A count on Downloading; an error dot while something did not save (TalkBack "Not saved: N") or a
 * check warning is open (TalkBack "Needs a look").
 * The underline sits under the selected word itself, not under the count and dot slots kept beside it, so it is measured
 * from the label in the row's own left-to-right pixels: the same code lands under the word in Arabic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsTabRow(selected: DownloadsTab, count: Int, warning: Boolean, language: String, failed: Int = 0, onSelect: (DownloadsTab) -> Unit) {
    val attention = warning || failed > 0
    val reduced = LocalReducedMotion.current
    val row = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val labels = remember { mutableStateMapOf<DownloadsTab, Pair<Float, Float>>() }
    val textSpans = remember { mutableMapOf<DownloadsTab, Pair<Float, Float>>() }
    val positions = remember { mutableMapOf<DownloadsTab, LayoutCoordinates>() }
    fun updateLabel(tab: DownloadsTab) {
        val coordinates = positions[tab]?.takeIf { it.isAttached } ?: return
        val origin = row[0]?.takeIf { it.isAttached }?.localPositionOf(coordinates, Offset.Zero) ?: return
        val text = textSpans[tab] ?: return
        val span = origin.x + text.first to text.second
        if (labels[tab] != span) labels[tab] = span
    }
    fun Modifier.label(tab: DownloadsTab) = onGloballyPositioned {
        positions[tab] = it
        updateLabel(tab)
    }
    fun textLayout(tab: DownloadsTab, result: androidx.compose.ui.text.TextLayoutResult) {
        // The weighted label reserves space for the count; the underline follows only its actual lines.
        val start = (0 until result.lineCount).minOf(result::getLineLeft)
        val end = (0 until result.lineCount).maxOf(result::getLineRight)
        textSpans[tab] = start to end - start
        updateLabel(tab)
    }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val target = labels[selected]
    LaunchedEffect(target, reduced) {
        val (x, w) = target ?: return@LaunchedEffect
        // First placement snaps; a switch slides like the platform indicator; reduced motion moves in one frame.
        if (reduced || width.value == 0f) { left.snapTo(x); width.snapTo(w) }
        else { launch { left.animateTo(x, easeOut(false, Motion.TAB)) }; width.animateTo(w, easeOut(false, Motion.TAB)) }
    }
    val indicator = MaterialTheme.colorScheme.primary
    Box(Modifier.onGloballyPositioned { row[0] = it }.drawWithContent {
        drawContent()
        val w = width.value
        if (w <= 0f) return@drawWithContent
        // M3 primary indicator: 3dp, rounded top corners only.
        val h = 3.dp.toPx()
        clipRect(top = size.height - h) {
            drawRoundRect(indicator, Offset(left.value, size.height - h), androidx.compose.ui.geometry.Size(w, h * 2), CornerRadius(h, h))
        }
    }) {
        PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surface, indicator = {}, divider = {}) {
            val needsLook = listOfNotNull(stringResource(R.string.dl_failed_count, NezzelFormat.count(failed, language)).takeIf { failed > 0 },
                stringResource(R.string.check_needs_look).takeIf { warning }).joinToString(", ")
            Tab(selected == DownloadsTab.RUNNING, { onSelect(DownloadsTab.RUNNING) },
                Modifier.heightIn(min = 48.dp).testTag("tab_running").then(if (attention) Modifier.semantics { stateDescription = needsLook } else Modifier),
                selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                Row(Modifier.width(IntrinsicSize.Max).padding(horizontal = Space.s, vertical = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    // Balance the trailing attention slot so the label and count stay centered together.
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dl_tab_running), Modifier.weight(1f).label(DownloadsTab.RUNNING).testTag("tab_running_label"),
                        onTextLayout = { textLayout(DownloadsTab.RUNNING, it) }, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    Text(NezzelFormat.count(count.coerceAtLeast(1), language), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("tab_running_count").graphicsLayer { alpha = if (count > 0) 1f else 0f }
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
                Text(stringResource(R.string.dl_tab_saved), Modifier.padding(vertical = Space.m).label(DownloadsTab.SAVED).testTag("tab_saved_label"),
                    onTextLayout = { textLayout(DownloadsTab.SAVED, it) }, style = MaterialTheme.typography.titleSmall)
            }
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
