package dev.ahmed.savlino.jobs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import dev.ahmed.savlino.storage.*
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A failed MP3 conversion keeps the downloaded source; Retry converts it again instead of downloading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ConvertRetryTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun mp3(id: String): DownloadCommand {
        val source = LinkRevision(LinkInput.parse("https://example.com/song")!!, 3)
        val format = Format("mp3-192", "mp3", false, true, size = Size.Estimated(4000), sourceFormatId = "140",
            conversion = AudioConversion.MP3_192, sourceContainer = "m4a")
        return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source,
            format.id, "song") as ConfirmResult.Confirmed).command
    }

    private fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                object : Worker(context, params) { override fun doWork() = Result.success() }
        }).build())

    @Test fun failedConversionKeepsTheSourceAndRetryConvertsWithoutDownloading() = runBlocking {
        workManager()
        val store = JobStore(app)
        val id = "convert-retry"
        val staging = File(app.filesDir, "downloads/$id")
        val source = File(staging, "output.m4a")
        var downloads = 0
        var conversions = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = "engine-42"
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                downloads++
                File(directory, "output.m4a").writeText("downloaded audio")
                progress(EngineEvent.Merging)
                return TransferResult.Failure(DownloadError.ConvertFailed)
            }
            override suspend fun convertKept(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult? {
                if (!File(directory, "output.m4a").isFile) return null
                conversions++
                assertEquals("downloaded audio", File(directory, "output.m4a").readText())
                return TransferResult.Failure(DownloadError.ConvertFailed)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE }))
        try {
            assertTrue(store.enqueue(mp3(id)))
            coordinator.execute(id, {}, {})
            store.get(id)!!.let {
                assertEquals(JobStage.FAILED, it.stage)
                assertEquals(DownloadError.ConvertFailed, it.error)
                assertTrue(it.retryable)
                assertTrue(JobStore.keepsPartial(it))
            }
            // The effect: the finished source is still on disk after the run's own recovery.reconcile and cleanup.
            assertEquals("downloaded audio", source.readText())

            // Retry: only the conversion runs again.
            assertTrue(store.retry(id))
            assertTrue(source.isFile)
            coordinator.execute(id, {}, {})
            assertEquals(1, downloads)
            assertEquals(1, conversions)
            // A kept source that fails again is dropped: the next Retry downloads in full.
            assertEquals(DownloadError.ConvertFailed, store.get(id)?.error)
            assertFalse(source.exists())
            assertTrue(store.retry(id))
            coordinator.execute(id, {}, {})
            assertEquals(2, downloads)
            assertEquals(1, conversions)
        } finally { store.close(); staging.deleteRecursively() }
    }

    @Test fun exceptionAfterConversionStartsKeepsSourceAndRetrySkipsDownload() = runBlocking {
        workManager()
        val store = JobStore(app)
        val id = "encode-throws"
        val staging = File(app.filesDir, "downloads/$id")
        var downloads = 0
        var conversions = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = "engine-42"
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                downloads++
                File(directory, "output.m4a").writeText("downloaded audio")
                progress(EngineEvent.Merging)
                throw IllegalStateException("local conversion setup failed")
            }
            override suspend fun convertKept(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult? {
                if (!File(directory, "output.m4a").isFile) return null
                conversions++
                assertEquals("downloaded audio", File(directory, "output.m4a").readText())
                return TransferResult.Failure(DownloadError.ConvertFailed)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE }))
        try {
            assertTrue(store.enqueue(mp3(id)))
            coordinator.execute(id, {}, {})
            assertEquals(DownloadError.PostTransferFailed, store.get(id)?.error)
            assertEquals("downloaded audio", File(staging, "output.m4a").readText())
            assertTrue(store.retry(id))
            coordinator.execute(id, {}, {})
            assertEquals(1, downloads)
            assertEquals(1, conversions)
        } finally { store.close(); staging.deleteRecursively() }
    }

    @Test fun postTransferFailureKeepsFinishedOutputAndRetrySkipsDownload() = runBlocking {
        postTransferRetry(publishThrows = false)
    }

    @Test fun publishExceptionAfterSuccessKeepsOutputAndRetrySkipsDownload() = runBlocking {
        postTransferRetry(publishThrows = true)
    }

    @Test fun copyExceptionAfterPendingInsertKeepsOutputAndRetrySkipsDownload() = runBlocking {
        postTransferRetry(publishThrows = true, copyThrows = true)
    }

    private suspend fun postTransferRetry(publishThrows: Boolean, copyThrows: Boolean = false, failKept: Boolean = false, commitFails: Boolean = false) {
        workManager()
        val store = JobStore(app)
        val id = if (failKept) "bounded-$publishThrows-$commitFails" else if (copyThrows) "copy-retry" else if (publishThrows) "publish-retry" else "post-transfer-retry"
        val staging = File(app.filesDir, "downloads/$id")
        val output = File(staging, "output.mp3")
        var downloads = 0
        var conversions = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = "engine-42"
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                downloads++
                File(directory, "output.mp3").writeText("finished mp3")
                File(directory, "result-path.txt").writeText(output.canonicalPath + "\n")
                // A long live download can leave a fragment behind even after encoding.
                File(directory, "output.m4a.part-Frag184.part").writeText("fragment")
                return if (publishThrows) TransferResult.Success(StagedOutput.validate(directory, listOf(output)))
                    else TransferResult.Failure(DownloadError.PostTransferFailed,
                        reason = ExecutionFailureReason.STORAGE_STAGING)
            }
            override suspend fun convertKept(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult? {
                if (!File(directory, "output.mp3").isFile) return null
                conversions++
                assertEquals("finished mp3", File(directory, "output.mp3").readText())
                if (failKept && !publishThrows) return TransferResult.Failure(DownloadError.PostTransferFailed,
                    reason = ExecutionFailureReason.STORAGE_STAGING)
                return TransferResult.Success(StagedOutput.validate(directory, listOf(output)))
            }
        }
        val uri = Uri.parse("content://media/external/downloads/732")
        val saved = File(app.filesDir, "$id-saved.mp3")
        var insertions = 0
        var committed = false
        val publisher = object : Publisher {
            override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
                insertions++
                if (publishThrows && !copyThrows && !commitFails && (insertions == 1 || failKept && insertions == 2)) throw StorageException(StorageError.SaveFailed, java.io.IOException("provider unavailable"))
                return PendingPublication(uri, name)
            }
            override fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal) {
                cancellation.throwIfCanceled()
                if (copyThrows && insertions == 1) {
                    saved.writeText("partial copy")
                    throw StorageException(StorageError.CopySizeMismatch, java.io.IOException("copy unavailable"))
                }
                source.copyTo(saved, overwrite = true)
            }
            override fun commit(pending: PendingPublication): PublicationResult {
                if (commitFails && insertions <= 2) return CommitFailed(pending, StorageError.SaveFailed)
                committed = true
                return PublishedFile(uri, pending.displayName)
            }
            override fun recover(uri: Uri): RecoveryResult = if (committed) PublishedFile(uri, "song.mp3") else MissingPublication
            override fun abandon(uri: Uri): RecoveryResult = recover(uri)
            override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String,
                mimeType: String, cancellation: CancellationSignal): PublicationResult = error("Journalled publication required")
        }
        val coordinator = DownloadCoordinator(app, engine, store, publisher = publisher,
            space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE }))
        try {
            assertTrue(store.enqueue(mp3(id)))
            coordinator.execute(id, {}, {})
            assertEquals(DownloadError.PostTransferFailed, store.get(id)?.error)
            assertTrue(store.get(id)!!.retryable)
            assertNull(store.get(id)!!.pendingUri) // Publisher confirmed absence before Retry.
            assertTrue(JobStore.keepsPartial(store.get(id)!!))
            assertEquals("finished mp3", output.readText())
            assertTrue(store.retry(id))
            val beforeRetry = downloads
            coordinator.execute(id, {}, {})
            assertEquals(0, downloads - beforeRetry)
            assertEquals(1, downloads)
            assertEquals(1, conversions)
            if (failKept) {
                assertEquals(DownloadError.PostTransferFailed, store.get(id)?.error)
                assertFalse(output.exists()) // The one local retry failed; discard its bytes.
                assertTrue(store.retry(id))
                coordinator.execute(id, {}, {})
                assertEquals(2, downloads) // Second Retry transfers again, rather than looping locally.
                assertEquals(1, conversions)
            } else {
                assertEquals(JobStage.COMPLETED, store.get(id)?.stage)
                assertEquals("finished mp3", saved.readText())
            }
        } finally { store.close(); staging.deleteRecursively(); saved.delete() }
    }

    @Test fun repeatedPostTransferFailureFromKeptBytesDropsThemBeforeSecondRetry() = runBlocking {
        postTransferRetry(publishThrows = false, failKept = true)
    }

    @Test fun repeatedPublicationFailureFromKeptBytesDropsThemBeforeSecondRetry() = runBlocking {
        postTransferRetry(publishThrows = true, failKept = true)
    }

    @Test fun repeatedAcknowledgedCommitFailureFromKeptBytesDropsThemBeforeSecondRetry() = runBlocking {
        postTransferRetry(publishThrows = true, failKept = true, commitFails = true)
    }

    @Test fun storedConvertFailedReadsBackAndAnUnknownStoredErrorReadsAsFailed() {
        JobStore(app).use { store ->
            assertTrue(store.enqueue(mp3("stored")))
            assertTrue(store.fail("stored", DownloadError.ConvertFailed))
            assertTrue(store.enqueue(mp3("future")))
            assertTrue(store.fail("future", DownloadError.Failed))
            // A newer build's value, or an older row: the reader never throws.
            store.writableDatabase.update("jobs", ContentValues().apply { put("error", "SomeFutureError") },
                "job_id = ?", arrayOf("future"))
        }
        JobStore(app).use { store ->
            assertEquals(DownloadError.ConvertFailed, store.get("stored")?.error)
            assertTrue(store.get("stored")!!.retryable)
            assertEquals(DownloadError.Failed, store.get("future")?.error)
            assertTrue(store.all().size >= 2)
        }
    }
}
