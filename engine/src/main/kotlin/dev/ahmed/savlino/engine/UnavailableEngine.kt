package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File

/** Fallback after native initialization fails. No initialization, IO, formats or progress. */
class UnavailableEngine : EngineAdapter {
    override val isAvailable: Boolean get() = false
    override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult = InspectResult.Failure(DownloadError.EngineUnavailable)
    override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult = TransferResult.Failure(DownloadError.EngineUnavailable)
    override fun cancel(operationId: String) = Unit
}
