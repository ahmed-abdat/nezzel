package dev.ahmed.savlino.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import dev.ahmed.savlino.domain.FilenameSanitizer
import dev.ahmed.savlino.jobs.TransferSpacePolicy
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

sealed interface RecoveryResult
sealed interface PublicationResult
/** Journal this URI immediately after insertion, before starting the copy. */
data class PendingPublication(val uri: Uri, val displayName: String) : RecoveryResult
data class PublishedFile(val uri: Uri, val displayName: String, val cleanupFailed: Boolean = false) : RecoveryResult, PublicationResult
data object MissingPublication : RecoveryResult
/** An acknowledged failure: staging is retained and the convenience API abandons the pending row. */
data class CommitFailed(val pending: PendingPublication, val error: StorageError = StorageError.SaveFailed) : PublicationResult
/** The provider may have committed. Persist/recover this URI; never blindly retry publication. */
data class CommitUncertain(val pending: PendingPublication) : PublicationResult

interface Publisher {
    fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication
    /** Source must come from Job.requireOutput. A failed copy abandons only this pending row. */
    fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal = CancellationSignal())
    /** Call only after copy returned successfully; U10 must journal that boundary. */
    fun commit(pending: PendingPublication): PublicationResult
    fun recover(uri: Uri): RecoveryResult
    fun abandon(uri: Uri): RecoveryResult
    /** Startup cleanup must recheck this gate under SaveDestinations.lock before any mutation. */
    fun abandon(uri: Uri, cancellation: CancellationSignal, sweepOpen: () -> Boolean): RecoveryResult =
        throw UnsupportedOperationException()
    /** Durable custom-folder receipts, independent of removable/retryable job metadata. Images have their own sweep. */
    fun pendingCleanupUris(): List<Uri> = emptyList()
    fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
        cancellation: CancellationSignal = CancellationSignal()): PublicationResult
}

