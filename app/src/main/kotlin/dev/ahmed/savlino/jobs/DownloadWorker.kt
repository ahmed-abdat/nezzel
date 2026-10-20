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
        val persistence = ProgressPersistence(coordinator.monotonicMillis) { event ->
            setProgress(workDataOf("phase" to event.phase.name, "bytes" to (event.bytes ?: -1L),
                "percent" to (event.percent ?: -1)))
            if (!event.terminal) notifications.progress(event)
        }
        val row = coordinator.execute(id, {
            if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN
        }, {
            setForeground(notifications.foreground(it))
        }, persistence::report) { event ->
            // Post first: WorkManager can reject progress updates for an already stopped worker.
            if (event.terminal) notifications.finished(event)
            if (!isStopped) persistence.report(event)
        }
        if (row?.stage == JobStage.ENQUEUED) return Result.retry()
        return if (row?.stage in setOf(JobStage.COMPLETED, JobStage.CANCELLED)) Result.success() else Result.failure()
    }
    class Factory(private val coordinator: DownloadCoordinator) : WorkerFactory() {
        override fun createWorker(context: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? =
            if (workerClassName == DownloadWorker::class.java.name) DownloadWorker(context, params, coordinator) else null
    }
    companion object { const val JOB_ID = "job_id" }
}
