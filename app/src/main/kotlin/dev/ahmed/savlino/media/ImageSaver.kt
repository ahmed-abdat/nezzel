package dev.ahmed.savlino.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import dev.ahmed.savlino.domain.MediaImageMetadata
import dev.ahmed.savlino.domain.MediaSelection
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.jobs.TransferSpacePolicy
import dev.ahmed.savlino.storage.*
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Proof-bound refetch of one returned image selection into caller staging. Fake point for app tests. */
fun interface ImageFetch {
    suspend fun transfer(selection: MediaSelection, sink: OutputStream): ImageSaveResult
    fun cancel() {}
}

enum class SweepFailure { STAGING, QUERY, ABANDON }

/** Identity-distinct states, so a repeated outcome for the same selection is never conflated away. */
sealed interface ImageSaveState {
    data object Idle : ImageSaveState
    class Saving(val selection: MediaSelection, val current: Int = 1, val total: Int = 1) : ImageSaveState
    class Saved(val selection: MediaSelection, val uri: Uri, val displayName: String) : ImageSaveState
    class BatchFinished(val selection: MediaSelection, val saved: List<Saved>, val total: Int,
        val cancelled: Boolean, val failure: Failed?) : ImageSaveState
    class Failed(val selection: MediaSelection, val image: ImageFailure? = null, val statusCode: Int? = null,
        val storage: StorageError? = null) : ImageSaveState
}

/** A snapshot of one explicitly chosen, returned photo. No network capability is persisted. */
class ImageSaveRequest(val selection: MediaSelection, val image: MediaImageMetadata, val title: String)

/** Reserve the numeric suffix before the existing Unicode/byte-safe filename cap. Keep the real encoding. */
internal fun indexedImageTitle(title: String, index: Int, extension: String): String {
    val suffix = "_$index"
    val max = 80 - suffix.length
    val shortened = if (title.codePointCount(0, title.length) > max)
        title.substring(0, title.offsetByCodePoints(0, max)) else title
    // A longer temporary extension reserves the suffix's UTF-8 bytes within the 240-byte filename limit.
    val reserved = "x".repeat(extension.length + suffix.length)
    return dev.ahmed.savlino.domain.FilenameSanitizer.sanitize(shortened, reserved).removeSuffix(".$reserved") + suffix
}

/**
 * One-shot image Save: no journal, no worker, no notification. Single-flight; a killed process leaves
 * only staging and an own pending image row, both removed by [sweep] before the next Save.
 * Order: space preflight, fresh staging dir, proof-checked transfer, reviewed MediaStore publication.
 */
