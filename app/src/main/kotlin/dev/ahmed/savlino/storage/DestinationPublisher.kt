package dev.ahmed.savlino.storage

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import dev.ahmed.savlino.domain.FilenameSanitizer
import dev.ahmed.savlino.jobs.TransferSpacePolicy
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/** One destination seam; MediaStore's publication protocol remains unchanged. */
class DestinationPublisher(context: Context, space: SpaceGate,
    private val destinations: SaveDestinations = SaveDestinations(context)) : Publisher {
    private val resolver = context.contentResolver
    private val default = MediaStorePublisher(resolver, space, context.packageName)

    override fun pendingCleanupUris(): List<Uri> = destinations.pendingJobFiles()

    override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
        return insertPendingAt(destinations.destination, job, name, mimeType)
    }
    private fun insertPendingAt(destination: SaveDestination, job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
        if (destination is SaveDestination.Default) return default.insertPending(job, name, mimeType)
        destination as SaveDestination.Tree
        return folderOperation {
            job.requireActive()
            val folderName = destinations.folderName(destination.uri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(destination.uri, DocumentsContract.getTreeDocumentId(destination.uri))
            val uri = DocumentsContract.createDocument(resolver, parent, mimeType, name)
                ?: throw StorageException(StorageError.FolderUnavailable)
            try {
                // Before any bytes. This URI includes the chosen tree; recovery never consults the current selection.
                destinations.record(uri, name, mimeType, false, folderName)
                destinations.recordPendingState(uri)
                recover(uri) as? PendingPublication ?: throw StorageException(StorageError.FolderUnavailable)
            } catch (failure: Exception) {
                try { DocumentsContract.deleteDocument(resolver, uri); destinations.forget(uri) } catch (_: Exception) { }
                throw failure
            }
        }
    }

    override fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal) {
        if (!DocumentsContract.isTreeUri(pending.uri)) return default.copy(pending, source, cancellation)
        folderOperation {
            check(recover(pending.uri) is PendingPublication)
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) || source.length() <= 0)
                throw StorageException(StorageError.SaveFailed)
            val expected = source.length()
            try {
                Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    cancellation.throwIfCanceled()
                    // SAF can return a pipe; closing the stream, rather than fsync on a non-file fd, is its boundary.
                    (resolver.openOutputStream(pending.uri, "wt") ?: throw FileNotFoundException()).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            cancellation.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                        }
                        cancellation.throwIfCanceled()
                        if (copied != expected || source.length() != expected) throw StorageException(StorageError.CopySizeMismatch)
                    }
                }
            } finally {
                // Failure to read metadata must never replace the transfer error or justify deletion.
                try { destinations.recordPendingState(pending.uri) } catch (_: Exception) { }
            }
        }
    }

    override fun commit(pending: PendingPublication): PublicationResult {
        if (!DocumentsContract.isTreeUri(pending.uri)) return default.commit(pending)
        return folderOperation {
            synchronized(SaveDestinations.lock) {
                when (val current = recover(pending.uri)) {
                    is PublishedFile -> current
                    is PendingPublication -> {
                        val receipt = requireNotNull(destinations.receipt(pending.uri))
                        destinations.record(current.uri, current.displayName, receipt.getString("mime"), true)
                        PublishedFile(current.uri, current.displayName)
                    }
                    MissingPublication -> throw StorageException(StorageError.FolderUnavailable)
                }
            }
        }
    }

    override fun recover(uri: Uri): RecoveryResult {
        if (!DocumentsContract.isTreeUri(uri)) return default.recover(uri)
        return folderOperation { inspect(uri, destinations.receipt(uri), CancellationSignal(), false) }
    }

    /** Fingerprints authorize deletion only; cloud metadata drift must not break copy/commit. */
    private fun inspect(uri: Uri, receipt: org.json.JSONObject?, cancellation: CancellationSignal,
        forCleanup: Boolean): RecoveryResult {
        cancellation.throwIfCanceled()
        destinations.requireGrant(SaveDestinations.treeOf(uri))
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            android.os.Bundle.EMPTY, cancellation)?.use {
            if (!it.moveToFirst()) return@use MissingPublication
            if (receipt == null) throw StorageException(StorageError.InvalidPublication)
            val name = it.getString(0) ?: receipt.getString("name")
            if (receipt.getBoolean("published")) PublishedFile(uri, name) else {
                val matches = !forCleanup || ((receipt.has("cleanupSize") || receipt.has("cleanupModified")) &&
                    (!receipt.has("cleanupSize") || (!it.isNull(1) && it.getLong(1) == receipt.getLong("cleanupSize"))) &&
                    (!receipt.has("cleanupModified") || (!it.isNull(2) && it.getLong(2) == receipt.getLong("cleanupModified"))))
                if (matches) PendingPublication(uri, name) else MissingPublication
            }
        } ?: run {
            destinations.folderName(SaveDestinations.treeOf(uri), cancellation)
            MissingPublication
        }
    }

    override fun abandon(uri: Uri): RecoveryResult = abandon(uri, CancellationSignal()) { true }

    override fun abandon(uri: Uri, cancellation: CancellationSignal, sweepOpen: () -> Boolean): RecoveryResult {
        if (!DocumentsContract.isTreeUri(uri)) return default.abandon(uri)
        return folderOperation {
            val receipt = synchronized(SaveDestinations.lock) { destinations.receipt(uri) } ?: return@folderOperation MissingPublication
            val revoked = resolver.persistedUriPermissions.none {
                it.uri == SaveDestinations.treeOf(uri) && it.isReadPermission && it.isWritePermission
            }
            val result = if (revoked && !receipt.getBoolean("published")) MissingPublication
                else inspect(uri, receipt, cancellation, true)
            synchronized(SaveDestinations.lock) {
                // The query can return after startup, or after a path-based URI was reused.
                cancellation.throwIfCanceled()
                val current = destinations.receipt(uri)
                if (!sweepOpen() || current == null || current.optString("nonce") != receipt.optString("nonce") ||
                    current.getBoolean("published")) return@synchronized result
                when (result) {
                    is PublishedFile -> result
                    MissingPublication -> { destinations.forget(uri); MissingPublication }
                    is PendingPublication -> {
                        if (!DocumentsContract.deleteDocument(resolver, uri)) throw StorageException(StorageError.SaveFailed)
                        destinations.forget(uri)
                        MissingPublication
                    }
                }
            }
        }
    }

    override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
        cancellation: CancellationSignal): PublicationResult {
        val destination = destinations.destination
        if (destination is SaveDestination.Default) return default.publish(job, stagedFile, title, extension, mimeType, cancellation)
        var pending: PendingPublication? = null
        try {
            cancellation.throwIfCanceled()
            val source = job.requireOutput(stagedFile)
            pending = insertPendingAt(destination, job, FilenameSanitizer.sanitize(title, extension), mimeType)
            copy(pending, source, cancellation)
            cancellation.throwIfCanceled()
            return when (val result = commit(pending)) {
                is PublishedFile -> result.copy(cleanupFailed = try { job.cleanup(); false } catch (_: Exception) { true })
                else -> result
            }
        } catch (failure: Exception) {
            pending?.let { try { abandon(it.uri) } catch (_: Exception) { } }
            throw failure
        }
    }

    private inline fun <T> folderOperation(block: () -> T): T = try { block() } catch (failure: Exception) {
        throw when {
            failure is OperationCanceledException -> StorageException(StorageError.Cancelled)
            TransferSpacePolicy.isNoSpace(failure) -> StorageException(StorageError.InsufficientSpace)
            failure is StorageException -> failure
            failure is SecurityException || failure is FileNotFoundException -> StorageException(StorageError.FolderUnavailable)
            else -> StorageException(StorageError.SaveFailed) // Never retain provider messages (may contain private names/URIs).
        }
    }
}
