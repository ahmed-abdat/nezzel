package dev.ahmed.savlino.jobs

import android.content.Context
import android.os.CancellationSignal
import android.os.Environment
import androidx.work.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.report.Report
import dev.ahmed.savlino.storage.*
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One journal/recovery owner per process. Startup and every worker share the same barrier. */
class DownloadCoordinator(
    private val context: Context,
    val engine: EngineAdapter = YoutubedlEngine(context),
    private val store: JobStore = JobStore(context),
    publisher: Publisher? = null,
    private val directories: JobDirectories = JobDirectory(context.filesDir),
    private val space: SpaceGate = SpaceBudget(context.filesDir,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context) },
    internal val monotonicMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val nextProgressTick: suspend () -> Unit = { delay(250) },
    private val savedFileExists: (android.net.Uri) -> Boolean = { uri -> publishedFileExists(context, uri) },
    parallelDownloads: StateFlow<Int> = MutableStateFlow(2),
    thermalStatus: StateFlow<Int> = MutableStateFlow(android.os.PowerManager.THERMAL_STATUS_NONE),
    private val usage: UsageEvents = UsageEvents.None,
    private val probe: dev.ahmed.savlino.media.MediaProbe = dev.ahmed.savlino.media.AndroidMediaProbe(context),
    private val checkTimeoutMs: Long = CHECK_TIMEOUT_MS,
    private val previewArtwork: suspend (String) -> android.graphics.Bitmap? = { dev.ahmed.savlino.ui.ThumbnailLoader().load(it) },
    private val checkGraceMs: Long = CHECK_GRACE_MS,
    private val network: NetworkWatch = NetworkWatch.Android(context),
    probeThreads: java.util.concurrent.ThreadPoolExecutor? = null, // tests: a pool no other coordinator can starve
) {
    /** Additive batch hooks: per-job admission, recovery and publication remain owned below. */
    val batches by lazy { BatchStore(store) }
    val batchFeeder by lazy { BatchFeeder(batches, engine, object : BatchJobActions {
        override suspend fun enqueue(command: DownloadCommand, preset: Preset) = this@DownloadCoordinator.enqueue(command, preset)
        override suspend fun pause(id: String) = this@DownloadCoordinator.pause(id)
        override suspend fun resume(id: String) = this@DownloadCoordinator.resume(id)
        override suspend fun cancel(id: String) { this@DownloadCoordinator.cancel(id) }
        override suspend fun retry(id: String) = this@DownloadCoordinator.retry(id)
        override suspend fun removeFailed(id: String) {
            // The old worker must finish delivering progress before its replaced row is removed.
            activeDone[id]?.await()
            this@DownloadCoordinator.removeFailed(id)
        }
    }, parallelDownloads, thermalStatus) }

    private val playlistRefresh = MutableStateFlow(0L)
    /** Called by the later sheet only after its Download-N tap. */
    suspend fun confirmBatch(id: String, title: String, preset: Preset, items: List<PlaylistItem>): Boolean = withContext(Dispatchers.IO) {
        initialize()
        batches.create(id, title, preset, items).also {
            if (it) {
                playlistRefresh.update { revision -> revision + 1 }; batchFeeder.wake()
                artworkScope.launch { saveBatchArtwork(store.artwork, batches.items(id), items, previewArtwork) }
            }
        }
    }
    /** Pasted/shared links after the multi-link sheet's one Download tap: the same batch machinery, no preview artwork. */
    suspend fun confirmLinks(id: String, title: String, preset: Preset, links: List<ValidatedLink>): Boolean = withContext(Dispatchers.IO) {
        initialize()
        batches.createLinks(id, title, links, preset).also {
            if (it) { playlistRefresh.update { revision -> revision + 1 }; batchFeeder.wake() }
        }
    }
    /** Playlist rows never pass through an options sheet; their artwork follows the confirm, off the feeder's path. */
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A Download tapped before the sheet's preview drew: fetch that preview now, unless the sheet's arrived meanwhile. */
    private suspend fun saveLateArtwork(jobId: String, url: String) {
        if (store.artwork.has(jobId)) return
        val bitmap = previewArtwork(url) ?: return
        try { if (!store.artwork.has(jobId)) JobArtwork.encode(bitmap)?.let { store.artwork.save(jobId, it) } } finally { bitmap.recycle() }
    }
    /** Read-only presentation stream; feeder controls and per-job behavior are unchanged. */
    val playlistBatches get() = batches.observePlaylists(playlistRefresh)
    // Controls run SQLite scans/transactions: keep them off the main thread whoever calls them.
    suspend fun pauseBatch(id: String) = withContext(Dispatchers.IO) { batchFeeder.pause(id) }
    suspend fun resumeBatch(id: String) = withContext(Dispatchers.IO) { batchFeeder.resume(id) }
    suspend fun stopBatch(id: String) = withContext(Dispatchers.IO) { batchFeeder.stop(id) }
    /** A person's Retry: the batch's failed videos, or the one at [position] (a row in its sheet). */
    suspend fun retryBatch(id: String, position: Int? = null) =
        withContext(Dispatchers.IO) { batchFeeder.retry(id, position) }.also { playlistRefresh.update { revision -> revision + 1 } }

    /** Replay durable batch controls before startup admits any worker; publications have already recovered. */
    private fun restoreBatchControls() {
        batches.scrubExpired()
        for (batch in batches.all()) {
            if (batch.state !in setOf(BatchState.STOPPED, BatchState.PAUSED, BatchState.WAITING_NETWORK)) continue
            for (item in batches.items(batch.id)) {
                if (batch.state == BatchState.STOPPED) store.cancelEnqueued(item.jobId)
                else store.pause(item.jobId)
            }
        }
    }

    val maintenance = EngineMaintenance(context, engine, {
        active.isNotEmpty() || store.all().any { !it.stage.terminal }
    }, hasRunningDownloads = {
        active.isNotEmpty() || store.all().any { !it.stage.terminal && it.stage != JobStage.PAUSED }
    })
    /** Only paused rows wait: a manual engine update may proceed once the user accepts they start over. */
    fun onlyPausedWaiting(): Boolean = active.isEmpty() &&
        store.all().filter { !it.stage.terminal }.let { rows -> rows.isNotEmpty() && rows.all { it.stage == JobStage.PAUSED } }
    /** Durable rows also cover queued playlist items that have not reached the progress stream yet. */
    internal suspend fun hasQueuedOrRunning(): Boolean = withContext(Dispatchers.IO) {
        active.isNotEmpty() || store.all().any { !it.stage.terminal && it.stage != JobStage.PAUSED } ||
            batches.all().any { it.state == BatchState.ACTIVE || it.state == BatchState.WAITING_NETWORK }
    }
    /** No generated toString: the report link exists only until terminal accounting consumes this attempt. */
    private class AttemptAccounting(link: String?, clock: () -> Long) {
        val siteKey = UsageKeys.site(link)
        @Volatile var reportLink: String? = link
        @Volatile var reason = ExecutionFailureReason.UNKNOWN
        val transfer = AttemptTransferSummary(clock)
    }
    private val accounting = ConcurrentHashMap<String, AttemptAccounting>()
    private val savedProbes = probeThreads?.let(::SavedProbeExecutor) ?: SavedProbeExecutor()

    private val recovery = JobRecovery(store, publisher ?: DestinationPublisher(context, space), File(context.filesDir, "downloads"))
    private val startup = Mutex()
    private val transfer = TransferSlots(parallelDownloads, thermalStatus)
    private val spaceLedger = TransferSpaceLedger { stagingBytes(stagingRoot(it)) }
    @Volatile private var initialized = false
    private val userCancellation = ConcurrentHashMap.newKeySet<String>()
    private val pauseRequests = ConcurrentHashMap.newKeySet<String>()
    private val activeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val active = ConcurrentHashMap<String, CancellationSignal>()
    /** Post-processing has started: the staged output is complete and a pause would discard it. */
    private val postProcessing = ConcurrentHashMap.newKeySet<String>()
    /** Requeued with more bytes on disk than this attempt started with. */
    private val progressed = ConcurrentHashMap.newKeySet<String>()
    /** Requeued because the network went away: the next attempt waits behind the network gate, never a retry. */
    private val networkWait = ConcurrentHashMap.newKeySet<String>()
    /** Consecutive attempts that passed the network gate but found no usable network. */
    private val offlineStarts = ConcurrentHashMap<String, Int>()
    private val mutableProgress = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val progress: StateFlow<List<DownloadProgress>> = mutableProgress.asStateFlow()
    private val finishedEvents = kotlinx.coroutines.flow.MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 100)
    val completed: kotlinx.coroutines.flow.SharedFlow<DownloadProgress> = finishedEvents
    private val cancellationEvents = kotlinx.coroutines.flow.MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 100)
    val cancelled: kotlinx.coroutines.flow.SharedFlow<DownloadProgress> = cancellationEvents
    private val mutableWarnings = MutableStateFlow<List<CheckWarning>>(emptyList())
    /** "Needs a look": saved files whose check found a problem, newest first. */
    val warnings: StateFlow<List<CheckWarning>> = mutableWarnings.asStateFlow()
    private val mutableError = MutableStateFlow<DownloadError?>(null)
    val error: StateFlow<DownloadError?> = mutableError.asStateFlow()

    init {
        store.onTerminalSource = { row, stage ->
            // Startup replay has no live predecessor and must neither count nor retain its sources.
            if (active.containsKey(row.jobId) || mutableProgress.value.any { it.jobId == row.jobId && (!it.terminal || it.awaitingSchedule) }) {
                val attempt = accounting.getOrPut(row.jobId) { AttemptAccounting(row.sourceUrl, monotonicMillis) }
                if (stage != JobStage.FAILED) attempt.reportLink = null
            }
        }
        store.onExpired = { row ->
            transfer.removeWaiting(row.jobId)
            // Scrub holds the store monitor. Publish without the coordinator's update monitor:
            // update() reads the store in the opposite order on native callback threads.
            val expired = fromRecord(row)
            // Aging out is a local confirmation timeout, not a failed site/transfer or a reportable link.
            if (row.error == DownloadError.FormatExpired) accounting.remove(row.jobId)?.reportLink = null
            mutableProgress.update { old ->
                if (old.any { it.jobId == row.jobId && it.restartEpoch > row.restartEpoch }) old
                else if (old.any { it.jobId == row.jobId }) old.map { if (it.jobId == row.jobId) expired else it }
                else old + expired
            }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineExceptionHandler { _, _ ->
        // Keep diagnostics out of UI/logs: SQLite errors may contain private row data.
        mutableError.value = DownloadError.ExecutionRestricted
    }) { initialize() }

    suspend fun initialize() {
        if (initialized) return
        maintenance.withDownload { initializeUnderMaintenanceGate() }
    }

    /** Only callers holding a maintenance download permit may initialize/recover/schedule. */
    private suspend fun initializeUnderMaintenanceGate() = withContext(Dispatchers.IO) {
        startup.withLock {
            if (initialized) return@withLock
            store.sweepInfoJson()
            // An ffmpeg a killed or replaced process left behind would keep writing into a job dir recovery adopts.
            engine.stopStaleProcesses(File(context.filesDir.canonicalFile, "downloads"))
            recovery.recover() // Never recover the whole journal after this barrier opens.
            restoreBatchControls()
            for (row in store.all()) {
                if (row.stage == JobStage.ENQUEUED) {
                    updateQueue(fromRecord(row))
                    try { schedule(row.jobId) }
                    catch (_: Exception) { updateQueue(scheduleFailed(row)) }
                }
                else if (row.stage.terminal || row.stage == JobStage.PAUSED) update(fromRecord(row))
            }
            initialized = true
            mutableError.value = null
        }
    }

    /** Row artwork for the Downloads tile: `artwork.load(jobId)`, null → kind icon. */
    val artwork: JobArtwork get() = store.artwork

    suspend fun enqueue(command: DownloadCommand, preset: Preset? = null): Boolean = withContext(Dispatchers.IO) {
        maintenance.withDownload {
            initializeUnderMaintenanceGate()
            val inserted = store.enqueue(command, preset)
            if (inserted) {
                val bytes = SheetArtwork.take(command.inspectionId)
                if (bytes != null) store.artwork.save(command.jobId, bytes)
                else SheetArtwork.sourceOf(command.inspectionId)?.let { url -> artworkScope.launch { saveLateArtwork(command.jobId, url) } }
            }
            // A scheduling failure leaves a durable ENQUEUED row for startup to retry.
            if (inserted) {
                usage.count(UsageKeys.download(UsageKeys.Download.STARTED, command.format))
                store.prepareInfoJson(command.jobId) { engine.prepareDownload(command) }
                val row = store.get(command.jobId)!!
                updateQueue(fromRecord(row))
                try { schedule(command.jobId) }
                catch (_: Exception) { updateQueue(scheduleFailed(row)) }
            }
            inserted
        }
    }

    /** Completed downloads of this link whose file is still there, keyed by format id. Never blocks a new download. */
    suspend fun savedBefore(normalizedUrl: String): Map<String, SavedBefore> = withContext(Dispatchers.IO) {
        try { store.savedFormats(normalizedUrl).filterValues { savedFileExists(it.uri) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
    }

    /**
     * A gate that waits for the network, then a runner without constraints: a network loss can no longer make
     * WorkManager stop a convert or a save. Cancelling the unique work cancels both links.
     */
    private fun enqueueChain(id: String, policy: ExistingWorkPolicy) {
        val gate = OneTimeWorkRequestBuilder<NetworkGateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        val runner = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf(DownloadWorker.JOB_ID to id)).build()
        workManager().beginUniqueWork(id, policy, gate).then(runner).enqueue().result.get()
    }

    private fun schedule(id: String) {
        if (store.get(id)?.stage != JobStage.ENQUEUED) return
        enqueueChain(id, ExistingWorkPolicy.KEEP)
        // A pause can win while an already-admitted scheduler is waiting on WorkManager.
        if (store.get(id)?.stage == JobStage.PAUSED) workManager().cancelUniqueWork(id).result.get()
    }

    /**
     * A requeue that kept more bytes than its attempt started with continues as fresh work after this one
     * succeeds, so exponential backoff only grows across attempts without progress. A network requeue always
     * continues behind the gate: an unconstrained retry would spin while offline. False: use Result.retry().
     */
    internal fun continueAfterProgress(id: String): Boolean {
        val waitsForNetwork = networkWait.remove(id)
        if (!(progressed.remove(id) || waitsForNetwork) || store.get(id)?.stage != JobStage.ENQUEUED) return false
        return try {
            enqueueChain(id, ExistingWorkPolicy.APPEND_OR_REPLACE)
            true
        } catch (_: Exception) { false }
    }

    /** Tests synchronize on the stop signal, never on engine.cancel (unregistered IDs are not cancelled). */
    @androidx.annotation.VisibleForTesting internal fun stopRequested(id: String) = active[id]?.isCanceled == true
    @androidx.annotation.VisibleForTesting internal fun isRunning(id: String) = active.containsKey(id)

    /** Immediate signal only: never delete staging while an engine or copy may still be writing. */
    fun requestCancellation(id: String) {
        if (!JobStore.validJobId(id)) return
        synchronized(userCancellation) { userCancellation += id }
        transfer.removeWaiting(id)
        stopEngine(id)
    }
    private fun stopEngine(id: String, cancelUnregistered: Boolean = true) {
        active[id]?.cancel()
        if (cancelUnregistered || id in downloading) engine.cancel(id)
    }

    private suspend fun rescheduleUnderMaintenanceGate(id: String) = withContext(Dispatchers.IO) {
        initializeUnderMaintenanceGate()
        val row = store.get(id) ?: return@withContext
        if (row.stage != JobStage.ENQUEUED) return@withContext
        updateQueue(fromRecord(row))
        try {
            schedule(id)
        } catch (_: Exception) { updateQueue(scheduleFailed(row)) }
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        requestCancellation(id)
        initialize()
        // Running work owns its cleanup after engine exit. WorkManager also interrupts its coroutine.
        if (!active.containsKey(id)) {
            // CAS only queued work: a worker may have registered since the first lookup.
            store.cancelEnqueued(id)
            val running = active[id]
            if (running != null) running.cancel()
            else {
                store.get(id)?.let { update(fromRecord(it)) }
                userCancellation.remove(id)
                pauseRequests.remove(id)
                offlineStarts.remove(id)
            }
        }
        try { workManager().cancelUniqueWork(id).result.get() }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted }
    }

    suspend fun pause(id: String): Boolean = pauseOne(id).also { if (it) usage.count(UsageKeys.PAUSE) }
    private suspend fun pauseOne(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        val paused = synchronized(userCancellation) {
            if (id in userCancellation || id in postProcessing || !store.pause(id)) false
            else { pauseRequests += id; stopEngine(id, cancelUnregistered = false); true }
        }
        if (!paused) return@withContext false
        transfer.removeWaiting(id)
        if (!active.containsKey(id)) store.pausedPartial(id, stagingBytes(stagingRoot(id)) > 0)
        store.get(id)?.let { update(fromRecord(it)) }
        try { workManager().cancelUniqueWork(id).result.get() }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted }
        true
    }

    suspend fun resume(id: String): Boolean = resumeOne(id).also { if (it) usage.count(UsageKeys.RESUME) }
    private suspend fun resumeOne(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        val row = store.get(id) ?: return@withContext false
        if (row.stage != JobStage.PAUSED) {
            if (row.stage.terminal) update(fromRecord(row))
            return@withContext false
        }
        try {
            val finished = withTimeoutOrNull(5000) {
                activeDone[id]?.await()
                workManager().getWorkInfosForUniqueWorkFlow(id).first { infos -> infos.all { it.state.isFinished } }
                true
            } ?: false
            if (!finished) { mutableError.value = DownloadError.ExecutionRestricted; return@withContext false }
            maintenance.withDownload {
                synchronized(userCancellation) {
                    if (!store.resume(id)) {
                        store.get(id)?.takeIf { it.stage.terminal }?.let { update(fromRecord(it)) }
                        return@withDownload false
                    }
                    pauseRequests.remove(id)
                }
                mutableError.value = null
                rescheduleUnderMaintenanceGate(id)
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted; false }
    }

    /** Batch forms of the per-row calls, over a snapshot of IDs in display order; each returns how many rows changed. */
    suspend fun pauseAll(ids: List<String>): Int = withContext(Dispatchers.IO) { ids.count { pauseOne(it) } }
        .also { if (it > 0) usage.count(UsageKeys.PAUSE_ALL) }
    suspend fun resumeAll(ids: List<String>): Int = withContext(Dispatchers.IO) { ids.count { resume(it) } }
    suspend fun retryAll(ids: List<String>): Int = withContext(Dispatchers.IO) { ids.count { retry(it) } }

    suspend fun execute(id: String, foreground: suspend (DownloadProgress) -> Unit,
        report: suspend (DownloadProgress) -> Unit): JobRecord? = execute(id,
            { WorkInfo.STOP_REASON_NOT_STOPPED }, foreground, report, report)

    suspend fun execute(id: String, stopReason: () -> Int,
        foreground: suspend (DownloadProgress) -> Unit, report: suspend (DownloadProgress) -> Unit,
        finished: suspend (DownloadProgress) -> Unit): JobRecord? = withContext(Dispatchers.IO) {
        initialize()
        // WorkManager may rerun this id while the stopped attempt still unwinds; its row returns to ENQUEUED first.
        activeDone[id]?.let { done ->
            // A stop in SAVING lets the copy finish: this rerun holds the foreground so the copy is not frozen or killed.
            store.get(id)?.takeIf { it.stage in setOf(JobStage.STAGED, JobStage.PENDING_INSERTED, JobStage.PUBLISHING) }?.let {
                try { foreground(fromRecord(it).copy(phase = DownloadPhase.SAVING, waiting = false)) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Best effort. */ }
            }
            done.await()
        }
        val row = queuedRow(id) ?: return@withContext null
        if (row.stage != JobStage.ENQUEUED) return@withContext waitingResult(id, finished)
        var completion: Pair<JobRecord, DownloadProgress>? = null
        val attempt = AttemptAccounting(row.sourceUrl, monotonicMillis)
        try {
            transfer.withSlot(id, row.createdAt, eligible = { queuedRow(id)?.stage == JobStage.ENQUEUED }) {
                try {
                    maintenance.withDownload {
                        executeWithMaintenanceGate(id, stopReason, foreground, report, finished, attempt) { saved, progress ->
                            completion = saved to progress
                        }
                    }
                } finally { withContext(NonCancellable) { spaceLedger.release(id) } }
            } ?: waitingResult(id, finished)
        } finally {
            try {
                completion?.let { (saved, progress) -> withContext(NonCancellable) {
                    // Writer ownership, native maintenance and the transfer slot are already free.
                    val verdict = try {
                        val check = checkSavedAsync(saved, progress.bytes)
                        withTimeoutOrNull(minOf(checkGraceMs, checkTimeoutMs)) { check.await() }
                    } catch (_: Exception) { null } // Optional verification cannot undo committed completion.
                    val final = progress.copy(check = verdict)
                    update(final)
                    finished(final)
                } }
            } finally {
                accounting.remove(id, attempt)
                attempt.reportLink = null
            }
        }
    }

    private fun queuedRow(id: String): JobRecord? = synchronized(userCancellation) {
        // A received cancel wins expiry's scrub, including the read needed for slot ordering.
        if (id in userCancellation) store.cancelEnqueued(id)
        store.get(id)
    }

    private suspend fun waitingResult(id: String, finished: suspend (DownloadProgress) -> Unit): JobRecord? {
        val row = queuedRow(id) ?: return null
        if (row.stage.terminal && (row.error == DownloadError.FormatExpired || id in userCancellation)) {
            val result = fromRecord(row)
            update(result)
            withContext(NonCancellable) { finished(result) }
            userCancellation.remove(id)
        }
        return row
    }

    private suspend fun executeWithMaintenanceGate(id: String, stopReason: () -> Int,
        foreground: suspend (DownloadProgress) -> Unit, report: suspend (DownloadProgress) -> Unit,
        finished: suspend (DownloadProgress) -> Unit, attempt: AttemptAccounting,
        committed: (JobRecord, DownloadProgress) -> Unit): JobRecord? = withContext(Dispatchers.IO) {
        initializeUnderMaintenanceGate()
        val signal = CancellationSignal()
        // Register before reading the row: a later pause/cancel reaches this attempt and resume waits for it.
        synchronized(userCancellation) {
            if (active.putIfAbsent(id, signal) != null) return@withContext store.get(id)
            activeDone[id] = CompletableDeferred()
            JobStore.liveWriters += id
            if (id in userCancellation) store.cancelEnqueued(id)
            if (id in userCancellation || id in pauseRequests) signal.cancel()
        }
        progressed.remove(id); networkWait.remove(id)
        var startBytes = Long.MAX_VALUE
        var schedulerStopped = false
        // This attempt's own network watch stopped the transfer: the same outcome as a connectivity stop.
        val networkStopped = java.util.concurrent.atomic.AtomicBoolean()
        // Staged and publishing: a scheduler stop lets the local copy finish instead of discarding the file.
        val saving = java.util.concurrent.atomic.AtomicBoolean()
        fun stoppedError(): DownloadError {
            if (id in userCancellation) return DownloadError.Cancelled
            if (networkStopped.get()) return DownloadError.Network
            val reason = stopReason()
            return when {
                connectivityStopped(reason) -> DownloadError.Network
                BatterySettings.batteryStopped(reason) -> DownloadError.StoppedByBattery
                else -> DownloadError.Interrupted
            }
        }
        fun finishStopped() {
            schedulerStopped = stopReason() != WorkInfo.STOP_REASON_NOT_STOPPED
            finishStopped(id, stoppedError(), systemStopped = true, startBytes = startBytes, keepOutput = id in postProcessing)
            if (networkStopped.get() && store.get(id)?.stage == JobStage.ENQUEUED) networkWait += id
        }
        try {
            // A pause cancelled this WorkManager attempt before it registered; resume scheduled a new one.
            currentCoroutineContext().ensureActive()
            val row = store.get(id) ?: return@withContext null
            if (row.stage != JobStage.ENQUEUED) {
                if (row.error == DownloadError.FormatExpired || id in userCancellation) {
                    val result = fromRecord(row)
                    update(result)
                    withContext(NonCancellable) { finished(result) }
                    userCancellation.remove(id)
                }
                return@withContext row
            }
            accounting[id] = attempt
            // Connectivity retries may outlive startup recovery in the same process.
            if (System.currentTimeMillis() - row.createdAt > JobStore.CONFIRMATION_LIFETIME_MS) {
                // Linearize cancellation with expiry's terminal write. A received user cancel wins.
                synchronized(userCancellation) {
                    if (id in userCancellation) store.cancel(id)
                    else store.fail(id, DownloadError.FormatExpired)
                }
                val expired = store.get(id) ?: return@withContext null
                val result = fromRecord(expired)
                update(result)
                withContext(NonCancellable) { finished(result) }
                return@withContext expired
            }
            coroutineScope {
                // A sibling receives cancellation even while publication blocks on Dispatchers.IO.
                val stop = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { if (!saving.get()) stopEngine(id, cancelUnregistered = false) }
                }
                val metrics = TransferMetrics(monotonicMillis)
                val delivery = ProgressDelivery(monotonicMillis, ::update)
                var transferOpen = true
                val consumer = launch { delivery.consume(report) }
                val ticker = launch {
                    while (isActive) {
                        nextProgressTick()
                        delivery.refresh { event ->
                            val measured = metrics.tick()
                            event.copy(bytesPerSecond = measured.bytesPerSecond,
                                remainingTransferSeconds = measured.remainingTransferSeconds, stalled = measured.stalled)
                        }
                    }
                }
                fun emit(event: DownloadProgress) = synchronized(delivery) {
                    if (event.phase in setOf(DownloadPhase.JOINING, DownloadPhase.CONVERTING, DownloadPhase.SAVING)) {
                        transferOpen = false
                        postProcessing += id
                    }
                    if (event.phase != DownloadPhase.DOWNLOADING) metrics.clear()
                    delivery.offer(event)
                }
                // Loss while transferring stops this attempt once; loss while converting or saving is ignored.
                val networkLost = network.watch {
                    synchronized(delivery) {
                        if (transferOpen && networkStopped.compareAndSet(false, true)) stopEngine(id, cancelUnregistered = false)
                    }
                }
                var outputBytes: Long? = null
                try {
                    // Old retries and backoff reruns can start while offline: wait behind the gate instead. A gate that
                    // keeps passing while this process sees no network (background restriction) would spin: after
                    // OFFLINE_GATE_PASSES the runner backs off through WorkManager's retry instead.
                    if (!network.online()) {
                        if (offlineStarts.merge(id, 1, Int::plus)!! <= OFFLINE_GATE_PASSES) {
                            networkStopped.set(true)
                            finishStopped()
                        } else finishStopped(id, DownloadError.Network, systemStopped = true)
                        return@coroutineScope
                    }
                    offlineStarts.remove(id)
                    var command = row.command() ?: error("Invalid job command")
                    attempt.reason = ExecutionFailureReason.ENGINE_RUN
                    val engineVersion = engine.installedVersion() ?: row.engineVersion
                    // An app update alone keeps the bytes: only the engine, the argv or the transfer engine matter.
                    val baseRecipe = "$engineVersion:${YtdlpCommand.ARGV_VERSION}"
                    val transferEngine = engine.resumeEngine(command, stagingRoot(id))
                    val recipe = "$baseRecipe:engine=$transferEngine"
                    // Older recipes start with the app version code; it no longer decides compatibility.
                    val stored = row.resumeRecipe?.let { if (it.substringBefore(':').toIntOrNull() != null) it.substringAfter(':') else it }
                    // One-time migration: only pre-native jobs can lack an engine marker. Preserve
                    // their bytes with the same yt-dlp/argv; new jobs always write engine=.
                    // Native fallback ownership is also durable in staging.
                    val legacyRecipe = stored == baseRecipe
                    val nativeFallback = stored == "$baseRecipe:engine=native" &&
                        DirectResume.read(stagingRoot(id))?.native == false
                    val compatibleRecipe = stored == recipe ||
                        (transferEngine == "ytdlp" && (legacyRecipe || nativeFallback))
                    val adopting = row.restartEpoch > 0 || row.hasPartial
                    attempt.reason = ExecutionFailureReason.STORAGE_STAGING
                    // A stopped attempt's ffmpeg must not outlive any cleanup or adopt of its staging: one that
                    // survives KILL defers this attempt and leaves the directory alone.
                    if (engine.stopStaleProcesses(stagingRoot(id)) > 0) {
                        finishStopped(id, DownloadError.Interrupted, systemStopped = true, keepOutput = true, staleWriter = true)
                        return@coroutineScope
                    }
                    if (adopting && !compatibleRecipe) {
                        check(cleanupJobStaging(File(context.filesDir.canonicalFile, "downloads"), id))
                        check(store.discardInfoJson(id))
                    }
                    if (engineVersion != command.engineVersion) {
                        // Cached signed URLs belong to the previous yt-dlp: extract again with this one.
                        check(store.discardInfoJson(id))
                        command = (DownloadCommand.confirm(id, Inspection(command.source, engineVersion, listOf(command.format)),
                            command.source, command.format.id, row.filename.substringBeforeLast('.')) as ConfirmResult.Confirmed).command
                    }
                    if (!store.startTransfer(id, recipe)) return@coroutineScope
                    var epoch = store.get(id)!!.restartEpoch
                    var engineEpoch = 0L
                    var checkedAt: Long? = null
                    var stale = false
                    var engineReturned = false
                    var hasPartial = row.hasPartial && compatibleRecipe
                    val initial = DownloadProgress(id, row.filename, DownloadPhase.PREPARING,
                        restartEpoch = epoch, hasPartial = hasPartial,
                        mimeType = mime(command.format), qualityHeight = VideoQuality.of(command.format.width, command.format.height))
                    emit(initial)
                    attempt.reason = ExecutionFailureReason.FOREGROUND_DENIED
                    foreground(initial) // Foreground promotion must succeed before any transfer.
                    attempt.reason = ExecutionFailureReason.STORAGE_STAGING
                    signal.throwIfCanceled()
                    currentCoroutineContext().ensureActive()
                    val job = if (adopting) directories.createOrAdopt(id, YtdlpCommand.finishedSource(command.format).takeIf { compatibleRecipe })
                        else directories.create(id)
                    val partialBytes = stagingBytes(job.directory)
                    hasPartial = partialBytes > 0
                    startBytes = partialBytes
                    store.transferPartial(id, hasPartial)
                    spaceLedger.reserve(id, space, command.format, partialBytes)
                    synchronized(userCancellation) {
                        signal.throwIfCanceled()
                        check(store.get(id)?.stage == JobStage.TRANSFERRING)
                        downloading += id
                    }
                    attempt.reason = ExecutionFailureReason.ENGINE_RUN
                    val result = try { engine.download(command, job.directory) { event ->
                        synchronized(delivery) {
                            // Late callbacks from a finished attempt never reach the next one.
                            if (engineReturned || event is EngineEvent.Progress && event.restartEpoch != engineEpoch) return@synchronized
                            // Pause and cancel arrive through the signal; read the row (expiry, epoch) at most once a second.
                            val now = monotonicMillis()
                            if (!stale && checkedAt.let { it == null || now - it !in 0 until 1000 }) {
                                checkedAt = now
                                val current = store.get(id)
                                stale = current == null || current.restartEpoch != epoch || current.stage != JobStage.TRANSFERRING
                                if (current?.error == DownloadError.FormatExpired) stopEngine(id, cancelUnregistered = false)
                            }
                            if (stale) return@synchronized
                            if (event is EngineEvent.Restarting) {
                                if (event.restartEpoch <= engineEpoch) return@synchronized
                                signal.throwIfCanceled()
                                epoch = store.restartTransfer(id) ?: return@synchronized
                                engineEpoch = event.restartEpoch
                                hasPartial = false
                                startBytes = 0
                                transferOpen = true
                                postProcessing -= id
                                metrics.clear()
                                attempt.transfer.reset()
                                emit(initial.copy(restartEpoch = epoch, hasPartial = false))
                                return@synchronized
                            }
                            // Byte callbacks arriving after the post-processing barrier are no longer transfer work.
                            if (event is EngineEvent.Progress && !transferOpen) {
                                signal.throwIfCanceled()
                                return@synchronized
                            }
                            if (event is EngineEvent.Progress) {
                                dev.ahmed.savlino.ui.EngineTiming.firstDownloadBytes(id, event.bytesDownloaded)
                            }
                            signal.throwIfCanceled()
                            emit(when (event) {
                                is EngineEvent.Progress -> {
                                    attempt.transfer.observe(event.bytesDownloaded)
                                    val measured = metrics.observe(event.bytesDownloaded, event.total)
                                    initial.copy(phase = DownloadPhase.DOWNLOADING, restartEpoch = epoch, hasPartial = hasPartial,
                                        bytes = event.bytesDownloaded.takeIf { it >= 0 }, total = event.total,
                                        fragmentIndex = event.fragmentIndex, fragmentCount = event.fragmentCount,
                                        bytesPerSecond = measured.bytesPerSecond,
                                        remainingTransferSeconds = measured.remainingTransferSeconds, stalled = measured.stalled)
                                }
                                EngineEvent.Merging -> initial.copy(restartEpoch = epoch, hasPartial = hasPartial, phase = if (command.format.conversion != null)
                                    DownloadPhase.CONVERTING else DownloadPhase.JOINING)
                                is EngineEvent.Restarting -> error("Restart handled above")
                            })
                        }
                    } } finally {
                        // Closed with the engine: a network loss after this never stops (and requeues) finished work.
                        synchronized(delivery) { engineReturned = true; transferOpen = false }
                        downloading.remove(id)
                    }
                    if (result is TransferResult.Failure) attempt.reason = result.reason
                    when (result) {
                        is TransferResult.Failure -> if (signal.isCanceled || result.error == DownloadError.Cancelled) finishStopped()
                            else finishStopped(id, result.error, resumeAllowed = result.resumeAllowed,
                                startBytes = startBytes, cause = result.cause)
                        is TransferResult.Success -> {
                            attempt.reason = ExecutionFailureReason.STORAGE_STAGING
                            // Single-stream output has no Merging event: close pause here so a resume never drops a finished file.
                            synchronized(delivery) { transferOpen = false; postProcessing += id }
                            currentCoroutineContext().ensureActive()
                            synchronized(userCancellation) {
                                signal.throwIfCanceled()
                                outputBytes = job.requireOutput(result.output.file).length()
                                check(store.markStaged(id))
                                saving.set(true) // User cancel still reaches the copy through requestCancellation.
                            }
                            emit(initial.copy(phase = DownloadPhase.SAVING, restartEpoch = epoch))
                            recovery.publish(job, job.requireOutput(result.output.file), mime(command.format), signal,
                                cancellationError = ::stoppedError)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    stopEngine(id, cancelUnregistered = false)
                    withContext(NonCancellable) {
                        finishStopped()
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    if (signal.isCanceled) finishStopped()
                    else {
                        val error = when {
                            TransferSpacePolicy.isNoSpace(failure) -> DownloadError.InsufficientSpace
                            failure is StorageException -> when (failure.error) {
                                StorageError.FolderUnavailable -> DownloadError.FolderUnavailable
                                StorageError.InsufficientSpace -> DownloadError.InsufficientSpace
                                StorageError.UnknownSizeRequiresBound -> DownloadError.ExecutionRestricted
                                else -> DownloadError.SaveFailed
                            }
                            failure is ArithmeticException -> DownloadError.InsufficientSpace
                            else -> DownloadError.ExecutionRestricted
                        }
                        finishStopped(id, error)
                    }
                } finally {
                    withContext(NonCancellable) {
                        // A callback already queued when the watch closes must not stop the next attempt (stopEngine is per id).
                        synchronized(delivery) { transferOpen = false }
                        networkLost.close()
                        stop.cancelAndJoin()
                        ticker.cancelAndJoin()
                        metrics.clear()
                        recovery.reconcile(id)
                        delivery.close()
                        consumer.join()
                        store.get(id)?.let { row ->
                            val result = fromRecord(row)
                            val savedBytes = outputBytes
                            val sized = if (row.stage == JobStage.COMPLETED && savedBytes != null)
                                result.copy(bytes = savedBytes, total = Size.Exact(savedBytes)) else result
                            if (row.stage == JobStage.COMPLETED) {
                                accounting[id]?.reportLink = null
                                committed(row, sized)
                            } else {
                                update(sized)
                                // Deliver directly even when WorkManager cancelled its event consumer.
                                finished(sized)
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val lateCancellation = synchronized(userCancellation) {
                    // Cancel can arrive after the final report but before this writer releases ownership.
                    val result = if (id in userCancellation && store.cancel(id)) {
                        recovery.reconcile(id, cancellationRequested = true)
                        store.get(id)?.let(::fromRecord)
                    } else null
                    active.remove(id, signal); userCancellation.remove(id); pauseRequests.remove(id); postProcessing.remove(id)
                    if (store.get(id)?.stage?.terminal != false) offlineStarts.remove(id) // A reused id starts its count over.
                    // Expiry skipped this staging while the writer was live; the writer has exited now.
                    JobStore.liveWriters.remove(id)
                    if (store.get(id)?.stage?.terminal == true) {
                        cleanupJobStaging(File(context.filesDir.canonicalFile, "downloads"), id)
                        // Expiry removed the info file while the engine still ran; a rebuild may have stored a new one.
                        store.discardInfoJson(id)
                    }
                    activeDone.remove(id)?.complete(Unit)
                    result
                }
                if (lateCancellation != null) { update(lateCancellation); finished(lateCancellation) }
                // A stopped WorkSpec is re-enqueued even though this writer journalled FAILED.
                // Release native/publication ownership before cancelling that obsolete scheduler entry.
                if (schedulerStopped && store.get(id)?.stage == JobStage.FAILED) {
                    try { workManager().cancelUniqueWork(id).result.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted }
                }
            }
        }
        store.get(id)
    }

    @Suppress("DEPRECATION")
    private fun connectivityStopped(reason: Int): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) reason == WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY
        else context.getSystemService(android.net.ConnectivityManager::class.java).activeNetworkInfo?.isConnected != true

    private fun stagingRoot(id: String) = File(context.filesDir.canonicalFile, "downloads/$id")

    private fun finishStopped(id: String, error: DownloadError,
        systemStopped: Boolean = false, resumeAllowed: Boolean = true, startBytes: Long = Long.MAX_VALUE,
        cause: DownloadFailureCause = error.cause, keepOutput: Boolean = false,
        staleWriter: Boolean = false) = synchronized(userCancellation) {
        val row = store.get(id) ?: return@synchronized
        val bytes = stagingBytes(stagingRoot(id))
        store.resetAutomaticResumes(id, bytes)
        if (id !in userCancellation && (id in pauseRequests || row.stage == JobStage.PAUSED)) {
            store.pausedPartial(id, bytes > 0)
        } else if (id in userCancellation || error == DownloadError.Cancelled) {
            store.cancel(id)
            recovery.reconcile(id, cancellationRequested = true)
        } else if (systemStopped && (error in setOf(DownloadError.Network, DownloadError.Offline) ||
                keepOutput && error in setOf(DownloadError.Interrupted, DownloadError.StoppedByBattery)) &&
            row.stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING)) {
            // A scheduler stop waits for its constraints; it is not an engine failure episode. A stop during a
            // convert or merge keeps the finished source, so the retry only converts again (createOrAdopt keep).
            // A surviving stale writer still owns its directory, even an empty one: never clean it up under it.
            if (staleWriter) store.requeue(id, true)
            else if (bytes > 0) {
                if (store.requeue(id, true) && bytes > startBytes) progressed += id
            } else if (cleanupJobStaging(File(context.filesDir.canonicalFile, "downloads"), id)) store.requeue(id)
            else store.fail(id, DownloadError.SaveFailed)
        } else if (resumeAllowed && cause in setOf(DownloadFailureCause.TRANSPORT, DownloadFailureCause.STALL) &&
            error in setOf(DownloadError.Network, DownloadError.Offline) &&
            row.stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING)) {
            // Cold probes/first-byte timeouts may have no partial yet; still allow one bounded resume.
            if (store.requeueAutomatic(id, cause, hasPartial = bytes > 0)) {
                if (bytes > startBytes) progressed += id
            } else store.fail(id, error)
        } else {
            store.fail(id, error)
            val stopped = error in setOf(DownloadError.Interrupted, DownloadError.StoppedByBattery, DownloadError.Network,
                DownloadError.InsufficientSpace, DownloadError.FolderUnavailable)
            recovery.reconcile(id, cancellationRequested = stopped, cancellationError = error)
        }
    }
    /** Admission may finish scheduling after a worker has started or finished this same job. */
    @Synchronized private fun updateQueue(event: DownloadProgress) {
        if (event.waiting && (store.get(event.jobId)?.stage != JobStage.ENQUEUED || active.containsKey(event.jobId))) return
        update(event)
    }
    @Synchronized private fun update(incoming: DownloadProgress) {
        if (incoming.terminal && !incoming.awaitingSchedule) dev.ahmed.savlino.ui.EngineTiming.forgetDownload(incoming.jobId)
        val previous = mutableProgress.value.find { it.jobId == incoming.jobId }
        if (incoming.restartEpoch < (previous?.restartEpoch ?: 0)) return
        // A paused row has no byte columns: keep this run's last real progress (gone after a process restart).
        val event = if (incoming.paused && incoming.bytes == null && previous != null && previous.restartEpoch == incoming.restartEpoch)
            incoming.copy(bytes = previous.bytes, total = previous.total,
                fragmentIndex = previous.fragmentIndex, fragmentCount = previous.fragmentCount) else incoming
        if (!event.terminal) {
            val row = store.get(event.jobId)
            if (row?.stage?.terminal == true) {
                if (row.error == DownloadError.FormatExpired) stopEngine(event.jobId, cancelUnregistered = false)
                return
            }
            if (!event.paused && row?.stage == JobStage.PAUSED) return
        }
        // Progress ticks and scheduling failures are not job ends; startup replay has no live predecessor.
        val newlyTerminal = event.terminal && !event.awaitingSchedule &&
            previous != null && (!previous.terminal || previous.awaitingSchedule)
        if (newlyTerminal) {
            if (event.phase == DownloadPhase.COMPLETED) finishedEvents.tryEmit(event)
            if (event.phase == DownloadPhase.CANCELLED) cancellationEvents.tryEmit(event)
            countTerminal(event)
        }
        mutableProgress.update { old ->
            // An expiry callback can win after the journal check above; never resurrect that confirmation.
            if (!event.terminal && old.any { it.jobId == event.jobId && it.error == DownloadError.FormatExpired &&
                    it.restartEpoch >= event.restartEpoch }) old
            else if (event.phase in setOf(DownloadPhase.COMPLETED, DownloadPhase.CANCELLED)) old.filterNot { it.jobId == event.jobId }
            else if (old.any { it.jobId == event.jobId }) old.map { if(it.jobId == event.jobId) event else it }
            else old + event
        }
    }
    /** One count per job end; a failure also goes out as a report with its exact link (only while Help improve is on). */
    private fun countTerminal(event: DownloadProgress) {
        val row = store.get(event.jobId) ?: return
        val outcome = when (event.phase) {
            DownloadPhase.COMPLETED -> UsageKeys.Download.FINISHED
            DownloadPhase.FAILED -> UsageKeys.Download.FAILED
            DownloadPhase.CANCELLED -> UsageKeys.Download.CANCELLED
            else -> return
        }
        val attempt = accounting.remove(event.jobId) ?: return
        usage.count(UsageKeys.download(outcome, row.format))
        if (outcome == UsageKeys.Download.CANCELLED) { attempt.reportLink = null; return }
        usage.count(if (outcome == UsageKeys.Download.FINISHED) UsageKeys.FUNNEL_FINISHED else UsageKeys.FUNNEL_FAILED)
        val siteKey = attempt.siteKey ?: UsageKeys.site(row.sourceUrl)
        siteKey?.let(usage::count)
        if (outcome == UsageKeys.Download.FINISHED) attempt.transfer.bytesPerSecond()?.let { usage.count(UsageKeys.speed(it)) }
        else {
            val error = event.error ?: row.error ?: DownloadError.Failed
            val code = error.name + if (error == DownloadError.ExecutionRestricted) "_" + attempt.reason.name else ""
            usage.failed(Report.DOWNLOAD, code, attempt.reportLink ?: row.sourceUrl, row.engineVersion)
        }
        attempt.reportLink = null
    }
    /** Explicit retry of the original confirmation only; scheduling never substitutes the editor URL. */
    suspend fun retry(id: String): Boolean = retryOne(id).also { if (it) usage.count(UsageKeys.RETRY) }
    private suspend fun retryOne(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        val row = store.get(id) ?: return@withContext false
        if (row.stage == JobStage.ENQUEUED) return@withContext maintenance.withDownload {
            if (store.get(id)?.stage != JobStage.ENQUEUED ||
                progress.value.none { it.jobId == id && it.awaitingSchedule }) return@withDownload false
            // Explicit Downloads Retry always extracts from the URL, even after failed cleanup;
            // fresh signed URLs never continue older bytes.
            if (!store.discardInfoJson(id) || !cleanupJobStaging(File(context.filesDir.canonicalFile, "downloads"), id) ||
                !store.requeue(id)) {
                mutableError.value = DownloadError.ExecutionRestricted
                return@withDownload false
            }
            rescheduleUnderMaintenanceGate(id)
            true
        }
        if (row.stage != JobStage.FAILED || !row.retryable || row.sourceUrl == null) {
            // An active row keeps its live progress; only a terminal row resyncs from the journal.
            if (row.stage.terminal) update(fromRecord(row))
            return@withContext false
        }
        try {
            // FAILED is journalled before WorkManager finishes its worker. KEEP during that gap
            // discards a new attempt, while REPLACE could cancel the newly reset journal row.
            val previousFinished = withTimeoutOrNull(5000) {
                workManager().getWorkInfosForUniqueWorkFlow(id).first { infos -> infos.all { it.state.isFinished } }
                true
            } ?: false
            if (!previousFinished) {
                mutableError.value = DownloadError.ExecutionRestricted
                return@withContext false
            }
            // Wait above without the gate: an old worker must be able to finish and release it.
            maintenance.withDownload {
                // CAS rechecks eligibility/expiry after waiting; duplicate taps cannot reset twice.
                recovery.reconcile(id) // Re-picked trees can now discard a previously inaccessible partial document.
                if (!store.retry(id)) {
                    store.get(id)?.let { updateQueue(fromRecord(it)) }
                    return@withDownload false
                }
                mutableError.value = null
                rescheduleUnderMaintenanceGate(id)
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableError.value = DownloadError.ExecutionRestricted
            false
        }
    }

    suspend fun removeFailed(id: String) = withContext(Dispatchers.IO) {
        initialize()
        val row = store.get(id)
        val removed = row?.stage == JobStage.FAILED && store.remove(id)
        if (removed) offlineStarts.remove(id)
        if (removed || store.get(id) == null) mutableProgress.update { it.filterNot { event -> event.jobId == id } }
    }
    /** Copy link and the gone-file sweep, on this coordinator's journal. */
    fun shareLinks(onGone: (String) -> Unit = {}) = dev.ahmed.savlino.downloads.ShareLinks(context.contentResolver, store, onGone)
    /** Settings › Clear link history: every kept link and its key go; the files stay. */
    suspend fun clearShareLinks() = withContext(Dispatchers.IO) {
        engine.clearRecentLinks()
        store.clearShareLinks()
    }
    /** Optional checks run after commit and outside all writer/transfer permits. Late problems use the warning list. */
    private fun checkSavedAsync(row: JobRecord, stagedBytes: Long?): Deferred<ResultCheck?> {
        val uri = row.pendingUri ?: return CompletableDeferred(null)
        val ticket = savedProbes.submit(checkTimeoutMs) { probe.probe(uri) }
        return artworkScope.async {
            val verdict = ResultJudge.judge(row.format, stagedBytes, ticket.await())
            if (verdict is ResultCheck.Problem) {
                val canRetry = try { store.reopen(row.jobId) != null } catch (_: Exception) { false }
                val warning = CheckWarning(row.jobId, row.displayName ?: row.filename, uri, verdict, canRetry)
                mutableWarnings.update { old -> (listOf(warning) + old.filterNot { it.jobId == row.jobId }).take(100) }
            }
            verdict.takeUnless { it == ResultCheck.Unchecked }
        }
    }

    fun dismissWarning(jobId: String) { mutableWarnings.update { old -> old.filterNot { it.jobId == jobId } } }

    /**
     * Try again (R5): an explicit tap on a format the person confirmed before, so a NEW job from the kept link.
     * The old file and its row stay as they are. Returns the new job id, or null when the link is gone.
     */
    suspend fun tryAgain(jobId: String): String? = withContext(Dispatchers.IO) {
        val reopen = try { store.reopen(jobId) } catch (_: Exception) { null } ?: return@withContext null
        val link = LinkInput.parse(reopen.link) ?: return@withContext null
        val source = LinkRevision(link, 0)
        val command = (DownloadCommand.confirm(java.util.UUID.randomUUID().toString(),
            Inspection(source, reopen.engineVersion, listOf(reopen.format)), source, reopen.format.id,
            reopen.filename.substringBeforeLast('.')) as? ConfirmResult.Confirmed)?.command ?: return@withContext null
        if (!enqueue(command, reopen.preset)) return@withContext null
        dismissWarning(jobId)
        command.jobId
    }

    fun dismiss(id: String) { mutableProgress.update { it.filterNot { event -> event.jobId == id && event.terminal && !event.awaitingSchedule } } }
    private fun fromRecord(row: JobRecord) = DownloadProgress(row.jobId, row.displayName ?: row.filename,
        when (row.stage) {
            JobStage.ENQUEUED, JobStage.PAUSED -> DownloadPhase.PREPARING
            JobStage.COMPLETED -> DownloadPhase.COMPLETED
            JobStage.CANCELLED -> DownloadPhase.CANCELLED
            else -> DownloadPhase.FAILED
        }, error = row.error, publishedUri = row.pendingUri.takeIf { row.stage == JobStage.COMPLETED }, targetUri = row.pendingUri, mimeType = mime(row.format), total = row.format.size, qualityHeight = VideoQuality.of(row.format.width, row.format.height), waiting = row.stage == JobStage.ENQUEUED,
        retryable = row.stage == JobStage.FAILED && row.retryable && row.sourceUrl != null,
        paused = row.stage == JobStage.PAUSED, hasPartial = row.hasPartial, restartEpoch = row.restartEpoch)
    private fun scheduleFailed(row: JobRecord) = fromRecord(row).copy(phase = DownloadPhase.FAILED,
        error = DownloadError.ExecutionRestricted, awaitingSchedule = true, waiting = true)
    private fun mime(format: Format) = mimeTypeFor(format)
}

/** MediaStore MIME for the saved container; mkv is the VP9 + AAC merge (playback-compatible codecs, 2026-10-19). */
/** A file deleted outside Nezzel no longer counts as saved. */
private fun publishedFileExists(context: Context, uri: android.net.Uri): Boolean = try {
    context.contentResolver.query(uri, arrayOf(if (android.provider.DocumentsContract.isTreeUri(uri)) android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID else android.provider.MediaStore.MediaColumns._ID), android.os.Bundle.EMPTY, null)
        ?.use { it.moveToFirst() } == true
} catch (_: Exception) { false }

/** Role is authoritative for ambiguous containers. Unknown bytes are never advertised as MP4. */
internal fun mimeTypeFor(format: Format): String {
    val container = format.container.lowercase(java.util.Locale.ROOT)
    val audio = !format.hasVideo && format.hasAudio
    return when (container) {
        "webm" -> if (audio) "audio/webm" else "video/webm"
        "mkv" -> if (audio) "audio/x-matroska" else "video/x-matroska"
        "ogg", "opus", "oga" -> if (format.hasVideo) "video/ogg" else "audio/ogg"
        "m4a", "m4b" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "mp4" -> if (audio) "audio/mp4" else "video/mp4"
        "mov" -> "video/quicktime"
        "3gp", "3gpp" -> if (audio) "audio/3gpp" else "video/3gpp"
        "flv" -> "video/x-flv"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(container)
            ?.takeIf { it.startsWith(if (audio) "audio/" else "video/") }
            ?: "application/octet-stream"
    }
}

/** Android providers associate audio/webm with .weba; a .webm hint can acquire a second extension. */
internal fun publicationName(filename: String, format: Format): String =
    if (!format.hasVideo && format.hasAudio && format.container.equals("webm", ignoreCase = true))
        filename.substringBeforeLast('.') + ".weba" else filename

/** Result check watchdog: past this the saved file is simply "Saved". */
internal const val CHECK_TIMEOUT_MS = 4000L

/** Quick verdicts preserve completion copy without holding transfer capacity. */
internal const val CHECK_GRACE_MS = 300L

/** Gate passes without a usable network before the runner backs off (WorkManager retry) instead of re-gating. */
internal const val OFFLINE_GATE_PASSES = 3
