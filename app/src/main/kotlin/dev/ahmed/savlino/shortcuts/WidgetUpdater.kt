package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.downloads.SavedMediaRepository
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.running
import dev.ahmed.savlino.storage.OldDownloads
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.loadMediaThumbnail
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only display fields: progress is bucketed so each byte event does not redraw every widget. */
data class WidgetJob(val id: String, val title: String, val phase: DownloadPhase, val percent: Int?,
    val paused: Boolean, val waiting: Boolean, val remainingSeconds: Long?, val mimeType: String = "video/mp4",
    val art: WidgetArt? = null) {
    val controllable get() = paused || phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)
}

/**
 * A running job's picture: the job row artwork (<=160 px square, [JobArtwork]) plus its JPEG bytes, made once per job.
 * From API 31 the bytes travel (a few KB, decoded by the launcher); older launchers get the masked bitmap.
 * Identity equality: the same object means no layout change.
 */
class WidgetArt(val bitmap: Bitmap, val jpeg: ByteArray) {
    companion object {
        fun of(bitmap: Bitmap): WidgetArt? = java.io.ByteArrayOutputStream().use { out ->
            if (bitmap.isRecycled || !bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) null
            else WidgetArt(bitmap, out.toByteArray())
        }
    }
}

data class WidgetRecent(val media: SavedMedia, val thumbnail: Bitmap? = null)

data class WidgetUiState(val jobs: List<WidgetJob> = emptyList(), val more: Int = 0,
    val savedCount: Int = 0, val recents: List<WidgetRecent> = emptyList()) {
    /** Same views, visibility and click targets: only percent, bars and time left differ. */
    fun sameLayout(other: WidgetUiState): Boolean = more == other.more && savedCount == other.savedCount &&
        recents == other.recents && jobs.size == other.jobs.size && jobs.zip(other.jobs).all { (a, b) ->
            a.copy(percent = b.percent, remainingSeconds = b.remainingSeconds) == b &&
                (a.percent == null) == (b.percent == null) && (a.remainingSeconds == null) == (b.remainingSeconds == null)
        }

    companion object {
        fun from(job: DownloadProgress?): WidgetUiState = from(listOfNotNull(job))

        /** Running first, then paused/queued, then failed. Completed/cancelled files belong to the shelf. */
        fun from(jobs: List<DownloadProgress>, saved: List<SavedMedia> = emptyList()): WidgetUiState {
            val visible = jobs.asReversed().filter { !it.terminal || it.phase == DownloadPhase.FAILED }
                .sortedBy { if (it.running) 0 else if (!it.terminal) 1 else 2 }
            return WidgetUiState(visible.take(2).map { job ->
                val exact = job.exactTransferTotal
                val measured = job.phase == DownloadPhase.DOWNLOADING && !job.paused && !job.waiting &&
                    !job.awaitingSchedule && job.stalled != true && exact != null &&
                    job.bytes?.let { it in 0 until exact } == true &&
                    job.bytesPerSecond?.let { it.isFinite() && it > 0 && it <= Long.MAX_VALUE.toDouble() } == true
                WidgetJob(job.jobId, title(job.filename), job.phase,
                    (if (job.paused) job.percent else job.visiblePercent)?.let { it / 5 * 5 },
                    job.paused, job.waiting || job.awaitingSchedule,
                    job.remainingTransferSeconds?.takeIf { measured && it > 0 }, job.mimeType)
            }, (visible.size - 2).coerceAtLeast(0), saved.size,
                saved.sortedByDescending { it.addedSeconds }.take(4).map { WidgetRecent(it) })
        }

        private val extension = Regex("""\.(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{1,5}$""")
        internal fun title(filename: String): String = filename.trim().replace(extension, "").trim().ifBlank { filename.trim() }
    }
}

