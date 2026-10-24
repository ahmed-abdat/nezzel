package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.DownloadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*

/** Display snapshot only. Controls remain entirely in the existing coordinator/feeder. */
class PlaylistBatch(val record: BatchRecord, val items: List<BatchItemRecord>) {
    val id get() = record.id
    val total get() = items.size
    val saved get() = items.count { it.state == BatchItemState.DONE }
    val failures get() = items.filter { it.state in setOf(BatchItemState.FAILED, BatchItemState.SKIPPED) }
    val finished get() = saved + failures.size
    val retryable get() = failures.count { it.state == BatchItemState.FAILED && it.retryable }
    val terminal get() = record.state in setOf(BatchState.COMPLETED, BatchState.STOPPED, BatchState.EXPIRED)
    val paused get() = record.state in setOf(BatchState.PAUSED, BatchState.WAITING_NETWORK)
    val slowed get() = paused && record.error in setOf(DownloadError.SourceRefused, DownloadError.LoginRequired)
    internal val signature get() = listOf(record.id, record.title, record.state, record.error,
        items.map { listOf(it.position, it.title, it.state, it.jobId, it.error, it.retryable, it.fallback) })
}

/** SQLite has no invalidation stream. Poll off-main for feeder-only transitions as well as job progress and controls. */
internal fun BatchStore.observePlaylists(refresh: StateFlow<Long>): Flow<List<PlaylistBatch>> = flow {
    while (currentCoroutineContext().isActive) {
        val revision = refresh.value
        val snapshot = all().map { PlaylistBatch(it, items(it.id)) }
        emit(snapshot)
        // New confirmations/retries wake an idle observer immediately; feeder transitions still poll off-main.
        withTimeoutOrNull(if (snapshot.any { !it.terminal }) 1_000L else 30_000L) {
            refresh.first { it != revision }
        }
    }
}.distinctUntilChanged { before, after -> before.map { it.signature } == after.map { it.signature } }.flowOn(Dispatchers.IO)
