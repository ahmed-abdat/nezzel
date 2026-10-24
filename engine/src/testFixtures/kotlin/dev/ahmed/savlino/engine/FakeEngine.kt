package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Test fixtures only; never bind to a debug or release production graph. */
class FakeEngine(
    val inspectionResult: InspectResult = InspectResult.Failure(DownloadError.NoFormats),
    val steps: List<Step> = emptyList(),
    val waitForInspectCancellation: Boolean = false,
    val inspectionScripts: Map<String, InspectionScript> = emptyMap(),
    val playlistResult: PlaylistResult = PlaylistResult.Failure(DownloadError.Unsupported),
) : EngineAdapter {
    data class InspectionScript(val result: InspectResult, val awaitRelease: Boolean = false)
    sealed interface Step {
        data class Emit(val event: EngineEvent) : Step
        data object AwaitCancellation : Step
        data class Fail(val error: DownloadError) : Step
        data class Output(val relativeName: String, val bytes: ByteArray) : Step
    }
    private val recordedCommands = mutableListOf<DownloadCommand>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val operations = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val inspectionReleases = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val commands: List<DownloadCommand> get() = synchronized(recordedCommands) { recordedCommands.toList() }
    /** May be called before registration. Releases only this operation, never another URL's result. */
    fun releaseInspection(operationId: String) {
        inspectionReleases.computeIfAbsent(operationId) { CompletableDeferred() }.complete(Unit)
    }
    override val isAvailable: Boolean get() = true
    override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?): PlaylistResult = playlistResult
    override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
        val cancellation = register(operationId) ?: return InspectResult.Failure(DownloadError.InvalidInput)
        try {
            currentCoroutineContext().ensureActive()
            val script = inspectionScripts[operationId]
            if (script?.awaitRelease == true) {
                val release = inspectionReleases.computeIfAbsent(operationId) { CompletableDeferred() }
                select<Unit> {
                    cancellation.onAwait { }
                    release.onAwait { }
                }
            }
            if (waitForInspectCancellation) cancellation.await()
            if (operationId in cancelled) return InspectResult.Failure(DownloadError.Cancelled)
            val result = script?.result ?: inspectionResult
            return if (result is InspectResult.Success && !result.inspection.source.matches(source))
                InspectResult.Failure(DownloadError.FormatExpired) else result
        } finally {
            operations.remove(operationId, cancellation)
            inspectionReleases.remove(operationId)
        }
    }

    override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
        val cancellation = register(command.jobId) ?: return TransferResult.Failure(DownloadError.InvalidInput)
        synchronized(recordedCommands) { recordedCommands += command }
        var created: File? = null
        var succeeded = false
        try {
            for (step in steps) {
                currentCoroutineContext().ensureActive()
                if (command.jobId in cancelled) return TransferResult.Failure(DownloadError.Cancelled)
                when (step) {
                    is Step.Emit -> progress(step.event)
                    is Step.Fail -> return TransferResult.Failure(step.error)
                    Step.AwaitCancellation -> {
                        cancellation.await()
                        return TransferResult.Failure(DownloadError.Cancelled)
                    }
                    is Step.Output -> {
                        val output = withContext(Dispatchers.IO) {
                            require(!File(step.relativeName).isAbsolute)
                            val root = directory.canonicalFile
                            val target = File(directory, step.relativeName).canonicalFile
                            require(root.isDirectory && target != root && target.toPath().startsWith(root.toPath()))
                            require(target.createNewFile()) // Never overwrite even in fixtures.
                            created = target
                            target.writeBytes(step.bytes)
                            StagedOutput.validate(root, listOf(target))
                        }
                        currentCoroutineContext().ensureActive()
                        if (command.jobId in cancelled) return TransferResult.Failure(DownloadError.Cancelled)
                        succeeded = true
                        return TransferResult.Success(output)
                    }
                }
            }
            return TransferResult.Failure(if (command.jobId in cancelled) DownloadError.Cancelled else DownloadError.Failed)
        } catch (_: IOException) {
            return TransferResult.Failure(DownloadError.Failed)
        } catch (_: IllegalArgumentException) {
            return TransferResult.Failure(DownloadError.Failed)
        } finally {
            if (!succeeded) withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            operations.remove(command.jobId, cancellation)
        }
    }

    override fun cancel(operationId: String) {
        cancelled += operationId
        operations[operationId]?.complete(Unit)
    }

    private fun register(id: String): CompletableDeferred<Unit>? {
        val signal = CompletableDeferred<Unit>()
        if (operations.putIfAbsent(id, signal) != null) return null
        if (id in cancelled) signal.complete(Unit) // Covers cancellation before registration.
        return signal
    }
}
