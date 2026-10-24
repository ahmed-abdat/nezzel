package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.LiveLeader
import dev.ahmed.savlino.jobs.running
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Only fields visible in RemoteViews participate in equality; 37% floors to the 35% bucket.
 * [more] counts the other running jobs behind the shown one ("+1"); queued ones are not counted.
 * No [title] means idle: the widget shows progress only, never a finished or failed job.
 */
data class WidgetUiState(val title: String? = null, val phase: DownloadPhase? = null,
    val percent: Int? = null, val paused: Boolean = false, val waiting: Boolean = false, val more: Int = 0) {
    companion object {
        fun from(job: DownloadProgress?, more: Int = 0): WidgetUiState = if (job == null || job.terminal) WidgetUiState() else WidgetUiState(
            title(job.filename), job.phase, (if (job.paused) job.percent else job.visiblePercent)?.let { it / 5 * 5 }, job.paused,
            job.waiting || job.awaitingSchedule, more)

        /** The newest running job, then the newest live one (queued or paused); idle otherwise. */
        fun from(jobs: List<DownloadProgress>): WidgetUiState {
            // Same leader as the Live Update notification.
            return LiveLeader.pick(jobs)?.let { from(it, jobs.count { job -> job.running } - 1) } ?: from(jobs.lastOrNull { !it.terminal })
        }

        // A trailing ".mp4"-like suffix only: "Part 1. Intro" and "v1.2" keep their dots.
        private val extension = Regex("""\.(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$""")
        internal fun title(filename: String): String = filename.trim().replace(extension, "").trim().ifBlank { filename.trim() }
    }
}

/** Process-owned observer. No timer, worker, network or clipboard work when the app is absent. */
class WidgetUpdater(
    private val context: Context,
    private val progress: StateFlow<List<DownloadProgress>>,
    private val widgetIds: () -> IntArray = {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NezzelWidgetProvider::class.java))
    },
    private val render: (WidgetUiState, IntArray) -> Unit = { state, ids -> NezzelWidgetProvider.render(context, ids, state) },
) {
    @Volatile private var cached: WidgetUiState? = null
    val currentState: WidgetUiState get() = cached ?: WidgetUiState.from(progress.value)

    fun start(scope: CoroutineScope): Job = scope.launch {
        progress.map { WidgetUiState.from(it) }
            .distinctUntilChanged()
            .onEach { cached = it }
            .conflate()
            .collect { state ->
                val ids = widgetIds()
                if (ids.isNotEmpty()) {
                    render(state, ids)
                    // Conflate during the cooldown, then deliver the latest state, including the return to idle.
                    delay(2_000)
                }
            }
    }
}
