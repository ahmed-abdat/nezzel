package dev.ahmed.savlino.jobs

import android.os.CancellationSignal
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.YtdlpCommand
import dev.ahmed.savlino.storage.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Blocking journal reconciliation. U12 owns one instance per journal and runs this I/O off main.
 * The private lock serializes publication/reconciliation without holding JobStore's monitor.
 * The root is exactly filesDir/downloads, as used by JobDirectory; never accept a remote path.
 */
class JobRecovery(private val store: JobStore, private val publisher: Publisher, jobRoot: File) {
    // Resolve Android/macOS aliases in filesDir, but never resolve/adopt a symlink at the job root.
    private val jobRoot = File(requireNotNull(jobRoot.absoluteFile.parentFile).canonicalFile, jobRoot.name)

    private val lock = Any()

    /** Startup-only: call before any worker runs; active jobs must use [reconcile] instead. */
    fun recover(receiptCleanupExecutor: Executor = cleanupExecutor) = synchronized(lock) {
        // Finish known work first. A provider/cleanup failure leaves its durable boundary for next time.
        for (row in store.all()) {
            // Death may separate the atomic fresh-id reservation from old-row/progress cleanup.
            if (row.stage == JobStage.FAILED && BatchStore(store).replaced(row.jobId)) {
                store.remove(row.jobId)
                continue
            }
            when (row.stage) {
                JobStage.PENDING_INSERTED, JobStage.PUBLISHING -> recoverPublication(row)
                JobStage.STAGED -> store.fail(row.jobId, DownloadError.SaveFailed)
                JobStage.TRANSFERRING -> recoverTransfer(row)
                JobStage.FAILED -> { cleanupFailedTree(row); if (!JobStore.keepsPartial(row)) cleanup(row.jobId) }
                JobStage.COMPLETED, JobStage.CANCELLED -> cleanup(row.jobId)
                JobStage.ENQUEUED -> if (row.hasPartial) recoverTransfer(row)
                JobStage.PAUSED -> Unit
            }
        }
        store.pruneTerminal()
        val remaining = store.all()
        val owned = remaining.mapNotNull { it.pendingUri }.toSet()
        // Capture before workers run; a later rescan could include an active create-to-journal window.
        val orphaned = publisher.pendingCleanupUris().filter { it !in owned }
        // Read under the receipt lock before every mutation, but closed WITHOUT it: a provider call hung while
        // holding that lock must never hold startup past the timeout.
        val sweepOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            for (uri in orphaned) {
                val cancellation = CancellationSignal()
                val receiptOpen = java.util.concurrent.atomic.AtomicBoolean(true)
                val task = FutureTask {
                    publisher.abandon(uri, cancellation) { sweepOpen.get() && receiptOpen.get() }
                }
                try {
                    receiptCleanupExecutor.execute(task)
                    task.get(3, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // A provider may ignore cancellation. Its late return cannot mutate receipts.
                    receiptOpen.set(false)
                    task.cancel(false)
                    cleanupExecutor.execute { cancellation.cancel() }
                }
            }
        } finally {
            sweepOpen.set(false)
        }
        val known = remaining.map { it.jobId }.toSet()
        if (safeRoot()) {
            jobRoot.listFiles()?.forEach { path ->
                if (path.name !in known && JobStore.validJobId(path.name) &&
                    Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) cleanup(path.name)
            }
        }
    }

    private fun recoverTransfer(row: JobRecord) {
        try {
            val job = JobDirectory(requireNotNull(jobRoot.parentFile)).createOrAdopt(row.jobId,
                row.command()?.format?.let(YtdlpCommand::finishedSource)) // The run drops it if its recipe changed.
            val hasPartial = stagingBytes(job.directory) > 0
            if (!hasPartial) job.cleanup()
            if (row.stage == JobStage.TRANSFERRING || row.hasPartial != hasPartial)
                store.requeue(row.jobId, hasPartial)
        } catch (_: Exception) {
            if (cleanup(row.jobId)) store.requeue(row.jobId)
            else store.fail(row.jobId, DownloadError.SaveFailed)
        }
    }

