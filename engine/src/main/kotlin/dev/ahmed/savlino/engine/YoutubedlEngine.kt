package dev.ahmed.savlino.engine

import android.content.Context
import dev.ahmed.savlino.domain.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock

sealed interface EngineUpdateResult {
    data class Success(val oldVersion: String, val newVersion: String) : EngineUpdateResult
    /** [usingBuiltIn]: the updated yt-dlp failed its self-test and the bundled one was restored. */
    data class Failure(val error: DownloadError, val usingBuiltIn: Boolean = false) : EngineUpdateResult
}

/** Engine timeouts and recovery (lead 2026-10-19, docs/OPEN_DECISIONS.md). */
internal class EngineLimits(val inspectMs: Long = 60_000, val updateMs: Long = 120_000, val stallMs: Long = 45_000,
    val selfTestMs: Long = 30_000, val maintenanceWaitMs: Long = 90_000, val firstByteMs: Long = 180_000)

/** Construction/availability are inert. Startup warm-up extracts and probes local binaries only. */
class YoutubedlEngine internal constructor(private val runtime: YtdlpRuntime,
    private val limits: EngineLimits = EngineLimits(), private val infoCache: YtdlpInfoCache? = null) : EngineAdapter {
    constructor(context: Context) : this(AndroidYtdlpRuntime(context), infoCache = YtdlpInfoCache(context.noBackupFilesDir))
    private val unavailable = UnavailableEngine()
    private class Operation {
        // Never pass externally supplied IDs to the library's child-process shell command.
        val processId = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean()
        val cancellation = CompletableDeferred<Unit>()
        val finished = AtomicBoolean()
        /** Set by the canceller when [deadline] passes; reported as retryable Network, never as a user cancel. */
        val timedOut = AtomicBoolean()
        @Volatile var deadline = NO_DEADLINE
        @Volatile var byteWatchdog = false
        @Volatile var timeoutCause = DownloadFailureCause.TRANSPORT
        @Synchronized fun expireIn(ms: Long) { deadline = System.nanoTime() + ms * 1_000_000 }
    }
    private class PendingCancellation {
        val deadline = System.nanoTime() + CANCELLATION_WINDOW_MS * 1_000_000
    }
    private val lifecycleLock = Any()
    private val operations = mutableMapOf<String, Operation>()
    private val pendingCancellation = mutableMapOf<String, PendingCancellation>()
    private val recentlyFinished = linkedMapOf<String, Unit>()
    private val waitingState = MutableStateFlow<Set<String>>(emptySet())
    override val waitingOperations = waitingState.asStateFlow()
    override val isAvailable get() = runtime.initialization.isAvailable
    override val preparing: StateFlow<Boolean> get() = runtime.initialization.preparing
    override fun retryInitialization() = runtime.initialization.requestRetry()

    override suspend fun warmUp(): Boolean = operation("warm-${UUID.randomUUID()}", { _, _ -> false }) { current ->
        if (!initialize()) return@operation false
        version(current)
        true
    }

    override suspend fun installedVersion(): String? {
        runtime.initialization.installedVersion?.let { return it }
        // While an update is unresolved the binary is not readable; report Unknown instead of waiting on it.
        if (pendingUpdate?.isActive == true) return null
        return operation("version-${UUID.randomUUID()}", { _, _ -> null }) { current ->
            if (initialize()) version(current) else null
        }
    }

    override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult =
        operation(operationId, { error, _ -> InspectResult.Failure(error) }, lockTimeoutMs = limits.maintenanceWaitMs) { current ->
            if (!initialize()) return@operation unavailable.inspect(source, operationId)
            // After initialization: a one-time extraction must not consume the network budget.
            current.expireIn(limits.inspectMs)
            val version = version(current)
            inspectOnce(current, source, version)
        }

    private suspend fun inspectOnce(current: Operation, source: LinkRevision, version: String): InspectResult {
        val empty = YtdlpErrorMapper.EmptyInspection()
        val json = try {
            execute(current, YtdlpCommand.inspect(source), empty::accept)
        } catch (error: Exception) {
            if (error is CancellationException || error is YtdlpExecutionFailure) throw error
            val message = error.message.orEmpty()
            empty.accept(message)
            if (empty.isEmptyFailure(message)) {
                val mapped = empty.error(source.link.rawUrl, DownloadError.Failed)
                if (mapped != DownloadError.Failed) throw YtdlpExecutionFailure(mapped)
            }
            throw error
        }
        if (json.isBlank()) return InspectResult.Failure(empty.error(source.link.rawUrl, DownloadError.Failed))
        val inspection = try {
            YtdlpMetadataMapper.map(json, source, version)
        } catch (error: UnsupportedMetadataException) {
            if (!error.emptyResult) throw error
            return InspectResult.Failure(empty.error(source.link.rawUrl, DownloadError.Unsupported))
        }
        return if (inspection.formats.isEmpty()) InspectResult.Failure(empty.error(source.link.rawUrl, DownloadError.NoFormats))
        else {
            infoCache?.remember(inspection, json)
            InspectResult.Success(inspection)
        }
    }

    override fun prepareDownload(command: DownloadCommand) { infoCache?.prepare(command) }

    override suspend fun download(command: DownloadCommand, directory: File,
        progress: (EngineEvent) -> Unit): TransferResult {
        var resumeAllowed = true
        return operation(command.jobId, { error, cause -> TransferResult.Failure(error, resumeAllowed, cause) }, lockTimeoutMs = limits.maintenanceWaitMs) { current ->
            if (!initialize()) return@operation unavailable.download(command, directory, progress)
            if (version(current) != command.engineVersion) return@operation TransferResult.Failure(DownloadError.FormatExpired)
            // Validate before launching yt-dlp. Caller owns staging cleanup; adapter never touches public storage.
            prepareDownload(command)
            val infoJson = infoCache?.freshFile(command.jobId)
            var bytes = -1L
            var restartEpoch = 0L
            // Without the inspection's info JSON yt-dlp extracts new signed URLs: never continue older bytes.
            if (infoJson == null && directory.listFiles().orEmpty().isNotEmpty()) {
                if (!clearStaging(directory)) return@operation TransferResult.Failure(DownloadError.ExecutionRestricted)
                restartEpoch++
                progress(EngineEvent.Restarting(restartEpoch))
            }
            var retriedWithoutTags = false
            var preProcessingBytes: Long? = null
            suspend fun transfer(info: File?) {
                val attemptEpoch = restartEpoch
                // Build both while staging still contains only valid partials. A postprocessor failure
                // leaves completed audio here, which must not be cleared or revalidated as a partial.
                val untaggedArgs = YtdlpCommand.download(command, directory, info, embedAudioTags = false)
                var args = YtdlpCommand.download(command, directory, info, embedAudioTags = !retriedWithoutTags)
                while (true) {
                    val mapper = YtdlpProgressMapper(if (command.format.requiresMerge) 2 else 1, expected = command.format.size)
                    val diagnostics = YtdlpDiagnostics()
                    var postProcessing = false
                    synchronized(current) {
                        current.byteWatchdog = false
                        current.expireIn(limits.firstByteMs)
                    }
                    try {
                        execute(current, args) { line ->
                            diagnostics.accept(line)
                            val event = mapper.map(line)
                            // Linearize post-processing/byte progress with expiry. Late byte callbacks
                            // after a post-processor must never arm the watchdog again.
                            var startedPostProcessing = false
                            synchronized(current) {
                                // Parsing/converting thumbnails run before downloading: keep the first-byte
                                // budget armed there, even if the downloader never reports progress.
                                val preparingTags = bytes <= 0 &&
                                    (line.startsWith("[MetadataParser]") || line.startsWith("[ThumbnailsConvertor]"))
                                if (!preparingTags && (POSTPROCESSORS.any(line::startsWith) || event == EngineEvent.Merging)) {
                                    if (!postProcessing) {
                                        startedPostProcessing = true
                                        // Stdout delivery may lag behind native rewriting. Prefer the measured
                                        // transfer bytes; the input file is only a fallback when no bytes were reported.
                                        // Keep this across the optional untagged retry on the same downloaded audio.
                                        if (preProcessingBytes == null) {
                                            val input = File(directory, "output.${command.format.container}")
                                            preProcessingBytes = bytes.takeIf { it > 0 }
                                                ?: input.takeIf { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile }?.length()
                                        }
                                    }
                                    postProcessing = true; current.deadline = NO_DEADLINE; current.byteWatchdog = false
                                }
                                if (!postProcessing && event is EngineEvent.Progress && event.bytesDownloaded > bytes) {
                                    bytes = event.bytesDownloaded
                                    if (bytes > 0) {
                                        current.byteWatchdog = true
                                        current.expireIn(limits.stallMs)
                                    }
                                }
                            }
                            val delivered = if (startedPostProcessing) EngineEvent.Merging else event
                            if (!current.cancelled.get() && delivered != null)
                                progress(if (delivered is EngineEvent.Progress) delivered.copy(restartEpoch = attemptEpoch) else delivered)
                        }
                        break
                    } catch (error: Exception) {
                        val failure = diagnostics.failure(error)
                        resumeAllowed = failure.resumeAllowed
                        if (error is CancellationException) throw error
                        // Watchdog cancellation must carry the HTTP retry-warning classification outward.
                        if (current.cancelled.get()) throw if (current.timedOut.get()) failure else error
                        if (failure.error != DownloadError.MergeFailed || "--embed-thumbnail" !in args || retriedWithoutTags)
                            throw failure
                        currentCoroutineContext().ensureActive()
                        checkCancelled(current)
                        retriedWithoutTags = true
                        // Keep downloaded audio. Remove only known artwork and the append-only path manifest.
                        directory.listFiles().orEmpty().filter {
                            it.name == "result-path.txt" ||
                                it.name.matches(Regex("output\\.(?:jpg|jpeg|png|webp|avif|gif|temp\\.(?:m4a|mp3))"))
                        }.forEach {
                            if (java.nio.file.Files.isSymbolicLink(it.toPath()) || !it.isFile || !it.delete())
                                throw YtdlpExecutionFailure(DownloadError.ExecutionRestricted)
                        }
                        args = untaggedArgs
                    }
                }
            }
            try { transfer(infoJson) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                // Keep the typed timeout diagnostic instead of replacing it with OperationCancelled.
                if (current.timedOut.get()) throw error
                checkCancelled(current)
                val hasBytes = bytes > 0 || directory.listFiles().orEmpty().any {
                    !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && PartialFileNames.matches(it.name) && it.length() > 0
                }
                val retry = error is YtdlpExecutionFailure && error.expiredUrl && (infoJson != null || hasBytes)
                if (!retry || !clearStaging(directory)) throw error
                infoCache?.remove(command.jobId)
                // A new extraction must never inherit bytes from expired signed URLs.
                bytes = -1L
                preProcessingBytes = null
                restartEpoch++
                progress(EngineEvent.Restarting(restartEpoch))
                transfer(null)
            }
            current.deadline = NO_DEADLINE
            current.byteWatchdog = false
            checkCancelled(current)
            val output = try {
                val root = directory.canonicalFile
                val manifest = File(root, "result-path.txt")
                require(!java.nio.file.Files.isSymbolicLink(manifest.toPath()))
                val paths = manifest.readLines().filter { it.isNotBlank() }
                require(paths.size == 1)
                val files = requireNotNull(root.listFiles()).filter { it.name != "result-path.txt" }
                require(files.size == 1)
                val file = files.single()
                require(file.name.matches(Regex("output\\.[a-zA-Z0-9]{1,16}")))
                require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                require(File(paths.single()).canonicalFile == file.canonicalFile)
                require(file.isFile)
                val row = command.format
                val expected = (row.size as? Size.Exact)?.bytes
                val extractsAudio = !row.hasVideo && row.container == "m4a" && row.sourceFormatId != row.id
                // Allow a 2% size shortfall for an unchanged single stream; remuxed/extracted/converted
                // outputs and estimates cannot be compared to input sizes. Native tags may change file size;
                // compare the bytes captured before post-processing, or the untouched final file.
                // expected - expected / 50 is ceil(98% of expected), without overflowing Long.
                if (!row.requiresMerge && row.conversion == null && !extractsAudio && expected != null &&
                    (preProcessingBytes ?: file.length()) < expected - expected / 50) throw YtdlpExecutionFailure(DownloadError.MergeFailed)
                val output = StagedOutput.validate(root, listOf(file))
                require(output.file.parentFile == root)
                output
            } catch (_: IllegalArgumentException) {
                throw YtdlpExecutionFailure(DownloadError.ExecutionRestricted)
            }
            // Progressive MP4s may arrive with a numeric title atom; a provider scan can overwrite TITLE
            // after IS_PENDING clears. Repair the staged bytes, including merged MP4s, before publication.
            if (command.format.hasVideo && !command.format.animatedGif && output.file.extension.equals("mp4", ignoreCase = true)) {
                progress(EngineEvent.Merging)
                checkCancelled(current)
                try { runtime.rewriteTitle(output.file, command.filename.substringBeforeLast('.'), current.processId) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Optional polish must never fail an otherwise successful transfer. */ }
                checkCancelled(current)
            }
            TransferResult.Success(output)
        }
    }

    private fun clearStaging(directory: File): Boolean = try {
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
        // walkFileTree does not follow nested links; never touch anything outside this job.
        java.nio.file.Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                if (dir != directory.toPath()) java.nio.file.Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        true
    } catch (_: Exception) { false }

    /**
     * Caller schedules authorized maintenance. The engine owns the updater: it runs in [updateScope] holding the
     * exclusive gate from the first version read until the new yt-dlp passed its self-test or the bundled one was
     * restored. The caller only stops waiting after [EngineLimits.updateMs]; the gate stays closed until then.
     */
    override suspend fun updateYtdlp(): EngineUpdateResult {
        val task = synchronized(updateOwner) {
            pendingUpdate?.takeIf { it.isActive } ?: updateScope.async { runUpdate() }.also { pendingUpdate = it }
        }
        return withTimeoutOrNull(limits.updateMs) { task.await() } ?: EngineUpdateResult.Failure(DownloadError.Network)
    }
    private suspend fun runUpdate(): EngineUpdateResult = operation("update-${UUID.randomUUID()}",
        { error, _ -> EngineUpdateResult.Failure(error) }, exclusive = true) { current ->
        if (!initialize()) return@operation EngineUpdateResult.Failure(DownloadError.EngineUnavailable)
        val old = version(current)
        // An updater can replace the binary and then fail; never retain the previous pin.
        runtime.initialization.installedVersion = null
        val outcome = runCatching { runtime.update() }
        val updated = probe()
        if (updated != null) {
            runtime.initialization.installedVersion = updated
            outcome.getOrThrow()
            EngineUpdateResult.Success(old, updated)
        } else {
            runtime.restoreBundledYtdlp()
            probe()?.let { runtime.initialization.installedVersion = it }
            EngineUpdateResult.Failure(DownloadError.Failed, usingBuiltIn = true)
        }
    }

    /** No-network `--version` under its own deadline and canceller; null when it fails or hangs (tree destroyed). */
    private suspend fun probe(): String? = operation("probe-${UUID.randomUUID()}", { _, _ -> null }, gated = false) { current ->
        executeVersion(current).trim().takeIf { it.isNotBlank() }?.also(runtime::rememberWarmedVersion)
    }

    override fun cancel(operationId: String) {
        synchronized(lifecycleLock) {
            operations[operationId]?.let { current ->
                if (!current.finished.get()) { current.cancelled.set(true); current.cancellation.complete(Unit) }
                return
            }
            // A brief pre-registration window, never a permanent tombstone for an unknown ID.
            if (operationId in recentlyFinished) return
            if (pendingCancellation.containsKey(operationId)) return
            val pending = PendingCancellation()
            pendingCancellation[operationId] = pending
            cancellationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(CANCELLATION_WINDOW_MS)
                synchronized(lifecycleLock) { pendingCancellation.remove(operationId, pending) }
            }
        }
    }
    private fun initialize() = runtime.initialization.ensure(runtime::resetPartialInstall, runtime::needsExtraction,
        runtime::initialize)
    private suspend fun version(current: Operation): String = runtime.initialization.versionGate.withLock {
        // Every holder's actual process is bounded; admission and extraction retain their own waits.
        runtime.initialization.installedVersion ?: (runtime.warmedVersion() ?: executeVersion(current).trim().also {
            require(it.isNotBlank())
            runtime.rememberWarmedVersion(it)
        }).also { runtime.initialization.installedVersion = it }
    }
    private suspend fun executeVersion(current: Operation): String {
        val previous = current.deadline
        val selfTest = System.nanoTime() + limits.selfTestMs * 1_000_000
        current.deadline = if (previous == NO_DEADLINE || selfTest - previous < 0) selfTest else previous
        try { return execute(current, VERSION_ARGS) }
        finally { current.deadline = previous } // Do not spend a later media operation under the probe's budget.
    }
    private fun checkCancelled(current: Operation) {
        if (current.cancelled.get()) throw OperationCancelled()
    }
    private suspend fun execute(current: Operation, args: List<String>, line: (String) -> Unit = {}): String {
        checkCancelled(current)
        // ProcessTree adopts the single new child whose argv matches; two live children with the same argv
        // (two version reads, a re-inspected link) could adopt each other's tree. The wrapper hides the PID,
        // so identical argv never runs concurrently. Waiting honours cancel and deadlines.
        while (!synchronized(runningArgv) { runningArgv.add(args) }) { delay(20); checkCancelled(current) }
        try {
            checkCancelled(current)
            var callbackFailure: Exception? = null
            val result = runtime.execute(args, current.processId) { text ->
                if (!current.cancelled.get()) try { line(text) } catch (failure: Exception) {
                    // Upstream callback runs on its reader thread: do not throw and strand the child.
                    callbackFailure = failure; current.cancelled.set(true)
                }
            }
            callbackFailure?.let { throw it }
            checkCancelled(current)
            return result
        } finally { synchronized(runningArgv) { runningArgv.remove(args) } }
    }
    private class OperationCancelled : Exception()

    private suspend fun <T> operation(id: String, failure: (DownloadError, DownloadFailureCause) -> T, lockTimeoutMs: Long? = null,
        exclusive: Boolean = false, gated: Boolean = true, body: suspend (Operation) -> T): T = coroutineScope {
        val current = Operation()
        synchronized(lifecycleLock) {
            if (operations.containsKey(id)) return@coroutineScope failure(DownloadError.ExecutionRestricted, DownloadFailureCause.OTHER)
            operations[id] = current
            recentlyFinished.remove(id)
            pendingCancellation.remove(id)?.let { pending ->
                if (System.nanoTime() - pending.deadline < 0) current.cancelled.set(true)
            }
        }
        val parent = currentCoroutineContext().job
        // Independent of parent cancellation so registration retries survive until execute unwinds.
        val canceller = launch(NonCancellable + Dispatchers.IO) {
            var destroyed = false
            while (!current.finished.get()) {
                try { runtime.observe(current.processId) } catch (_: Exception) { /* Retry transient proc reads. */ }
                if (!parent.isActive) current.cancelled.set(true)
                synchronized(current) {
                    val deadline = current.deadline
                    if (deadline != NO_DEADLINE && System.nanoTime() - deadline >= 0 && !current.cancelled.get()) {
                        current.timeoutCause = if (current.byteWatchdog) DownloadFailureCause.STALL else DownloadFailureCause.TRANSPORT
                        current.timedOut.set(true); current.cancelled.set(true)
                    }
                }
                if (current.cancelled.get()) current.cancellation.complete(Unit)
                if (current.cancelled.get() && !destroyed) {
                    destroyed = try { runtime.destroy(current.processId) } catch (_: Exception) { false }
                }
                delay(50)
            }
        }
        var permits = 0
        try {
            val acquire: suspend () -> Unit = { permits = if (exclusive) gate.lockExclusive() else gate.lockShared() }
            if (gated) {
                waitingState.update { it + id }
                try {
                    // Admission has its own budget. Never spend the metadata/network deadline on replacement.
                    // cancel(id) must interrupt this wait even though it does not cancel the caller's coroutine.
                    val awaitAdmission: suspend () -> Unit = {
                        val admission = async { acquire() }
                        try {
                            select<Unit> {
                                admission.onAwait { }
                                current.cancellation.onAwait { throw OperationCancelled() }
                            }
                        } finally { withContext(NonCancellable) { admission.cancelAndJoin() } }
                    }
                    // A null limit preserves the updater/version path's untimed, cancellable admission.
                    // Do not add a media timeout while first extraction holds a share; updateMs still bounds its caller.
                    val admitted = if (lockTimeoutMs == null) { awaitAdmission(); Unit }
                        else withTimeoutOrNull(EngineReadiness.remainingMillis(lockTimeoutMs)) { awaitAdmission() }
                    if (admitted == null) {
                        // The pinned updater has no safe abort API: a timeout must never bypass its writer gate.
                        current.timedOut.set(true); current.cancelled.set(true)
                    }
                } finally { waitingState.update { it - id } }
            } // Ungated probes are nested inside an operation that already holds the writer gate.
            withContext(Dispatchers.IO) {
                checkCancelled(current)
                val result = body(current)
                currentCoroutineContext().ensureActive()
                checkCancelled(current)
                result
            }
        } catch (cancel: CancellationException) {
            current.cancelled.set(true)
            throw cancel
        } catch (error: Exception) {
            // Never log raw engine output: it may contain source URLs/cookies, even in debug.
            val diagnostic = YtdlpDiagnostics().failure(error)
            val httpTimeout = current.timedOut.get() && !diagnostic.resumeAllowed &&
                diagnostic.failureCause == DownloadFailureCause.HTTP
            val mapped = when {
                httpTimeout -> diagnostic.error
                current.timedOut.get() -> DownloadError.Network
                current.cancelled.get() -> DownloadError.Cancelled
                error is UnsupportedMetadataException -> DownloadError.Unsupported
                else -> diagnostic.error
            }
            val cause = when {
                httpTimeout -> DownloadFailureCause.HTTP
                current.timedOut.get() -> current.timeoutCause
                current.cancelled.get() -> DownloadFailureCause.OTHER
                else -> diagnostic.failureCause
            }
            failure(mapped, cause)
        } finally {
            synchronized(lifecycleLock) {
                current.finished.set(true)
                pendingCancellation.remove(id)
                recentlyFinished[id] = Unit
                if (recentlyFinished.size > 128) recentlyFinished.remove(recentlyFinished.keys.first())
            }
            // Do not cancel an in-flight TERM/grace/KILL after Python exits: descendants
            // can still hold descriptors or write staging. Cleanup belongs to the caller.
            withContext(NonCancellable) { canceller.join() }
            synchronized(lifecycleLock) { operations.remove(id, current) }
            gate.unlock(permits)
        }
    }
    private companion object {
        /**
         * Process-wide. Executions share it: 0.18.1 `executeImpl` builds a fresh ProcessBuilder, buffers and env
         * per call, keys processes in a synchronized map by our UUIDs and forces `--no-cache-dir`; its static
         * paths are written only by the synchronized `init`. Update/rollback rewrite yt-dlp, so they are exclusive.
         */
        val gate = NativeGate()
        const val CANCELLATION_WINDOW_MS = 500L
        const val NO_DEADLINE = Long.MIN_VALUE
        val VERSION_ARGS = listOf("--ignore-config", "--version")
        val POSTPROCESSORS = listOf("[Fixup", "[ffmpeg]", "[VideoRemuxer]", "[VideoConvertor]", "[Metadata]", "[MoveFiles]",
            "[EmbedThumbnail]", "[ThumbnailsConvertor]", "[ExtractAudio]", "[MetadataParser]")
        val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val updateOwner = Any()
        /** The one updater; a second request joins it instead of starting another. */
        @Volatile var pendingUpdate: Deferred<EngineUpdateResult>? = null
        val runningArgv = HashSet<List<String>>()
    }
}

/** Shared/exclusive gate for suspending callers; the FIFO semaphore keeps a waiting update from starving. */
internal class NativeGate(private val permits: Int = 8) {
    init { require(permits > 1) } // unlock() tells exclusive from shared by the count held.
    private val shared = kotlinx.coroutines.sync.Semaphore(permits)
    private val writer = kotlinx.coroutines.sync.Mutex()
    suspend fun lockShared(): Int { shared.acquire(); return 1 }
    suspend fun lockExclusive(): Int {
        writer.lock()
        var held = 0
        try { repeat(permits) { shared.acquire(); held++ } }
        catch (cancelled: CancellationException) { repeat(held) { shared.release() }; writer.unlock(); throw cancelled }
        return permits
    }
    fun unlock(held: Int) {
        if (held == 0) return
        repeat(held) { shared.release() }
        if (held == permits) writer.unlock()
    }
    suspend fun <T> shared(block: suspend () -> T): T { val held = lockShared(); try { return block() } finally { unlock(held) } }
    suspend fun <T> exclusive(block: suspend () -> T): T { val held = lockExclusive(); try { return block() } finally { unlock(held) } }
}