/** Blocking I/O: the later job coordinator must call this off the main thread, after confirmation. */
class MediaStorePublisher internal constructor(
    private val resolver: ContentResolver,
    private val space: SpaceGate,
    private val ownerPackage: String,
    private val relativePath: String = "Download/Nezzel/",
    private val syncOutput: (FileDescriptor) -> Unit,
) : Publisher {
    constructor(resolver: ContentResolver, space: SpaceGate, ownerPackage: String, relativePath: String = "Download/Nezzel/") :
        this(resolver, space, ownerPackage, relativePath, FileDescriptor::sync)

    override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
        job.requireActive()
        return synchronized(nameLock) {
            val hint = uniqueName(FilenameSanitizer.sanitize(name.substringBeforeLast('.'), name.substringAfterLast('.')))
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, hint)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: throw StorageException(StorageError.SaveFailed)
            try {
                // MediaProvider chooses the final name; collection queries cannot see all collisions.
                recover(uri) as? PendingPublication ?: throw StorageException(StorageError.InvalidPublication)
            } catch (failure: Exception) {
                val error = storageFailure(failure)
                try { abandon(uri) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }

    override fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal) {
        try {
            requirePending(pending)
            if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw StorageException(StorageError.SaveFailed)
            val expected = source.length()
            if (expected <= 0) throw StorageException(StorageError.SaveFailed)
            space.beforePublication(expected)
            // Opening with NOFOLLOW closes the validation/open gap for a substituted final symlink.
            Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                cancellation.throwIfCanceled()
                val descriptor = resolver.openFileDescriptor(pending.uri, "w") ?: throw StorageException(StorageError.SaveFailed)
                descriptor.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        var copied = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            cancellation.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count == -1) break
                            cancellation.throwIfCanceled()
                            output.write(buffer, 0, count)
                            copied += count
                        }
                        if (copied != expected || source.length() != expected) throw StorageException(StorageError.CopySizeMismatch)
                        cancellation.throwIfCanceled()
                        syncOutput(output.fd)
                    }
                }
            }
        } catch (failure: Exception) {
            val error = storageFailure(failure)
            try { abandon(pending.uri) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun commit(pending: PendingPublication): PublicationResult {
        val current = recover(pending.uri)
        if (current is PublishedFile) return current
        if (current !is PendingPublication) throw StorageException(StorageError.InvalidPublication)
        return try {
            val count = resolver.update(pending.uri, ContentValues().apply {
                put(MediaStore.Downloads.TITLE, current.displayName.substringBeforeLast('.'))
                put(MediaStore.Downloads.IS_PENDING, 0)
            },
                OWN_PENDING, arrayOf("1", ownerPackage, relativePath))
            if (count == 1) PublishedFile(current.uri, current.displayName) else CommitFailed(current)
        } catch (_: Exception) {
            // Even a failed Binder acknowledgement can follow a successful provider commit.
            CommitUncertain(current)
        }
    }

    override fun recover(uri: Uri): RecoveryResult {
        validateUri(uri)
        val columns = arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING)
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw StorageException(StorageError.SaveFailed)
        return cursor.use {
            if (!it.moveToFirst()) return@use MissingPublication
            if (it.getString(1) != ownerPackage || it.getString(2) != relativePath) {
                throw StorageException(StorageError.InvalidPublication)
            }
            val name = it.getString(0) ?: throw StorageException(StorageError.SaveFailed)
            when (it.getInt(3)) {
                1 -> PendingPublication(uri, name)
                0 -> PublishedFile(uri, name)
                else -> throw StorageException(StorageError.InvalidPublication)
            }
        }
    }

    override fun abandon(uri: Uri): RecoveryResult {
        val state = recover(uri)
        if (state !is PendingPublication) return state // Adopt already committed media, never delete it.
        resolver.delete(uri, OWN_PENDING, arrayOf("1", ownerPackage, relativePath))
        // If commit raced the conditional delete, adopt the published row instead.
        return recover(uri)
    }

    override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
        cancellation: CancellationSignal): PublicationResult {
        var pending: PendingPublication? = null
        var committing = false
        try {
            cancellation.throwIfCanceled()
            val source = job.requireOutput(stagedFile)
            space.beforePublication(source.length())
            pending = insertPending(job, FilenameSanitizer.sanitize(title, extension), mimeType)
            copy(pending, source, cancellation)
            cancellation.throwIfCanceled()
            committing = true
            return when (val result = commit(pending)) {
                is PublishedFile -> {
                    // Commit wins over late cancellation. Cleanup cannot roll back published bytes.
                    val cleanupFailed = try { job.cleanup(); false } catch (_: Exception) { true }
                    result.copy(cleanupFailed = cleanupFailed)
                }
                is CommitFailed -> {
                    // Keep staging for retry even when pending-row cleanup fails.
                    try { abandon(pending.uri) } catch (_: Exception) { /* URI is retained in the result. */ }
                    result
                }
                is CommitUncertain -> result
            }
        } catch (failure: Exception) {
            val error = storageFailure(failure)
            if (!committing) {
                pending?.let { try { abandon(it.uri) } catch (cleanup: Exception) { error.addSuppressed(cleanup) } }
                try { job.cleanup() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    private fun requirePending(pending: PendingPublication) {
        if (recover(pending.uri) !is PendingPublication) throw StorageException(StorageError.InvalidPublication)
    }

    private fun validateUri(uri: Uri) {
        if (uri.scheme != collection.scheme || uri.authority != collection.authority || uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != collection.pathSegments.size + 1 ||
            uri.pathSegments.dropLast(1) != collection.pathSegments || uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } != true) {
            throw StorageException(StorageError.InvalidPublication)
        }
    }

    private fun uniqueName(base: String): String {
        val names = mutableSetOf<String>()
        val path = MediaStore.Downloads.RELATIVE_PATH
        val cursor = resolver.query(collection, arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "($path = ? OR $path = ?)", arrayOf(relativePath, relativePath.trimEnd('/')), null)
            ?: throw StorageException(StorageError.SaveFailed)
        cursor.use { while (it.moveToNext()) names += it.getString(0).lowercase(Locale.ROOT) }
        if (base.lowercase(Locale.ROOT) !in names) return base
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.')
        var suffix = 1L
        while (true) {
            val tail = " ($suffix).$ext"
            var shortened = stem
            while ((shortened + tail).toByteArray(Charsets.UTF_8).size > 240) {
                shortened = shortened.dropLast(Character.charCount(shortened.codePointBefore(shortened.length)))
            }
            val candidate = shortened + tail
            if (candidate.lowercase(Locale.ROOT) !in names) return candidate
            suffix++
        }
    }

    private fun storageFailure(failure: Exception) = when {
        failure is OperationCanceledException -> StorageException(StorageError.Cancelled)
        TransferSpacePolicy.isNoSpace(failure) -> StorageException(StorageError.InsufficientSpace)
        failure is StorageException -> failure
        else -> StorageException(StorageError.SaveFailed, failure)
    }

    private companion object {
        const val OWN_PENDING = "is_pending = ? AND owner_package_name = ? AND relative_path = ?"
        val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val nameLock = Any()
    }
}