class ImageSaver(private val fetch: ImageFetch, private val publisher: Publisher, private val stagingRoot: File,
    private val space: SpaceGate, private val scope: CoroutineScope,
    private val syncStaging: (FileDescriptor) -> Unit = FileDescriptor::sync,
    private val pendingImages: () -> List<Uri> = { emptyList() }) {
    private val state = MutableStateFlow<ImageSaveState>(ImageSaveState.Idle)
    val outcome: StateFlow<ImageSaveState> = state
    private val directories = JobDirectory(stagingRoot)
    private val lock = Any()
    private var running: Job? = null
    private var signal: CancellationSignal? = null
    private val startup = Mutex()
    private var clean = false
    private var attempts = 0
    @Volatile private var dirtied = false
    /** Why the last cleanup pass was incomplete. Typed only: never a URI, name or path. */
    @Volatile internal var lastSweepFailure: SweepFailure? = null
        private set

    /** False while another Save runs; nothing is fetched twice. */
    fun save(selection: MediaSelection, image: MediaImageMetadata, title: String): Boolean = synchronized(lock) {
        if (running?.isActive == true) return false
        val cancellation = CancellationSignal()
        signal = cancellation
        state.value = ImageSaveState.Saving(selection)
        running = scope.launch {
            startup.withLock {
                sweepLocked()
                state.value = run(selection, image, title, cancellation)
            }
        }
        true
    }

    /** One explicit batch; publications stay sequential and independent, including after a sibling failure. */
    fun saveBatch(requests: List<ImageSaveRequest>): Boolean = synchronized(lock) {
        if (running?.isActive == true || requests.isEmpty() || requests.size > 20 ||
            requests.map { it.selection }.distinct().size != requests.size) return false
        val chosen = requests.toList()
        val cancellation = CancellationSignal()
        signal = cancellation
        state.value = ImageSaveState.Saving(chosen.first().selection, 1, chosen.size)
        running = scope.launch {
            startup.withLock {
                sweepLocked()
                val saved = ArrayList<ImageSaveState.Saved>()
                var failure: ImageSaveState.Failed? = null
                for ((index, request) in chosen.withIndex()) {
                    if (cancellation.isCanceled) break
                    state.value = ImageSaveState.Saving(request.selection, index + 1, chosen.size)
                    when (val result = run(request.selection, request.image, request.title, cancellation)) {
                        is ImageSaveState.Saved -> saved += result
                        is ImageSaveState.Failed -> {
                            failure = result
                            if (result.image == ImageFailure.CANCELLED) break
                        }
                        else -> Unit
                    }
                }
                state.value = ImageSaveState.BatchFinished(chosen.first().selection, saved.toList(), chosen.size,
                    cancellation.isCanceled || failure?.image == ImageFailure.CANCELLED, failure)
            }
        }
        true
    }

    /** Stops the transfer or the copy; a commit that already happened wins and stays saved. */
    fun cancel() {
        synchronized(lock) { signal }?.cancel()
        fetch.cancel()
    }

    suspend fun awaitIdle() { synchronized(lock) { running }?.join() }

    /**
     * At startup and before each Save until one pass fully succeeds: wipe this saver's staging and abandon own
     * pending image rows. The same barrier protects the entire save/batch, including its final cleanup.
     * A delayed startup call during a save defers to the next save/batch; it must never sweep active files.
     * A failure never blocks the Save; retries stop after [MAX_SWEEP_ATTEMPTS] failed passes.
     */
    suspend fun sweep() {
        if (!startup.tryLock()) return
        try { sweepLocked() } finally { startup.unlock() }
    }

    /** Caller holds [startup] across cleanup and all staging/publication I/O. */
    private suspend fun sweepLocked() {
        if (dirtied) { dirtied = false; clean = false; attempts = 0 }
        if (clean || attempts >= MAX_SWEEP_ATTEMPTS) return
        attempts++
        val failure = withContext(Dispatchers.IO) {
            var failed: SweepFailure? = null
            try { deleteContents(stagingRoot) } catch (_: Exception) { failed = SweepFailure.STAGING }
            val rows = try { pendingImages() } catch (_: Exception) { failed = failed ?: SweepFailure.QUERY; emptyList() }
            for (uri in rows) {
                val left = try { publisher.abandon(uri) } catch (_: Exception) { null }
                if (left == null || left is PendingPublication) failed = failed ?: SweepFailure.ABANDON
            }
            failed
        }
        lastSweepFailure = failure
        if (failure == null) { clean = true; attempts = 0 }
    }

    private suspend fun run(selection: MediaSelection, image: MediaImageMetadata, title: String,
        cancellation: CancellationSignal): ImageSaveState {
        fun failed(reason: ImageFailure? = null, status: Int? = null, storage: StorageError? = null) =
            ImageSaveState.Failed(selection, reason, status, storage)
        var job: JobDirectory.Job? = null
        return try {
            withContext(Dispatchers.IO) {
                stagingRoot.mkdirs() // SpaceBudget fails closed on a missing staging volume path.
                val bytes = image.proof?.measuredBytes
                space.beforeTransfer(bytes, bytes, MAX_IMAGE_BYTES)
                if (cancellation.isCanceled) return@withContext failed(ImageFailure.CANCELLED)
                val directory = directories.create("img-" + UUID.randomUUID())
                job = directory
                val staged = directory.file(title, image.format.extension)
                val result = FileOutputStream(staged).use { output ->
                    fetch.transfer(selection, output).also { if (it is ImageSaveResult.Success) syncStaging(output.fd) }
                }
                val saved = when (result) {
                    is ImageSaveResult.Failure -> return@withContext if (result.reason == ImageFailure.INSUFFICIENT_SPACE)
                        failed(ImageFailure.STAGING, storage = StorageError.InsufficientSpace)
                        else failed(result.reason, result.statusCode)
                    is ImageSaveResult.Success -> result.image
                }
                if (cancellation.isCanceled) return@withContext failed(ImageFailure.CANCELLED)
                when (val published = publisher.publish(directory, staged, title, saved.format.extension, saved.format.mimeType, cancellation)) {
                    is PublishedFile -> ImageSaveState.Saved(selection, published.uri, published.displayName)
                    is CommitFailed -> failed(ImageFailure.STAGING, storage = published.error)
                    is CommitUncertain -> when (val recovered = publisher.recover(published.pending.uri)) {
                        is PublishedFile -> ImageSaveState.Saved(selection, recovered.uri, recovered.displayName)
                        is PendingPublication -> {
                            when (val abandoned = publisher.abandon(recovered.uri)) {
                                is PublishedFile -> ImageSaveState.Saved(selection, abandoned.uri, abandoned.displayName)
                                else -> failed(ImageFailure.STAGING, storage = StorageError.SaveFailed)
                            }
                        }
                        MissingPublication -> failed(ImageFailure.STAGING, storage = StorageError.SaveFailed)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            when {
                error is StorageException && error.error == StorageError.Cancelled -> failed(ImageFailure.CANCELLED)
                TransferSpacePolicy.isNoSpace(error) -> failed(ImageFailure.STAGING, storage = StorageError.InsufficientSpace)
                error is StorageException -> failed(ImageFailure.STAGING, storage = error.error)
                else -> failed(ImageFailure.STAGING, storage = StorageError.SaveFailed)
            }
        }
        finally {
            // A staging directory that could not be removed is swept again before the next Save.
            job?.let { try { it.cleanup() } catch (_: Exception) { dirtied = true } }
        }
    }

    private fun deleteContents(root: File) {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        // Default walkFileTree does not follow links; a link is removed, never its target.
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                if (dir != path) Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_SWEEP_ATTEMPTS = 3
    }
}

/** Own pending image rows in Download/Nezzel. AV never writes image MIME types, so its journalled rows are untouched. */
fun ownPendingImages(resolver: ContentResolver, ownerPackage: String): List<Uri> {
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val selection = "is_pending = ? AND owner_package_name = ? AND relative_path = ? AND mime_type LIKE ?"
    val args = arrayOf("1", ownerPackage, "Download/Nezzel/", "image/%")
    val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.MIME_TYPE)
    val cursor = if (Build.VERSION.SDK_INT >= 30) resolver.query(collection, columns, Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }, null) else @Suppress("DEPRECATION") resolver.query(MediaStore.setIncludePending(collection), columns, selection, args, null)
    return cursor?.use { rows ->
        buildList {
            while (rows.moveToNext()) {
                if (rows.getString(1)?.startsWith("image/") != true) continue
                add(ContentUris.withAppendedId(collection, rows.getLong(0)))
            }
        }
    }.orEmpty()
}
