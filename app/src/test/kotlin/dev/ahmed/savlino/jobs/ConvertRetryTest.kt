package dev.ahmed.savlino.jobs

import android.content.ContentValues
import android.content.Context
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
