package dev.ahmed.savlino.jobs

import android.content.Context
import android.os.CancellationSignal
import android.os.Environment
import androidx.work.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.storage.*
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
) {
    val maintenance = EngineMaintenance(context, engine, {
        active.isNotEmpty() || store.all().any { !it.stage.terminal }
    })
    private val recovery = JobRecovery(store, publisher ?: DestinationPublisher(context, space), File(context.filesDir, "downloads"))
    private val startup = Mutex()
    // Bound native processing and space preflight to one transfer; queue admission never waits here.
    private val transfer = Mutex()
    @Volatile private var initialized = false
    private val userCancellation = ConcurrentHashMap.newKeySet<String>()
    private val pauseRequests = ConcurrentHashMap.newKeySet<String>()
    private val activeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val active = ConcurrentHashMap<String, CancellationSignal>()
    private val mutableProgress = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val progress: StateFlow<List<DownloadProgress>> = mutableProgress.asStateFlow()
    private val finishedEvents = kotlinx.coroutines.flow.MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 100)
    val completed: kotlinx.coroutines.flow.SharedFlow<DownloadProgress> = finishedEvents
    private val cancellationEvents = kotlinx.coroutines.flow.MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 100)
    val cancelled: kotlinx.coroutines.flow.SharedFlow<DownloadProgress> = cancellationEvents
    private val mutableError = MutableStateFlow<DownloadError?>(null)
    val error: StateFlow<DownloadError?> = mutableError.asStateFlow()

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
            recovery.recover() // Never recover the whole journal after this barrier opens.
            for (row in store.all()) {
                if (row.stage == JobStage.ENQUEUED) {
                    updateQueue(fromRecord(row))
                    try { schedule(row.jobId) }
                    catch (_: Exception) { updateQueue(DownloadProgress(row.jobId, row.filename, DownloadPhase.FAILED,
                        error = DownloadError.ExecutionRestricted, awaitingSchedule = true, waiting = true)) }
                }
                else if (row.stage.terminal || row.stage == JobStage.PAUSED) update(fromRecord(row))
            }
            initialized = true
            mutableError.value = null
        }
    }

    suspend fun enqueue(command: DownloadCommand, preset: Preset? = null): Boolean = withContext(Dispatchers.IO) {
        maintenance.withDownload {
            initializeUnderMaintenanceGate()
            val inserted = store.enqueue(command, preset)
            // A scheduling failure leaves a durable ENQUEUED row for startup to retry.
            if (inserted) {
                store.prepareInfoJson(command.jobId) { engine.prepareDownload(command) }
                updateQueue(fromRecord(store.get(command.jobId)!!))
                try { schedule(command.jobId) }
                catch (_: Exception) { updateQueue(DownloadProgress(command.jobId, command.filename, DownloadPhase.FAILED,
                    error = DownloadError.ExecutionRestricted, awaitingSchedule = true, waiting = true)) }
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

    private fun schedule(id: String) {
        if (store.get(id)?.stage != JobStage.ENQUEUED) return
        workManager().enqueueUniqueWork(id, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setInputData(workDataOf(DownloadWorker.JOB_ID to id)).build())
            .result.get()
        // A pause can win while an already-admitted scheduler is waiting on WorkManager.
        if (store.get(id)?.stage == JobStage.PAUSED) workManager().cancelUniqueWork(id).result.get()
    }

    /** Immediate signal only: never delete staging while an engine or copy may still be writing. */
    fun requestCancellation(id: String) {
        if (!JobStore.validJobId(id)) return
        synchronized(userCancellation) { userCancellation += id }
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
        } catch (_: Exception) { updateQueue(DownloadProgress(id, row.filename, DownloadPhase.FAILED,
            error = DownloadError.ExecutionRestricted, awaitingSchedule = true, waiting = true)) }
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
            }
        }
        try { workManager().cancelUniqueWork(id).result.get() }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted }
    }

    suspend fun pause(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        val paused = synchronized(userCancellation) {
            if (id in userCancellation || !store.pause(id)) false
            else { pauseRequests += id; stopEngine(id, cancelUnregistered = false); true }
        }
        if (!paused) return@withContext false
        if (!active.containsKey(id)) store.pausedPartial(id, stagingBytes(stagingRoot(id)) > 0)
        store.get(id)?.let { update(fromRecord(it)) }
        try { workManager().cancelUniqueWork(id).result.get() }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted }
        true
    }

    suspend fun resume(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        if (store.get(id)?.stage != JobStage.PAUSED) return@withContext false
        try {
            val finished = withTimeoutOrNull(5000) {
                activeDone[id]?.await()
                workManager().getWorkInfosForUniqueWorkFlow(id).first { infos -> infos.all { it.state.isFinished } }
                true
            } ?: false
            if (!finished) { mutableError.value = DownloadError.ExecutionRestricted; return@withContext false }
            maintenance.withDownload {
                synchronized(userCancellation) {
                    if (!store.resume(id)) return@withDownload false
                    pauseRequests.remove(id)
                }
                mutableError.value = null
                rescheduleUnderMaintenanceGate(id)
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = DownloadError.ExecutionRestricted; false }
    }

    suspend fun execute(id: String, foreground: suspend (DownloadProgress) -> Unit,
        report: suspend (DownloadProgress) -> Unit): JobRecord? = execute(id,
            { WorkInfo.STOP_REASON_NOT_STOPPED }, foreground, report, report)

    suspend fun execute(id: String, stopReason: () -> Int,
        foreground: suspend (DownloadProgress) -> Unit, report: suspend (DownloadProgress) -> Unit,
        finished: suspend (DownloadProgress) -> Unit): JobRecord? = transfer.withLock {
        maintenance.withDownload { executeWithMaintenanceGate(id, stopReason, foreground, report, finished) }
    }

    private suspend fun executeWithMaintenanceGate(id: String, stopReason: () -> Int,
        foreground: suspend (DownloadProgress) -> Unit, report: suspend (DownloadProgress) -> Unit,
        finished: suspend (DownloadProgress) -> Unit): JobRecord? = withContext(Dispatchers.IO) {
        initializeUnderMaintenanceGate()
        synchronized(userCancellation) { if (id in userCancellation) store.cancelEnqueued(id) }
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
        val signal = CancellationSignal()
        synchronized(userCancellation) {
            if (active.putIfAbsent(id, signal) != null) return@withContext row
            activeDone[id] = CompletableDeferred()
            if (id in userCancellation || id in pauseRequests) signal.cancel()
        }
        var transferredBytes = 0L
        fun stoppedError(): DownloadError = when {
            id in userCancellation -> DownloadError.Cancelled
            connectivityStopped(stopReason()) -> DownloadError.Network
            else -> DownloadError.Interrupted
        }
        fun finishStopped() = finishStopped(id, stoppedError(), transferredBytes, requeueEmpty = true)
        try {
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
                    try { awaitCancellation() } finally { stopEngine(id, cancelUnregistered = false) }
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
                    }
                    if (event.phase != DownloadPhase.DOWNLOADING) metrics.clear()
                    delivery.offer(event)
                }
                var outputBytes: Long? = null
                try {
                    var command = row.command() ?: error("Invalid job command")
                    val engineVersion = engine.installedVersion() ?: row.engineVersion
                    val recipe = "${dev.ahmed.savlino.BuildConfig.VERSION_CODE}:$engineVersion:${YtdlpCommand.ARGV_VERSION}"
                    val adopting = row.restartEpoch > 0 || row.hasPartial
                    if (adopting && row.resumeRecipe != recipe) {
                        check(cleanupJobStaging(File(context.filesDir.canonicalFile, "downloads"), id))
                        check(store.discardInfoJson(id))
                    }
                    if (engineVersion != command.engineVersion) {
                        command = (DownloadCommand.confirm(id, Inspection(command.source, engineVersion, listOf(command.format)),
                            command.source, command.format.id, row.filename.substringBeforeLast('.')) as ConfirmResult.Confirmed).command
                    }
                    if (!store.startTransfer(id, recipe)) return@coroutineScope
                    var epoch = store.get(id)!!.restartEpoch
                    var engineEpoch = 0L
                    var hasPartial = row.hasPartial && row.resumeRecipe == recipe
                    val initial = DownloadProgress(id, row.filename, DownloadPhase.PREPARING,
                        restartEpoch = epoch, hasPartial = hasPartial,
                        mimeType = mime(command.format), qualityHeight = VideoQuality.of(command.format.width, command.format.height))
                    emit(initial)
                    foreground(initial) // Foreground promotion must succeed before any transfer.
                    signal.throwIfCanceled()
                    currentCoroutineContext().ensureActive()
                    val job = if (adopting) directories.createOrAdopt(id) else directories.create(id)
                    val partialBytes = stagingBytes(job.directory)
                    hasPartial = partialBytes > 0
                    store.transferPartial(id, hasPartial)
                    TransferSpacePolicy.check(space, command.format, partialBytes)
                    synchronized(userCancellation) {
                        signal.throwIfCanceled()
                        check(store.get(id)?.stage == JobStage.TRANSFERRING)
                        downloading += id
                    }
                    val result = try { engine.download(command, job.directory) { event ->
                        synchronized(delivery) {
                            if (event is EngineEvent.Progress && event.restartEpoch != engineEpoch) return@synchronized
                            val current = store.get(id) ?: return@synchronized
                            if (current.restartEpoch != epoch) return@synchronized
                            if (current.stage != JobStage.TRANSFERRING) {
                                if (current.error == DownloadError.FormatExpired) stopEngine(id, cancelUnregistered = false)
                                return@synchronized
                            }
                            if (event is EngineEvent.Restarting) {
                                if (event.restartEpoch <= engineEpoch) return@synchronized
                                signal.throwIfCanceled()
                                epoch = store.restartTransfer(id) ?: return@synchronized
                                engineEpoch = event.restartEpoch
                                hasPartial = false
                                transferredBytes = 0
                                transferOpen = true
                                metrics.clear()
                                emit(initial.copy(restartEpoch = epoch, hasPartial = false))
                                return@synchronized
                            }
                            // Byte callbacks arriving after the post-processing barrier are no longer transfer work.
                            if (event is EngineEvent.Progress && !transferOpen) {
                                signal.throwIfCanceled()
                                return@synchronized
                            }
                            if (event is EngineEvent.Progress) {
                                transferredBytes = maxOf(transferredBytes, event.bytesDownloaded)
                                dev.ahmed.savlino.ui.EngineTiming.firstDownloadBytes(id, event.bytesDownloaded)
                            }
                            signal.throwIfCanceled()
                            emit(when (event) {
                                is EngineEvent.Progress -> {
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
                    } } finally { downloading.remove(id) }
                    when (result) {
                        is TransferResult.Failure -> if (signal.isCanceled || result.error == DownloadError.Cancelled) finishStopped()
                            else finishStopped(id, result.error, transferredBytes, resumeAllowed = result.resumeAllowed)
                        is TransferResult.Success -> {
                            currentCoroutineContext().ensureActive()
                            synchronized(userCancellation) {
                                signal.throwIfCanceled()
                                outputBytes = job.requireOutput(result.output.file).length()
                                check(store.markStaged(id))
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
                        finishStopped(id, error, transferredBytes)
                    }
                } finally {
                    withContext(NonCancellable) {
                        stop.cancelAndJoin()
                        ticker.cancelAndJoin()
                        metrics.clear()
                        recovery.reconcile(id)
                        delivery.close()
                        consumer.join()
                        store.get(id)?.let { row ->
                            val result = fromRecord(row)
                            val savedBytes = outputBytes
                            val final = if (row.stage == JobStage.COMPLETED && savedBytes != null)
                                result.copy(bytes = savedBytes, total = Size.Exact(savedBytes)) else result
                            update(final)
                            // The worker's event consumer is already cancelled on a system stop.
                            // Deliver the result directly while this context is NonCancellable.
                            finished(final)
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
                    active.remove(id, signal); userCancellation.remove(id); pauseRequests.remove(id)
                    activeDone.remove(id)?.complete(Unit)
                    result
                }
                if (lateCancellation != null) { update(lateCancellation); finished(lateCancellation) }
            }
        }
        store.get(id)
    }

    @Suppress("DEPRECATION")
    private fun connectivityStopped(reason: Int): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) reason == WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY
        else context.getSystemService(android.net.ConnectivityManager::class.java).activeNetworkInfo?.isConnected != true

    private fun stagingRoot(id: String) = File(context.filesDir.canonicalFile, "downloads/$id")

    private fun finishStopped(id: String, error: DownloadError, reportedBytes: Long,
        requeueEmpty: Boolean = false, resumeAllowed: Boolean = true) = synchronized(userCancellation) {
        val row = store.get(id) ?: return@synchronized
        val bytes = stagingBytes(stagingRoot(id))
        if (id !in userCancellation && (id in pauseRequests || row.stage == JobStage.PAUSED)) {
            store.pausedPartial(id, bytes > 0)
        } else if (id in userCancellation || error == DownloadError.Cancelled) {
            store.cancel(id)
            recovery.reconcile(id, cancellationRequested = true)
        } else if (resumeAllowed && error in setOf(DownloadError.Network, DownloadError.Offline) &&
            row.stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING)) {
            val jobRoot = File(context.filesDir.canonicalFile, "downloads")
            if (bytes > 0 && row.stage == JobStage.TRANSFERRING) store.requeue(id, true)
            else if (requeueEmpty && reportedBytes <= 0 && bytes == 0L) {
                if (cleanupJobStaging(jobRoot, id)) store.requeue(id)
                else store.fail(id, DownloadError.SaveFailed)
            } else store.fail(id, error)
        } else {
            store.fail(id, error)
            val stopped = error in setOf(DownloadError.Interrupted, DownloadError.Network,
                DownloadError.InsufficientSpace, DownloadError.FolderUnavailable)
            recovery.reconcile(id, cancellationRequested = stopped, cancellationError = error)
        }
    }
    /** Admission may finish scheduling after a worker has started or finished this same job. */
    @Synchronized private fun updateQueue(event: DownloadProgress) {
        if (event.waiting && (store.get(event.jobId)?.stage != JobStage.ENQUEUED || active.containsKey(event.jobId))) return
        update(event)
    }
    @Synchronized private fun update(event: DownloadProgress) {
        if (event.terminal && !event.awaitingSchedule) dev.ahmed.savlino.ui.EngineTiming.forgetDownload(event.jobId)
        val previous = mutableProgress.value.find { it.jobId == event.jobId }
        if (event.restartEpoch < (previous?.restartEpoch ?: 0)) return
        if (!event.terminal) {
            val row = store.get(event.jobId)
            if (row?.stage?.terminal == true) {
                if (row.error == DownloadError.FormatExpired) stopEngine(event.jobId, cancelUnregistered = false)
                return
            }
            if (!event.paused && row?.stage == JobStage.PAUSED) return
        }
        val newlyTerminal = previous != null && (!previous.terminal || previous.awaitingSchedule)
        if (newlyTerminal) {
            if (event.phase == DownloadPhase.COMPLETED) finishedEvents.tryEmit(event)
            if (event.phase == DownloadPhase.CANCELLED) cancellationEvents.tryEmit(event)
        }
        mutableProgress.update { old ->
            if (event.phase in setOf(DownloadPhase.COMPLETED, DownloadPhase.CANCELLED)) old.filterNot { it.jobId == event.jobId }
            else if (old.any { it.jobId == event.jobId }) old.map { if(it.jobId == event.jobId) event else it }
            else old + event
        }
    }
    /** Explicit retry of the original confirmation only; scheduling never substitutes the editor URL. */
    suspend fun retry(id: String): Boolean = withContext(Dispatchers.IO) {
        initialize()
        val row = store.get(id) ?: return@withContext false
        if (row.stage == JobStage.ENQUEUED) return@withContext maintenance.withDownload {
            if (store.get(id)?.stage != JobStage.ENQUEUED ||
                progress.value.none { it.jobId == id && it.awaitingSchedule }) return@withDownload false
            // Explicit Downloads Retry always extracts from the URL, even after failed cleanup.
            if (!store.discardInfoJson(id)) {
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
        if (removed || store.get(id) == null) mutableProgress.update { it.filterNot { event -> event.jobId == id } }
    }
    fun dismiss(id: String) { mutableProgress.update { it.filterNot { event -> event.jobId == id && event.terminal && !event.awaitingSchedule } } }
    private fun fromRecord(row: JobRecord) = DownloadProgress(row.jobId, row.displayName ?: row.filename,
        when (row.stage) {
            JobStage.ENQUEUED, JobStage.PAUSED -> DownloadPhase.PREPARING
            JobStage.COMPLETED -> DownloadPhase.COMPLETED
            JobStage.CANCELLED -> DownloadPhase.CANCELLED
            else -> DownloadPhase.FAILED
        }, error = row.error, publishedUri = row.pendingUri.takeIf { row.stage == JobStage.COMPLETED }, mimeType = mime(row.format), total = row.format.size, qualityHeight = VideoQuality.of(row.format.width, row.format.height), waiting = row.stage == JobStage.ENQUEUED,
        retryable = row.stage == JobStage.FAILED && row.retryable && row.sourceUrl != null,
        paused = row.stage == JobStage.PAUSED, hasPartial = row.hasPartial, restartEpoch = row.restartEpoch)
    private fun mime(format: Format) = mimeTypeFor(format.container)
}

/** MediaStore MIME for the saved container; mkv is the VP9 + AAC merge (playback-compatible codecs, 2026-10-19). */
/** A file deleted outside Nezzel no longer counts as saved. */
private fun publishedFileExists(context: Context, uri: android.net.Uri): Boolean = try {
    context.contentResolver.query(uri, arrayOf(if (android.provider.DocumentsContract.isTreeUri(uri)) android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID else android.provider.MediaStore.MediaColumns._ID), android.os.Bundle.EMPTY, null)
        ?.use { it.moveToFirst() } == true
} catch (_: Exception) { false }

internal fun mimeTypeFor(container: String) = when (container) {
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    else -> "video/mp4"
}
