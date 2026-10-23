package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
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
    private val widgetIds: () -> IntArray = {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NezzelWidgetProvider::class.java))
    },
    private val render: (WidgetUiState, IntArray) -> Unit = { state, ids -> NezzelWidgetProvider.render(context, ids, state) },
) {
    @Volatile private var cached: WidgetUiState? = null
    val currentState: WidgetUiState get() = cached ?: WidgetUiState.from(progress.value.lastOrNull())

    fun start(scope: CoroutineScope): Job = scope.launch {
        val latest = LatestJob()
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
    private class LatestJob {
        private var snapshot = emptyList<DownloadProgress>()
        private var completed: DownloadProgress? = null
        fun accept(event: Event): WidgetUiState {
            when (event) {
                is Event.Snapshot -> {
                    if (event.jobs.any { next -> snapshot.none { it.jobId == next.jobId } }) completed = null
                    snapshot = event.jobs
                }
                is Event.Finished -> completed = event.job
            }
            return WidgetUiState.from(completed ?: snapshot.lastOrNull())
        }
    }
}
