package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import android.net.Uri
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JobInfoJsonTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun command(id: String): DownloadCommand {
        val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
        val format = Format("18", "mp4", true, true)
        return (DownloadCommand.confirm(id, Inspection(source, "version", listOf(format)), source,
            "18", "title") as ConfirmResult.Confirmed).command
    }
    private fun cached(id: String) = File(app.noBackupFilesDir, "ytdlp-info/$id.json").apply {
        parentFile!!.mkdirs(); writeText("{\"url\":\"https://example.org/signed?token=private\"}")
    }
    @Test fun failureAndCancellationDeletePrivateInfoImmediately() {
        JobStore(app, null).use { store ->
            for (id in listOf("failed", "cancelled", "queued-cancel")) {
                store.enqueue(command(id))
                val file = cached(id)
                when (id) {
                    "failed" -> assertTrue(store.fail(id, DownloadError.Network))
                    "cancelled" -> assertTrue(store.cancel(id))
                    else -> assertTrue(store.cancelEnqueued(id))
                }
                assertFalse("terminal $id retains signed URLs", file.exists())
            }
        }
    }
    @Test fun retryDiscardsEvenLeftoverInfoFromFailedCleanup() {
        JobStore(app, null).use { store ->
            store.enqueue(command("retry")); store.fail("retry", DownloadError.Network)
            val file = cached("retry")
            assertTrue(store.retry("retry"))
            assertFalse(file.exists())
        }
    }
    @Test fun completedAndAbandonedPublicationsDeleteInfo() {
        JobStore(app, null).use { store ->
            for (id in listOf("complete", "abandon", "publication-cancel")) {
                store.enqueue(command(id)); store.startTransfer(id); store.markStaged(id)
                val uri = Uri.parse("content://media/external/downloads/42")
                store.recordPending(id, PendingPublication(uri, "clip.mp4"))
                val file = cached(id)
                if (id == "complete") assertTrue(store.complete(id, PublishedFile(uri, "clip.mp4")))
                else {
                    if (id == "publication-cancel") store.requestPublicationFailure(id, DownloadError.Cancelled)
                    assertTrue(store.publicationAbandoned(id))
                }
                assertFalse(file.exists())
            }
        }
    }
    @Test fun startupSweepsExpiredOrTerminalInfoButPreservesFreshQueuedJob() = kotlinx.coroutines.runBlocking {
        JobStore(app, null).use { store ->
            store.enqueue(command("fresh")); store.enqueue(command("terminal")); store.cancel("terminal")
            val fresh = cached("fresh")
            val terminal = cached("terminal") // Crash after terminal database write, before deletion.
            val expired = cached("expired").apply { setLastModified(System.currentTimeMillis() - 3_600_001) }
            val coordinator = DownloadCoordinator(app, engine = dev.ahmed.savlino.engine.FakeEngine(), store = store,
                workManager = { error("offline scheduling fixture") })
            coordinator.initialize()
            assertTrue(fresh.exists()); assertFalse(terminal.exists()); assertFalse(expired.exists())
        }
    }
    @Test fun cancellationDuringPersistenceCannotLeaveInfoBehind() {
        JobStore(app, null).use { store ->
            store.enqueue(command("racing"))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val cancelling = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val prepare = pool.submit {
                    store.prepareInfoJson("racing") {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        cached("racing")
                    }
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val cancel = pool.submit { cancelling.countDown(); store.cancelEnqueued("racing") }
                assertTrue(cancelling.await(5, java.util.concurrent.TimeUnit.SECONDS))
                release.countDown()
                prepare.get(5, java.util.concurrent.TimeUnit.SECONDS); cancel.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(JobStage.CANCELLED, store.get("racing")!!.stage)
                assertFalse(File(app.noBackupFilesDir, "ytdlp-info/racing.json").exists())
                store.prepareInfoJson("racing") { fail("terminal row must not write cache") }
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
    @Test fun confirmationPersistsBeforeSchedulingAndWorkerMeasuresOnlyFirstPositiveByte() = kotlinx.coroutines.runBlocking {
        org.robolectric.shadows.ShadowLog.clear()
        val confirmed = command("worker")
        lateinit var file: File
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.FakeEngine() {
            override fun prepareDownload(command: DownloadCommand) { file = cached(command.jobId) }
            override suspend fun download(command: DownloadCommand, directory: File,
                progress: (dev.ahmed.savlino.engine.EngineEvent) -> Unit): dev.ahmed.savlino.engine.TransferResult {
                assertTrue(file.exists())
                assertNotEquals(confirmed.inspectionId, command.inspectionId) // Restored journal command.
                progress(dev.ahmed.savlino.engine.EngineEvent.Progress(0))
                assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("NezzelTiming").isEmpty())
                progress(dev.ahmed.savlino.engine.EngineEvent.Progress(1))
                progress(dev.ahmed.savlino.engine.EngineEvent.Progress(2))
                return dev.ahmed.savlino.engine.TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
            }
        }
        JobStore(app, null).use { store ->
            val coordinator = DownloadCoordinator(app, engine = engine, store = store,
                space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                    dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE }), workManager = {
                    assertTrue("persist before scheduling", file.exists()); error("offline scheduling fixture")
                })
            dev.ahmed.savlino.ui.EngineTiming.startDownload("worker")
            assertTrue(coordinator.enqueue(confirmed))
            assertEquals(JobStage.FAILED, coordinator.execute("worker", {}, {})!!.stage)
            assertFalse(file.exists())
            val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("NezzelTiming")
            assertEquals(1, logs.size)
            assertTrue(logs.single().msg.matches(Regex("download_start [0-9]+ms")))
        }
    }
    @Test fun explicitRetryOfSchedulingFailureAlsoDiscardsInfo() = kotlinx.coroutines.runBlocking {
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.FakeEngine() {
            override fun prepareDownload(command: DownloadCommand) { cached(command.jobId) }
        }
        JobStore(app, null).use { store ->
            val coordinator = DownloadCoordinator(app, engine = engine, store = store,
                workManager = { error("offline scheduling fixture") })
            assertTrue(coordinator.enqueue(command("scheduled")))
            val file = File(app.noBackupFilesDir, "ytdlp-info/scheduled.json")
            assertTrue(file.exists())
            assertTrue(coordinator.retry("scheduled"))
            assertFalse(file.exists())
        }
    }

    @Test fun retryCannotRunWhenPrivateInfoCannotBeDiscarded() = kotlinx.coroutines.runBlocking {
        JobStore(app, null).use { store ->
            store.enqueue(command("blocked")); store.fail("blocked", DownloadError.Network)
            // A nonempty directory makes deletion fail on every platform, without permission-dependent tests.
            val blocked = File(app.noBackupFilesDir, "ytdlp-info/blocked.json").apply { mkdirs() }
            File(blocked, "leftover").writeText("private")
            assertFalse(store.retry("blocked"))
            assertEquals(JobStage.FAILED, store.get("blocked")!!.stage)
            val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.FakeEngine() {
                override fun prepareDownload(command: DownloadCommand) {
                    val path = File(app.noBackupFilesDir, "ytdlp-info/${command.jobId}.json").apply { mkdirs() }
                    File(path, "leftover").writeText("private")
                }
            }
            var schedules = 0
            val coordinator = DownloadCoordinator(app, engine = engine, store = store,
                workManager = { schedules++; error("offline scheduling fixture") })
            assertTrue(coordinator.enqueue(command("blocked-scheduling")))
            val beforeRetry = schedules
            assertFalse(coordinator.retry("blocked-scheduling"))
            assertEquals(beforeRetry, schedules)
        }
    }

}
