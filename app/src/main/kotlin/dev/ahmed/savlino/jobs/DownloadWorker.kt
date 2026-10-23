package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.*

class DownloadWorker(context: Context, params: WorkerParameters,
    private val coordinator: DownloadCoordinator) : CoroutineWorker(context, params) {
    private val notifications = DownloadNotifications(context)
    private val jobId get() = inputData.getString(JOB_ID)
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val progress = coordinator.progress.value.firstOrNull { it.jobId == jobId }
            ?: DownloadProgress(jobId.orEmpty(), applicationContext.getString(dev.ahmed.savlino.R.string.app_name),
                DownloadPhase.PREPARING)
        return notifications.foreground(progress)
    }
    override suspend fun doWork(): Result {
        val id = jobId?.takeIf(JobStore::validJobId) ?: return Result.failure()
        try { return run(id) } finally { notifications.settled(id) }
    }
    private suspend fun run(id: String): Result {
        // When another job's worker ends, WorkManager re-promotes the service with this job's last ForegroundInfo.
        // Hand it a fresh one at each state change, so the surviving notification never steps back to "Preparing".
        var promoted: Any? = null
        suspend fun promote(event: DownloadProgress) {
            promoted = foregroundKey(event)
            setForeground(notifications.foreground(event))
        }
        val persistence = ProgressPersistence(coordinator.monotonicMillis) { event ->
            setProgress(workDataOf("phase" to event.phase.name, "bytes" to (event.bytes ?: -1L),
                "percent" to (event.percent ?: -1), "restart_epoch" to event.restartEpoch,
                "paused" to event.paused, "has_partial" to event.hasPartial))
            if (!event.terminal) notifications.progress(event)
            if (!event.terminal && !event.paused && !isStopped && foregroundKey(event) != promoted)
                try { promote(event) } catch (_: IllegalStateException) { /* Work already stopped: nothing to promote. */ }
        }
        val row = coordinator.execute(id, {
            if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN
        }, { promote(it) }, persistence::report) { event ->
            // Post first: WorkManager can reject progress updates for an already stopped worker.
            if (event.terminal) notifications.finished(event)
            if (!isStopped) persistence.report(event)
        }
        // A stopped worker's result is ignored; WorkManager reschedules it.
        if (row?.stage == JobStage.ENQUEUED)
            return if (!isStopped && coordinator.continueAfterProgress(id)) Result.success() else Result.retry()
        return if (row?.stage in setOf(JobStage.COMPLETED, JobStage.CANCELLED, JobStage.PAUSED)) Result.success() else Result.failure()
    }
    /** What the notification says in words; a percent step alone is posted, not re-promoted. */
    private fun foregroundKey(event: DownloadProgress) = listOf(event.phase, event.waiting, event.stalled == true, event.restartEpoch)
    class Factory(private val coordinator: DownloadCoordinator) : WorkerFactory() {
        override fun createWorker(context: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? =
            if (workerClassName == DownloadWorker::class.java.name) DownloadWorker(context, params, coordinator) else null
    }
    companion object { const val JOB_ID = "job_id" }
}