    /**
     * Journalled publication only; never transfers or resumes bytes. A process death after insert but
     * before recordPending can leave an unrecorded pending row: do not scan/delete it by name or path.
     * SAF accepts this create-to-journal gap: an unrecorded empty document may remain.
     * MediaStore owns expiry of that row. This gap cannot duplicate committed output (copy is later).
     */
    fun publish(job: JobDirectory.Job, stagedFile: File, mimeType: String,
        cancellation: CancellationSignal = CancellationSignal(),
        cancellationError: () -> DownloadError = { DownloadError.Cancelled }): PublicationResult = synchronized(lock) {
        val id = job.directory.name
        require(job.directory.absoluteFile.parentFile == jobRoot.absoluteFile && safeRoot())
        val row = requireNotNull(store.get(id))
        check(row.stage == JobStage.STAGED)
        try {
            cancellation.throwIfCanceled()
            val source = job.requireOutput(stagedFile)
            val pending = publisher.insertPending(job, publicationName(row.filename, row.format), mimeType)
            try {
                check(store.recordPending(id, pending))
            } catch (failure: Exception) {
                // The returned URI is ours even when the SQLite write fails.
                try { publisher.abandon(pending.uri) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
            publisher.copy(pending, source, cancellation)
            cancellation.throwIfCanceled()
            check(store.markPublishing(id))
            val result = publisher.commit(pending)
            if (result is PublishedFile) {
                check(store.complete(id, result))
                result.copy(cleanupFailed = !cleanup(id))
            } else {
                reconcile(id, cancellation.isCanceled, cancellationError(),
                    publicationError = if (result is CommitFailed && result.error == StorageError.InsufficientSpace)
                        DownloadError.InsufficientSpace else DownloadError.SaveFailed)
                result
            }
        } catch (failure: Exception) {
            reconcile(id, cancellation.isCanceled, cancellationError(),
                publicationError = if (TransferSpacePolicy.isNoSpace(failure)) DownloadError.InsufficientSpace
                    else if (failure is StorageException && failure.error == StorageError.FolderUnavailable) DownloadError.FolderUnavailable
                    else DownloadError.SaveFailed)
            throw failure
        }
    }

    /**
     * Resolve only this job, safe while another job transfers. Provider failures retain the recorded
     * boundary for another reconciliation; published output always wins a late cancellation.
     */
    fun reconcile(jobId: String, cancellationRequested: Boolean = false,
        cancellationError: DownloadError = DownloadError.Cancelled,
        publicationError: DownloadError = DownloadError.SaveFailed): Unit = synchronized(lock) {
        if (cancellationRequested || publicationError in setOf(DownloadError.InsufficientSpace, DownloadError.FolderUnavailable))
            store.requestPublicationFailure(jobId, if (cancellationRequested) cancellationError else publicationError)
        val row = store.get(jobId) ?: return@synchronized
        when (row.stage) {
            JobStage.PENDING_INSERTED, JobStage.PUBLISHING -> recoverPublication(row)
            JobStage.STAGED -> if (cancellationRequested && cancellationError == DownloadError.Cancelled) store.cancel(jobId)
                else store.fail(jobId, if (cancellationRequested) cancellationError else publicationError)
            JobStage.FAILED -> { cleanupFailedTree(row); if (!JobStore.keepsPartial(row)) cleanup(jobId) }
            JobStage.COMPLETED, JobStage.CANCELLED -> cleanup(jobId)
            else -> Unit
        }
    }

    private fun recoverPublication(row: JobRecord) {
        val uri = row.pendingUri ?: return
        try {
            val current = publisher.recover(uri)
            val resolved = if (current is PendingPublication) {
                if (row.stage == JobStage.PUBLISHING && row.error != DownloadError.Cancelled) {
                    // PUBLISHING is durable proof copy returned after sync/size checks.
                    val committed = try { publisher.commit(current) } catch (_: Exception) { null }
                    if (committed is PublishedFile) committed else publisher.abandon(uri)
                } else publisher.abandon(uri)
            } else current
            when (resolved) {
                is PublishedFile -> store.complete(row.jobId, resolved)
                MissingPublication -> store.publicationAbandoned(row.jobId)
                is PendingPublication -> Unit // Delete not acknowledged: retain URI for reconciliation.
            }
        } catch (failure: Exception) {
            if (failure is StorageException && failure.error == StorageError.FolderUnavailable &&
                android.provider.DocumentsContract.isTreeUri(uri)) store.folderUnavailable(row.jobId)
            // Permission/provider failure is not evidence of absence or ownership. Keep recorded URI.
        }
    }

    private fun cleanupFailedTree(row: JobRecord) {
        val uri = row.pendingUri ?: return
        if (!android.provider.DocumentsContract.isTreeUri(uri)) return
        try {
            if (publisher.abandon(uri) == MissingPublication) store.clearFailedPublication(row.jobId, uri)
        } catch (_: Exception) { /* Retain ownership until the user restores access. */ }
    }

    private fun safeRoot(): Boolean = jobRoot.absoluteFile == jobRoot.canonicalFile &&
        !Files.isSymbolicLink(jobRoot.toPath())

    private fun cleanup(id: String): Boolean = cleanupJobStaging(jobRoot, id)

    private companion object {
        val cleanupExecutor = Executors.newCachedThreadPool { task ->
            Thread(task, "destination-receipt-cleanup").apply { isDaemon = true }
        }
    }
}
