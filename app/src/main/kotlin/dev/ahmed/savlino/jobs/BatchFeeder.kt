package dev.ahmed.savlino.jobs

import android.os.PowerManager
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Scheduling/control seam; the journal remains real in feeder tests. */
internal interface BatchJobActions {
    suspend fun enqueue(command: DownloadCommand, preset: Preset): Boolean
    suspend fun pause(id: String): Boolean
    suspend fun resume(id: String): Boolean
    suspend fun cancel(id: String)
    suspend fun retry(id: String): Boolean
    suspend fun removeFailed(id: String)
}

/** Sequential inspection, durable identity, bounded lookahead. No transfer slot or publication logic lives here. */
class BatchFeeder internal constructor(private val batches: BatchStore, private val engine: EngineAdapter,
    private val actions: BatchJobActions, private val limit: StateFlow<Int>, private val thermal: StateFlow<Int>) {
    private val jobs get() = batches.jobs
    private val gate = Mutex()
    private val tickGate = Mutex()
    // Accessed under gate; inspection itself releases gate so controls remain responsive.
    private var resolvingBatch: String? = null
    private var resolvingOperation: String? = null
    private var resolvingJob: Job? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var runner: Job? = null

    /** App/process lifetime; the parent's cancellation also closes observers and any pending inspection. */
    @Synchronized internal fun start(scope: CoroutineScope, changes: Flow<*>, online: Flow<Boolean>): Job {
        runner?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            launch { changes.collect { wake() } }
            launch { combine(limit, thermal) { _, _ -> Unit }.collect { wake() } }
            launch { online.distinctUntilChanged().collect { if (it) networkAvailable() } }
            wake()
            while (isActive) {
                // Also scrub a quiet/paused batch when its confirmation expires.
                withTimeoutOrNull(60_000) { wakeups.receive() }
                try { tick() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain durable state; retry on next signal, never log row contents. */ }
            }
        }.also { runner = it }
    }
    fun wake() { wakeups.trySend(Unit) }

    internal suspend fun tick() = tickGate.withLock { gate.withLock {
        reconcile() // First adopt any enqueue committed just before process death.
        batches.scrubExpired()
        applyControls()
        for (batch in batches.all()) {
            while (batches.get(batch.id)?.state == BatchState.ACTIVE && activeCount() < lookahead()) {
                val item = batches.items(batch.id).firstOrNull { it.state == BatchItemState.WAITING } ?: break
                val existing = jobs.get(item.jobId)
                if (existing != null) {
                    if (existing.stage == JobStage.FAILED && item.retryable) {
                        if (actions.retry(item.jobId)) batches.queued(item, existing.preset != batch.preset)
                        else {
                            // Retry may have waited for the old worker, or scrubbed a stale confirmation.
                            val latest = jobs.get(item.jobId)
                            if (latest?.stage?.terminal == false) batches.queued(item, latest.preset != batch.preset)
                            else if (batches.refresh(item)) actions.removeFailed(item.jobId)
                            else batches.finish(item, BatchItemState.FAILED, batchError(item, latest ?: existing) ?: DownloadError.ExecutionRestricted)
                        }
                    } else batches.queued(item, existing.preset != batch.preset)
                    reconcile()
                    applyControls()
                    continue
                }
                val raw = batches.source(item)
                if (raw == null) {
                    // A temporarily locked Keystore must not turn all rows into permanent failures.
                    if (batches.items(batch.id).first { it.position == item.position }.state == BatchItemState.WAITING)
                        batches.state(batch.id, BatchState.PAUSED, DownloadError.ExecutionRestricted)
                    break
                }
                val link = LinkInput.parse(raw)
                // Playlist rows were sealed as YouTube videos; pasted links may be any host. A YouTube list or mix
                // resolves its first video only (inspection runs with --no-playlist), as a single paste does.
                if (link == null || !batchable(link)) {
                    batches.finish(item, BatchItemState.SKIPPED, DownloadError.InvalidInput)
                    continue
                }
                val source = LinkRevision(link, 0)
                // A control can persist while the source is being read, before resolution starts.
                if (batches.get(batch.id)?.state != BatchState.ACTIVE) break
                val operation = "resolve-${item.jobId}"
                resolvingBatch = batch.id
                resolvingOperation = operation
                val result = coroutineScope {
                    val resolve = async(start = CoroutineStart.LAZY) {
                        try {
                            if (item.jobId.endsWith(BatchStore.FRESH_SUFFIX)) engine.inspectFresh(source, operation)
                            else engine.inspect(source, operation)
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { InspectResult.Failure(DownloadError.Failed) }
                    }
                    resolvingJob = resolve
                    gate.unlock()
                    try { resolve.await() }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    }
                    catch (_: Exception) { InspectResult.Failure(DownloadError.Failed) }
                    finally {
                        // Reacquire even on parent cancellation: withLock must own the mutex on exit.
                        withContext(NonCancellable) { resolve.join(); gate.lock() }
                        resolvingBatch = null
                        resolvingOperation = null
                        resolvingJob = null
                    }
                } ?: break
                // Completion, heat and the original confirmation deadline can change during inspection.
                reconcile()
                batches.scrubExpired()
                applyControls()
                if (batches.get(batch.id)?.state != BatchState.ACTIVE || activeCount() >= lookahead()) break
                when (result) {
                    // A link batch says why a photo post is skipped: photos are saved from the single-link sheet only.
                    is InspectResult.Failure -> if (result.error == DownloadError.NoVideo && item.itemId.startsWith(BatchStore.LINK_ITEM))
                        batches.finish(item, BatchItemState.SKIPPED, DownloadError.PhotoPost)
                    else batches.finish(item,
                        if (result.error in setOf(DownloadError.Unavailable, DownloadError.NoVideo, DownloadError.NotAPost, DownloadError.Unsupported))
                            BatchItemState.SKIPPED else BatchItemState.FAILED,
                        result.error, JobStore.retryableError(result.error))
                    is InspectResult.Success -> {
                        val selected = choose(result.inspection, batch.preset)
                        if (selected == null) batches.finish(item, BatchItemState.SKIPPED, DownloadError.NoFormats)
                        else {
                            val (format, actualPreset) = selected
                            val confirmed = DownloadCommand.confirm(item.jobId, result.inspection, source, format.id, item.title)
                            if (confirmed !is ConfirmResult.Confirmed) batches.finish(item, BatchItemState.FAILED,
                                (confirmed as? ConfirmResult.Invalid)?.reason ?: DownloadError.Interrupted)
                            else {
                                // Link items have no list artwork: the running row fetches the inspected thumbnail late.
                                result.inspection.thumbnailUrl?.let { SheetArtwork.source(result.inspection.inspectionId, it) }
                                try {
                                    actions.enqueue(confirmed.command, actualPreset)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* An insert may have committed: inspect the row before deciding. */ }
                                if (jobs.get(item.jobId) != null) {
                                    batches.queued(item, actualPreset != batch.preset)
                                    reconcile() // Scheduling can already have failed or completed.
                                } else batches.finish(item, BatchItemState.FAILED, DownloadError.ExecutionRestricted, retryable = true)
                            }
                        }
                    }
                }
                applyControls()
            }
            batches.finishIfIdle(batch.id)
        }
    } }

    private fun batchable(link: ValidatedLink) = link.host != Host.YOUTUBE ||
        YouTubeLinkKind.classify(link) in setOf(YouTubeLinkKind.Video, YouTubeLinkKind.VideoInList, YouTubeLinkKind.Playlist, YouTubeLinkKind.Mix)
    private fun lookahead() = (if (thermal.value >= PowerManager.THERMAL_STATUS_SEVERE) 1 else limit.value.coerceIn(1, 3)) + 1
    private fun activeCount() = batches.all().sumOf { batch -> batches.items(batch.id).count { item ->
        item.state == BatchItemState.QUEUED && jobs.get(item.jobId)?.let { !it.stage.terminal && it.stage != JobStage.PAUSED } == true
    } }

    // An expired confirmation says nothing about whether the site's current formats changed.
    private suspend fun batchError(item: BatchItemRecord, job: JobRecord?): DownloadError? {
        if (item.itemId.startsWith(BatchStore.LINK_ITEM) || job?.error != DownloadError.FormatExpired) return job?.error
        if (System.currentTimeMillis() - job.createdAt > JobStore.CONFIRMATION_LIFETIME_MS) return DownloadError.Interrupted
        val version = try { engine.installedVersion() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return DownloadError.EngineUnavailable }
        return if (version != job.engineVersion) DownloadError.EngineUnavailable else job.error
    }

    /** Adopt live/committed work; interrupted playlist jobs get one durable fresh resolution. */
    private suspend fun reconcile() {
        for (batch in batches.all()) {
            var deferredFresh = false
            for (item in batches.items(batch.id)) {
                if (item.state !in setOf(BatchItemState.WAITING, BatchItemState.QUEUED, BatchItemState.FAILED)) continue
                val job = jobs.get(item.jobId)
                if (item.state == BatchItemState.FAILED && !item.hasSource && !item.itemId.startsWith(BatchStore.LINK_ITEM))
                    job?.sourceUrl?.let { batches.adoptSource(item, it) }
                if (job == null) {
                    if (item.state == BatchItemState.QUEUED && batches.canRefresh(item) &&
                        batch.state in setOf(BatchState.PAUSED, BatchState.WAITING_NETWORK)) {
                        deferredFresh = true
                        continue
                    }
                    if (item.state == BatchItemState.QUEUED && !batches.refresh(item))
                        batches.finish(item, BatchItemState.FAILED, DownloadError.Interrupted)
                    continue
                }
                val needsFresh = job.stage == JobStage.FAILED && !item.itemId.startsWith(BatchStore.LINK_ITEM) &&
                    (job.error in setOf(DownloadError.Interrupted, DownloadError.StoppedByBattery, DownloadError.FormatExpired) ||
                        System.currentTimeMillis() - job.createdAt > JobStore.CONFIRMATION_LIFETIME_MS)
                // Do not discard the source or consume the fresh attempt while batch controls defer it.
                if (needsFresh && batches.canRefresh(item) && batch.state in setOf(BatchState.PAUSED, BatchState.WAITING_NETWORK)) {
                    deferredFresh = true
                    continue
                }
                if (needsFresh && batches.refresh(item)) {
                    actions.removeFailed(item.jobId)
                    continue
                }
                if (item.state == BatchItemState.FAILED && job.stage != JobStage.COMPLETED) continue
                // A requested retry waits for a lookahead vacancy instead of requeueing N jobs at once.
                if (item.state == BatchItemState.WAITING && item.retryable && job.stage == JobStage.FAILED) continue
                if (item.state == BatchItemState.WAITING) batches.queued(item, job.preset != batch.preset)
                when (job.stage) {
                    JobStage.COMPLETED -> batches.finish(item, BatchItemState.DONE)
                    JobStage.CANCELLED -> batches.finish(item, BatchItemState.SKIPPED, DownloadError.Cancelled)
                    JobStage.FAILED -> batches.finish(item, BatchItemState.FAILED, batchError(item, job), job.retryable)
                    else -> Unit
                }
            }
            if (!deferredFresh) batches.finishIfIdle(batch.id)
        }
    }

    private suspend fun applyControls() {
        for (batch in batches.all()) if (batch.state == BatchState.STOPPED) {
            // Stop may have persisted just before process death, before coordinator cancellation ran.
            for (item in batches.items(batch.id)) if (item.state == BatchItemState.QUEUED &&
                jobs.get(item.jobId)?.stage?.terminal == false) actions.cancel(item.jobId)
        } else if (batch.state in setOf(BatchState.PAUSED, BatchState.WAITING_NETWORK)) {
            for (item in batches.items(batch.id)) if (item.state == BatchItemState.QUEUED &&
                jobs.get(item.jobId)?.stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING)) actions.pause(item.jobId)
        }
    }

    suspend fun pause(id: String) {
        if (batches.get(id)?.state !in setOf(BatchState.ACTIVE, BatchState.WAITING_NETWORK)) return
        batches.state(id, BatchState.PAUSED)
        gate.withLock {
            if (resolvingBatch == id) {
                try { resolvingOperation?.let(engine::cancel) }
                finally { resolvingJob?.cancel() }
            }
            batches.items(id).filter { it.state == BatchItemState.QUEUED }.forEach { actions.pause(it.jobId) }
        }
    }
    suspend fun resume(id: String) = gate.withLock { resumeUnderGate(id); wake() }
    private suspend fun resumeUnderGate(id: String, automatic: Boolean = false) {
        batches.scrubExpired()
        if (batches.get(id)?.state !in setOf(BatchState.PAUSED, BatchState.WAITING_NETWORK)) return
        batches.requestRetries(id, automatic)
        if (!batches.state(id, BatchState.ACTIVE)) return
        batches.items(id).filter { it.state == BatchItemState.QUEUED && jobs.get(it.jobId)?.stage == JobStage.PAUSED }
            .forEach { actions.resume(it.jobId) }
    }
    suspend fun stop(id: String) {
        val jobIds = batches.stop(id)
        gate.withLock {
            if (resolvingBatch == id) {
                try { resolvingOperation?.let(engine::cancel) }
                finally { resolvingJob?.cancel() }
            }
            for (jobId in jobIds) if (jobs.get(jobId)?.stage?.terminal == false) actions.cancel(jobId)
            reconcile()
            wake()
        }
    }
    /** A person's Retry: every failed video whose link is still stored, or only the one at [position]. */
    suspend fun retry(id: String, position: Int? = null) = gate.withLock { retryUnderGate(id, position = position); wake() }
    private suspend fun retryUnderGate(id: String, automatic: Boolean = false, position: Int? = null) {
        batches.scrubExpired()
        val batch = batches.get(id) ?: return
        if (batch.state in setOf(BatchState.STOPPED, BatchState.EXPIRED)) return
        // Connectivity retries only what the engine called retryable; a tap also re-resolves the rest, once per tap.
        val changed = if (automatic) batches.requestRetries(id, true)
            else batches.requestUserRetries(id, position).let { (changed, replaced) ->
                // The replaced failed rows would otherwise linger as standalone failures.
                replaced.forEach { actions.removeFailed(it) }
                changed
            }
        if (changed) {
            batches.state(id, BatchState.ACTIVE)
            batches.items(id).filter { it.state == BatchItemState.QUEUED && jobs.get(it.jobId)?.stage == JobStage.PAUSED }
                .forEach { actions.resume(it.jobId) }
        }
    }
    internal suspend fun networkAvailable() = gate.withLock {
        for (batch in batches.all()) if (batch.state == BatchState.WAITING_NETWORK) {
            retryUnderGate(batch.id, automatic = true)
            resumeUnderGate(batch.id, automatic = true)
        }
        wake()
    }

    private fun choose(inspection: Inspection, requested: Preset): Pair<Format, Preset>? {
        val chain = when (requested) {
            Preset.BALANCED -> listOf(Preset.BALANCED, Preset.SMALL)
            Preset.AUDIO_M4A -> listOf(Preset.AUDIO_M4A, Preset.AUDIO_MP3)
            else -> listOf(requested)
        }
        for (preset in chain) {
            val id = inspection.presets.firstOrNull { it.preset == preset }?.formatId ?: continue
            val row = inspection.formats.firstOrNull { it.id == id } ?: continue
            if (preset == Preset.BALANCED && row.height?.let { it <= 720 } != true) continue
            return row to preset
        }
        return null
    }
}
