package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.JobRecord
import dev.ahmed.savlino.jobs.JobStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Only fields visible in RemoteViews participate in equality; 37% floors to the 35% bucket. */
data class WidgetUiState(val title: String? = null, val phase: DownloadPhase? = null,
    val percent: Int? = null, val paused: Boolean = false, val waiting: Boolean = false) {
    companion object {
        fun from(job: DownloadProgress?): WidgetUiState = if (job == null) WidgetUiState() else WidgetUiState(
            job.filename, job.phase, job.visiblePercent?.let { it / 5 * 5 }, job.paused,
            !job.terminal && (job.waiting || job.awaitingSchedule))
    }
}

/** Process-owned observer. No timer, worker, network or clipboard work when the app is absent. */
class WidgetUpdater(
    private val context: Context,
    private val progress: StateFlow<List<DownloadProgress>>,
    private val completed: Flow<DownloadProgress> = emptyFlow(),
    /** The coordinator's own journal: a second JobStore would scrub staging without knowing its live writers. */
    private val journal: () -> List<JobRecord> = { emptyList() },
    private val widgetIds: () -> IntArray = {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NezzelWidgetProvider::class.java))
    },
    private val render: (WidgetUiState, IntArray) -> Unit = { state, ids -> NezzelWidgetProvider.render(context, ids, state) },
) {
    @Volatile private var cached: WidgetUiState? = null
    val currentState: WidgetUiState get() = cached ?: WidgetUiState.from(progress.value.lastOrNull())

    fun start(scope: CoroutineScope): Job = scope.launch {
        // The process scope runs on IO. A restart has no completion events to replay, and live
        // snapshots deliberately omit completed rows. Seed the widget without emitting a snackbar.
        val latest = LatestJob(try { journal() } catch (_: Exception) { emptyList() })
        merge(progress.map { Event.Snapshot(it) }, completed.map { Event.Finished(it) })
            .map { latest.accept(it) }
            .distinctUntilChanged()
            .onEach { cached = it }
            .conflate()
            .collect { state ->
                val ids = widgetIds()
                if (ids.isNotEmpty()) {
                    render(state, ids)
                    // Conflate during the cooldown, then deliver the latest state, including terminal states.
                    delay(2_000)
                }
            }
    }

    private sealed interface Event {
        data class Snapshot(val jobs: List<DownloadProgress>) : Event
        data class Finished(val job: DownloadProgress) : Event
    }

    /** Coordinator snapshots are in admission order. Completed jobs are removed from that list. */
    private class LatestJob(journal: List<JobRecord>) {
        private var snapshot = emptyList<DownloadProgress>()
        private var knownIds = journal.map { it.jobId }.toSet()
        // Reverse admission order breaks timestamp ties in favor of the newer admission.
        private var rememberedId: String? = null
        private var remembered = journal.asReversed().maxByOrNull { it.updatedAt }?.let { row ->
            rememberedId = row.jobId
            WidgetUiState(row.displayName ?: row.filename, when (row.stage) {
                JobStage.ENQUEUED, JobStage.PAUSED -> DownloadPhase.PREPARING
                JobStage.COMPLETED -> DownloadPhase.COMPLETED
                JobStage.CANCELLED -> DownloadPhase.CANCELLED
                else -> DownloadPhase.FAILED
            }, paused = row.stage == JobStage.PAUSED, waiting = row.stage == JobStage.ENQUEUED)
        }
        fun accept(event: Event): WidgetUiState {
            when (event) {
                is Event.Snapshot -> {
                    if (event.jobs.any { !it.terminal } ||
                        event.jobs.any { it.jobId !in knownIds } ||
                        (remembered?.phase != DownloadPhase.COMPLETED && snapshot.any { it.jobId == rememberedId } &&
                            event.jobs.none { it.jobId == rememberedId })) remembered = null
                    // Empty startup snapshots must not make recovered journal rows look newly admitted.
                    if (event.jobs.isNotEmpty()) knownIds = event.jobs.map { it.jobId }.toSet()
                    snapshot = event.jobs
                }
                is Event.Finished -> {
                    rememberedId = event.job.jobId
                    remembered = WidgetUiState.from(event.job)
                }
            }
            return remembered ?: WidgetUiState.from(snapshot.lastOrNull { !it.terminal } ?: snapshot.lastOrNull())
        }
    }
}
