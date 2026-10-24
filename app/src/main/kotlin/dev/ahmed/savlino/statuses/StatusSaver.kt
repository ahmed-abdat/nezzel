package dev.ahmed.savlino.statuses

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import dev.ahmed.savlino.jobs.TransferSpacePolicy
import dev.ahmed.savlino.media.ownPendingImages
import dev.ahmed.savlino.storage.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Listing identity, checked against the provider again before and after the read-only copy. */
data class StatusSaveSource(val uri: Uri, val mimeType: String, val sizeBytes: Long? = null,
    val lastModifiedMillis: Long? = null)

sealed interface StatusSaveResult {
    data class Saved(val uri: Uri) : StatusSaveResult
    data object Gone : StatusSaveResult
    data object NoSpace : StatusSaveResult
    data object Failed : StatusSaveResult
    /** The caller cancelled (left the screen); nothing to report. */
    data object Cancelled : StatusSaveResult
}

/** Explicit one-shot saves. No download job/store, source mutations, network, or diagnostic logging. */
class StatusSaver(context: Context,
    private val stagingRoot: File = File(context.noBackupFilesDir, "status-save"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val resolver = context.contentResolver
    private val owner = context.packageName
    private val directories = JobDirectory(stagingRoot)
    private val space = SpaceBudget(stagingRoot,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
    private val publisher = MediaStorePublisher(resolver, space, owner, relativePath = RELATIVE_PATH)

    suspend fun save(source: StatusSaveSource, cancellation: CancellationSignal = CancellationSignal()): StatusSaveResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                var job: JobDirectory.Job? = null
                try {
                    currentCoroutineContext().ensureActive()
                    cancellation.throwIfCanceled()
                    val before = metadata(source.uri, cancellation) ?: return@withLock StatusSaveResult.Gone
                    if (!matches(source, before)) return@withLock StatusSaveResult.Gone
                    val mime = before.mimeType
                    if (!mime.startsWith("image/") && !mime.startsWith("video/")) return@withLock StatusSaveResult.Failed
                    val extension = KNOWN_EXTENSIONS[mime] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                        ?: return@withLock StatusSaveResult.Failed
                    if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) return@withLock StatusSaveResult.Failed
                    space.beforeTransfer(before.size, before.size, UNKNOWN_SIZE_BOUND)
                    val stage = directories.createOrAdopt("status-${nextId.incrementAndGet()}")
                    job = stage
                    val file = stage.file("output", extension)
                    var copied = 0L
                    openSource(source.uri)?.use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                cancellation.throwIfCanceled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied = Math.addExact(copied, count.toLong())
                                if (before.size != null && copied > before.size) return@withLock StatusSaveResult.Gone
                                if (before.size == null && copied > UNKNOWN_SIZE_BOUND) return@withLock StatusSaveResult.Failed
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    } ?: return@withLock StatusSaveResult.Gone
                    val after = metadata(source.uri, cancellation) ?: return@withLock StatusSaveResult.Gone
                    if (before != after || (before.size != null && copied != before.size)) return@withLock StatusSaveResult.Gone
                    if (copied <= 0 || file.length() != copied || !validMedia(file, mime)) return@withLock StatusSaveResult.Failed
                    currentCoroutineContext().ensureActive()
                    cancellation.throwIfCanceled()
                    val timestamp = before.modified ?: now()
                    val title = "Status-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(timestamp))
                    when (val result = publisher.publish(stage, file, title, extension,
                        // MediaProvider only knows the standard JPEG type.
                        if (mime == "image/jpg") "image/jpeg" else mime, cancellation)) {
                        is PublishedFile -> StatusSaveResult.Saved(result.uri)
                        is CommitFailed -> if (result.error == StorageError.InsufficientSpace) StatusSaveResult.NoSpace else StatusSaveResult.Failed
                        is CommitUncertain -> when (val recovered = publisher.abandon(result.pending.uri)) {
                            is PublishedFile -> StatusSaveResult.Saved(recovered.uri)
                            else -> StatusSaveResult.Failed
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: android.os.OperationCanceledException) {
                    StatusSaveResult.Cancelled
                } catch (_: SourceGone) {
                    StatusSaveResult.Gone
                } catch (error: Exception) {
                    if (TransferSpacePolicy.isNoSpace(error)) StatusSaveResult.NoSpace else StatusSaveResult.Failed
                } finally {
                    // A failed cleanup stays confined to this root and is retried by the startup sweep.
                    job?.let { try { it.cleanup() } catch (_: Exception) { } }
                }
            }
        }

    /** Same lock as save: startup/image cleanup cannot erase an in-flight status or its pending row. */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (Files.exists(stagingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(stagingRoot.toPath())) Files.delete(stagingRoot.toPath())
                else {
                    val root = File(stagingRoot, "downloads")
                    if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
                        else for (file in root.listFiles() ?: throw StorageException(StorageError.SaveFailed)) {
                            // Only our private staging jobs; JobDirectory validates/adopts without following links.
                            if (file.name.matches(Regex("status-[0-9]+"))) directories.createOrAdopt(file.name).cleanup()
                        }
                    }
                }
            }
            for (uri in ownPendingImages(resolver, owner)) abandonIfStatusLocked(uri)
        }
    }

    /** Null means another publication protocol owns this URI; its caller routes it to that publisher. */
    suspend fun abandonIfStatus(uri: Uri): RecoveryResult? = withContext(Dispatchers.IO) {
        lock.withLock { abandonIfStatusLocked(uri) }
    }

    private fun abandonIfStatusLocked(uri: Uri): RecoveryResult? = try {
        publisher.abandon(uri)
    } catch (failure: StorageException) {
        if (failure.error == StorageError.InvalidPublication) null else throw failure
    }

    private class SourceGone : java.io.IOException()
    private fun openSource(uri: Uri) = try { resolver.openInputStream(uri) }
        catch (_: FileNotFoundException) { throw SourceGone() }

    private data class Metadata(val mimeType: String, val size: Long?, val modified: Long?)
    private fun metadata(uri: Uri, cancellation: CancellationSignal): Metadata? {
        if (uri.scheme != "content") return null
        val columns = arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val cursor = try { resolver.query(uri, columns, null, null, null, cancellation) }
            catch (_: FileNotFoundException) { throw SourceGone() }
            ?: throw StorageException(StorageError.SaveFailed)
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val mime = it.getString(0)?.lowercase(Locale.ROOT) ?: resolver.getType(uri)?.lowercase(Locale.ROOT)
                ?: throw StorageException(StorageError.SaveFailed)
            Metadata(mime, if (it.isNull(1)) null else it.getLong(1).takeIf { n -> n >= 0 },
                if (it.isNull(2)) null else it.getLong(2).takeIf { n -> n > 0 })
        }
    }

    private fun matches(source: StatusSaveSource, actual: Metadata): Boolean =
        source.mimeType.lowercase(Locale.ROOT) == actual.mimeType &&
            (source.sizeBytes == null || source.sizeBytes < 0 || source.sizeBytes == actual.size) &&
            (source.lastModifiedMillis == null || source.lastModifiedMillis <= 0 || source.lastModifiedMillis == actual.modified)

    private fun validMedia(file: File, mime: String): Boolean = if (mime.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType?.startsWith("image/") == true
    } else MediaMetadataRetriever().use {
        it.setDataSource(file.absolutePath)
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration -> duration > 0 } == true
    }

    companion object {
        const val RELATIVE_PATH = "Download/Nezzel/Statuses/"
        // Status files are almost always these; the platform table is only the fallback.
        private val KNOWN_EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png",
            "image/webp" to "webp", "image/gif" to "gif", "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/webm" to "webm")
        // Enforced only for providers with unknown size; a safety budget, never a displayed estimate.
        const val UNKNOWN_SIZE_BOUND = 256L * 1024 * 1024
        private val nextId = AtomicLong(System.currentTimeMillis())
        private val lock = Mutex()
    }
}
