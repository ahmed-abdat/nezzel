package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Intake may warm local binaries only. Inspect requires Check; download requires an explicitly confirmed command.
 * Real adapters dispatch blocking work to IO, disable playlists/config execution, use exact IDs,
 * and terminate all subprocesses on cancellation (including a cancel before registration).
 * CancellationException propagates for coroutine cancellation; cancel(id) returns typed Cancelled.
 */
interface EngineAdapter {
    val isAvailable: Boolean
    /** May initialize local binaries on IO; never inspects a URL or updates over the network. */
    suspend fun installedVersion(): String? = null
    /** Local extraction and one version probe per installed engine, on IO. No URL inspection, updater, or network. */
    suspend fun warmUp(): Boolean = false
    /** True while the bundled engine is unpacked for the first time (one-time "Preparing engine…"). */
    val preparing: StateFlow<Boolean> get() = notPreparing
    /** Operation IDs suspended at the native maintenance barrier; never URLs. */
    val waitingOperations: StateFlow<Set<String>> get() = notWaiting
    /** User Retry after a failed local initialization: skip the remaining backoff. */
    fun retryInitialization() = Unit
    /** STABLE binary maintenance; caller owns cadence and transfer exclusion. */
    suspend fun updateYtdlp(): EngineUpdateResult = EngineUpdateResult.Failure(DownloadError.EngineUnavailable)
    /** Network-only first stage of [updateYtdlp]: touches neither the installed binary nor the native gate; cancellable. */
    suspend fun prepareYtdlpUpdate(): PreparedUpdate = PreparedUpdate.Unstaged
    /** Local second stage; runs to completion once begun. Consumes [prepared]. */
    suspend fun installYtdlpUpdate(prepared: PreparedUpdate): EngineUpdateResult =
        if (prepared is PreparedUpdate.Failed) prepared.failure else updateYtdlp()
    /** Drops a prepared download without installing it. */
    fun discardYtdlpUpdate(prepared: PreparedUpdate) = Unit
    /** Explicit playlist-listing path; ordinary inspection remains single-video. */
    suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken? = null): PlaylistResult =
        PlaylistResult.Failure(DownloadError.Unsupported)
    suspend fun inspect(source: LinkRevision, operationId: String): InspectResult
    /** Renew a lazy playlist selection after interruption; bypass reusable inspection results. */
    suspend fun inspectFresh(source: LinkRevision, operationId: String): InspectResult = inspect(source, operationId)
    /** Local best-effort persistence of already inspected metadata, after explicit confirmation. */
    fun prepareDownload(command: DownloadCommand) = Unit
    /** yt-dlp-shaped JSON a native reader already fetched for [link]; a later inspect may use it. Memory only. */
    fun prefetched(link: ValidatedLink, json: String) = Unit
    /** Clear link history: forget every link's recent result. */
    fun clearRecentLinks() = Unit
    /** Local selection only; transfer ownership is durable in staging, never inferred from byte names alone. */
    fun resumeEngine(command: DownloadCommand, directory: File): String = "ytdlp"
    suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult
    fun cancel(operationId: String)
}

/** Result of [EngineAdapter.prepareYtdlpUpdate]; engine-staged releases stay internal to the engine. */
sealed interface PreparedUpdate {
    /** No separate network stage: installing runs the whole update. */
    data object Unstaged : PreparedUpdate
    /** The network stage failed; nothing was downloaded or kept. */
    data class Failed(val failure: EngineUpdateResult.Failure) : PreparedUpdate
}

private val notWaiting:StateFlow<Set<String>> = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
private val notPreparing: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

sealed interface InspectResult {
    data class Success(val inspection: Inspection) : InspectResult
    data class Failure(val error: DownloadError) : InspectResult
}

sealed interface EngineEvent {
    /** Cumulative transfer bytes across streams; estimates never become exact implicitly. */
    data class Progress(val bytesDownloaded: Long, val total: Size = Size.Unknown,
        val fragmentIndex: Long? = null, val fragmentCount: Long? = null,
        val restartEpoch: Long = 0) : EngineEvent
    /** Relative to this download call; the journal adds its durable attempt epoch. */
    data class Restarting(val restartEpoch: Long) : EngineEvent
    /** Unmeasured post-processing, including lossless extraction and explicit audio conversion. */
    data object Merging : EngineEvent
}

sealed interface TransferResult {
    data class Success(val output: StagedOutput) : TransferResult
    data class Failure(val error: DownloadError, val resumeAllowed: Boolean = true,
        val cause: DownloadFailureCause = error.cause) : TransferResult
}

/** A single nonempty staged file, not a published/saved download. */
class StagedOutput private constructor(val file: File) {
    companion object {
        fun validate(directory: File, outputs: List<File>): StagedOutput {
            require(outputs.size == 1)
            val root = directory.canonicalFile
            val file = outputs.single().canonicalFile
            require(root.isDirectory && file != root && file.toPath().startsWith(root.toPath()))
            require(file.isFile && file.length() > 0)
            return StagedOutput(file)
        }
    }
}