/** Process-owned local observer. No worker, network, clipboard access or polling timer. */
class WidgetUpdater(
    private val context: Context,
    private val progress: StateFlow<List<DownloadProgress>>,
    private val widgetIds: () -> IntArray = {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NezzelWidgetProvider::class.java))
    },
    private val render: (WidgetUiState, IntArray) -> Unit = { state, ids -> NezzelWidgetProvider.render(context, ids, state) },
    private val renderProgress: (WidgetUiState, IntArray) -> Unit = { state, ids -> NezzelWidgetProvider.renderProgress(context, ids, state) },
    private val savedMedia: () -> Flow<List<SavedMedia>> = {
        SavedMediaRepository(context.contentResolver, context.packageName, SaveDestinations(context), OldDownloads(context))
            .observe().mapNotNull { it.getOrNull() }.catch { emit(emptyList()) }
    },
    /** The job row artwork the Downloads card shows (`JobArtwork.load`): disk only, never fetched. */
    private val artwork: suspend (String) -> Bitmap? = { null },
    private val thumbnail: suspend (SavedMedia) -> Bitmap? = { media ->
        withContext(Dispatchers.IO) {
            // Four <=192px software bitmaps: <=576 KiB, shared by all responsive layouts.
            val edge = (96 * context.resources.displayMetrics.density).toInt().coerceIn(1, 192)
            val kind = when { media.audio -> ThumbnailKind.AUDIO
                media.mimeType.startsWith("image/") -> ThumbnailKind.IMAGE
                else -> ThumbnailKind.VIDEO }
            loadMediaThumbnail(context, media.uri, kind, edge, edge)?.let { original ->
                val ratio = minOf(1f, edge.toFloat() / maxOf(original.width, original.height))
                val scaled = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1),
                    (original.height * ratio).toInt().coerceAtLeast(1), true)
                if (scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
            }
        }
    },
) {
    @Volatile private var cached: WidgetUiState? = null
    private val arts = MutableStateFlow<Map<String, WidgetArt>>(emptyMap())
    private var shown: Pair<WidgetUiState, IntArray>? = null
    private val refreshes = MutableStateFlow(0L)
    val currentState: WidgetUiState get() = cached ?: WidgetUiState.from(progress.value)

    /**
     * One picture per shown job, loaded when the job first shows and dropped when it leaves. Artwork can land after
     * the row starts (playlists, a quick confirm), so a missing one is looked for again briefly, like the Downloads card.
     */
    private suspend fun loadArtwork() = coroutineScope {
        val loading = mutableMapOf<String, Job>()
        combine(progress, refreshes) { jobs, _ ->
            WidgetUiState.from(jobs).jobs.map { it.id }.toSet() to widgetIds().isNotEmpty()
        }.distinctUntilChanged().collect { (shown, installed) ->
            (loading.keys - shown).forEach { loading.remove(it)?.cancel() }
            arts.update { it.filterKeys(shown::contains) }
            // A widget placed after the retries ran out (none was on screen then) looks again.
            val missing = shown.filter { id -> id !in arts.value && loading[id]?.isActive != true }
            if (installed) for (id in missing) loading[id] = launch {
                for (wait in ARTWORK_RETRY_MS) {
                    delay(wait)
                    if (widgetIds().isEmpty()) continue
                    val art = artwork(id)?.let(WidgetArt::of) ?: continue
                    arts.update { it + (id to art) }
                    return@launch
                }
            }
        }
    }

    /** Widget add/remove/resize must start/stop the library observer even when progress is unchanged. */
    fun refresh() { refreshes.value += 1 }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { loadArtwork() }
        var pictures = emptyMap<SavedMedia, Bitmap?>()
        val saved = combine(progress, refreshes) { _, _ -> widgetIds().isNotEmpty() }
            .distinctUntilChanged().flatMapLatest { installed ->
                if (installed) savedMedia().onStart { emit(emptyList()) } else flowOf(emptyList())
            }.map { rows ->
                val latest = rows.sortedByDescending { it.addedSeconds }.take(4)
                pictures = latest.associateWith { pictures[it] ?: thumbnail(it) }
                rows
            }
        combine(progress, saved) { jobs, files ->
            WidgetUiState.from(jobs, files).let { state ->
                state.copy(recents = state.recents.map { it.copy(thumbnail = pictures[it.media]) })
            }
        }.combine(arts) { state, pictures ->
            if (pictures.isEmpty()) state else state.copy(jobs = state.jobs.map { it.copy(art = pictures[it.id]) })
        }.distinctUntilChanged().onEach { cached = it }.conflate().collect { state ->
            val ids = widgetIds()
            if (ids.isNotEmpty()) {
                val previous = shown
                if (previous != null && previous.second.contentEquals(ids) && state.sameLayout(previous.first))
                    renderProgress(state, ids) else render(state, ids)
                shown = state to ids
                // At most one push per second; the cooldown still delivers the latest, including completion.
                delay(1_000)
            } else shown = null
        }
    }
}

private val ARTWORK_RETRY_MS = longArrayOf(0, 1_000, 3_000, 8_000)
