package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress

enum class BatchKind { PAUSE, RESUME, RETRY }

/** The one header action and the job IDs it acts on, snapshotted in display order at render time. */
data class BatchAction(val kind: BatchKind, val ids: List<String>)

/** Running or queued, before joining/saving (the coordinator refuses a pause from there on). */
internal val DownloadProgress.batchPausable: Boolean get() = !terminal && !awaitingSchedule && !paused &&
    phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)

internal val DownloadProgress.batchResumable: Boolean get() = paused && !terminal

/** Same test as the per-row Retry button in FailedRow. */
internal val DownloadProgress.batchRetryable: Boolean get() = phase == DownloadPhase.FAILED && (awaitingSchedule || retryable)

/**
 * One button by priority: Pause all, then Resume all, then Retry all; each needs two or more rows (the row's own button
 * covers one). [jobs] is the Downloads display order, so batch work runs in the order the user sees.
 */
fun batchAction(jobs: List<DownloadProgress>): BatchAction? = listOf(
    BatchKind.PAUSE to jobs.filter { it.batchPausable },
    BatchKind.RESUME to jobs.filter { it.batchResumable },
    BatchKind.RETRY to jobs.filter { it.batchRetryable },
).firstOrNull { (_, rows) -> rows.size >= 2 }?.let { (kind, rows) -> BatchAction(kind, rows.map { it.jobId }) }
