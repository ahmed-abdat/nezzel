@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Test the real adapter without widening its production API.

package dev.ahmed.savlino.jobs

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.storage.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real adapter watchdog/cancellation, coordinator, SQLite, files and worker result; only native execution is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], manifest = Config.NONE)
class EngineResumeIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun slowFirstByteOutlastsStallWindowThenRealWatchdogRequeuesPartial() = runBlocking {
        val release = CountDownLatch(1)
        Fixture("slow-first", EngineLimits(stallMs = 100, firstByteMs = 3000)).use { f ->
            f.runtime.transfer = { _, id, line ->
                check(release.await(5, TimeUnit.SECONDS))
                f.part.writeText("keep")
                line("nezzel:opaque-choice:4:16777216:NA")
                f.runtime.hang(id)
            }
            f.enqueue()
            val running = async(Dispatchers.Default) { f.worker().doWork() }
            try {
                withTimeout(5000) { f.runtime.entered.receive() }
                assertNull(withTimeoutOrNull(350) { running.await() })
                assertEquals(0, f.runtime.destroys.get())
                assertEquals(JobStage.TRANSFERRING, f.store.get(f.id)!!.stage)
                release.countDown()
                assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { running.await() })
                f.assertQueued(true)
                assertEquals("keep", f.part.readText())
                assertEquals(1, f.store.get(f.id)!!.stallRestarts)
                assertEquals(0, f.store.get(f.id)!!.transportResumes)
                assertTrue(f.runtime.destroys.get() > 0)
                assertTrue(f.work().any { it.state == WorkInfo.State.BLOCKED })
            } finally { release.countDown(); running.cancelAndJoin() }
        }
    }

    @Test fun firstByteTimeoutResumesOnceWithAndWithoutPartial() = runBlocking {
        for (partial in listOf(false, true)) {
            Fixture("no-first-$partial", EngineLimits(stallMs = 50, firstByteMs = 150)).use { f ->
                f.enqueue(partial)
                assertEquals(ListenableWorker.Result.retry(), withTimeout(5000) { f.worker().doWork() })
                f.assertQueued(partial)
                assertEquals(1, f.store.get(f.id)!!.transportResumes)
                assertEquals(0, f.store.get(f.id)!!.stallRestarts)
                if (partial) assertEquals("keep", f.part.readText()) else assertFalse(f.part.exists())
                assertEquals(ListenableWorker.Result.failure(), withTimeout(5000) { f.worker().doWork() })
                assertEquals(JobStage.FAILED, f.store.get(f.id)!!.stage)
                assertFalse(f.part.parentFile!!.exists())
                assertEquals(1, f.phases.count { it == "FAILED" })
            }
        }
    }

    @Test fun twoConnectivityStopsPreservePartialAndDoNotConsumeEngineBudget() = runBlocking {
        Fixture("constraint", EngineLimits(stallMs = 100, firstByteMs = 5000)).use { f ->
            f.enqueue(partial = true)
            assertTrue(f.store.requeueAutomatic(f.id, DownloadFailureCause.TRANSPORT))
            repeat(2) {
                val worker = f.worker()
                val running = launch(Dispatchers.Default) { worker.doWork() }
                try {
                    withTimeout(5000) { f.runtime.entered.receive() }
                    worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
                } finally { running.cancelAndJoin() }
                f.assertQueued(true)
                assertEquals("keep", f.part.readText())
                assertEquals(1, f.store.get(f.id)!!.transportResumes)
                assertEquals(0, f.store.get(f.id)!!.stallRestarts)
                assertTrue(f.work().all { it.state == WorkInfo.State.ENQUEUED })
            }
            assertTrue(f.runtime.destroys.get() >= 2)
        }
    }

    @Test fun coldVersionProbeTimeoutRequeuesTheAdoptedPartial() = runBlocking {
        Fixture("cold-probe", EngineLimits(selfTestMs = 100), cold = true).use { f ->
            f.runtime.hangVersion = true
            f.enqueue(partial = true)
            assertEquals(ListenableWorker.Result.retry(), withTimeout(5000) { f.worker().doWork() })
            f.assertQueued(true)
            assertEquals("keep", f.part.readText())
            assertEquals(1, f.store.get(f.id)!!.transportResumes)
            assertEquals(0, f.runtime.transfers.get())
            assertTrue(f.runtime.destroys.get() >= 2) // Discovery and the download's required probe both timed out.
        }
    }

    @Test fun maintenanceAdmissionTimeoutRequeuesTheAdoptedPartial() = runBlocking {
        Fixture("maintenance", EngineLimits(maintenanceWaitMs = 100, updateMs = 5000)).use { f ->
            f.enqueue(partial = true)
            val update = async(Dispatchers.Default) { f.engine.updateYtdlp() }
            try {
                assertTrue(withContext(Dispatchers.IO) { f.runtime.updating.await(5, TimeUnit.SECONDS) })
                assertEquals(ListenableWorker.Result.retry(), withTimeout(5000) { f.worker().doWork() })
                f.assertQueued(true)
                assertEquals("keep", f.part.readText())
                assertEquals(1, f.store.get(f.id)!!.transportResumes)
                assertEquals(0, f.runtime.transfers.get())
            } finally {
                f.runtime.releaseUpdate.countDown()
                withTimeout(5000) { update.await() }
            }
        }
    }

    @Test fun kilobyteTricklesThroughRealWatchdogExhaustThreeStallRestarts() = runBlocking {
        Fixture("trickle", EngineLimits(stallMs = 100, firstByteMs = 3000)).use { f ->
            f.runtime.transfer = { _, id, line ->
                f.part.appendBytes(ByteArray(4096))
                line("nezzel:opaque-choice:${f.part.length()}:16777216:NA")
                f.runtime.hang(id)
            }
            f.enqueue()
            repeat(3) { index ->
                assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { f.worker().doWork() })
                f.assertQueued(true)
                assertEquals((index + 1) * 4096L, f.part.length())
                assertEquals(index + 1, f.store.get(f.id)!!.stallRestarts)
            }
            assertEquals(ListenableWorker.Result.failure(), withTimeout(5000) { f.worker().doWork() })
            assertEquals(JobStage.FAILED, f.store.get(f.id)!!.stage)
            assertFalse(f.part.parentFile!!.exists())
            assertEquals(1, f.phases.count { it == "FAILED" })
            assertEquals(4, f.runtime.destroys.get())
            assertEquals(4, f.work().size)
        }
    }

    @Test fun fragmentHttpRetryWarningsThenWatchdogNeverAutomaticallyRequeue() = runBlocking {
        for (status in listOf(429, 503)) {
            Fixture("http-backoff-$status", EngineLimits(stallMs = 100, firstByteMs = 3000)).use { f ->
                f.runtime.transfer = { _, id, line ->
                    f.part.writeText("partial")
                    line("nezzel:opaque-choice:7:16777216:NA")
                    repeat(3) {
                        line("[download] Got error: HTTP Error $status: retrying https://example.com/private. Retrying fragment 2 (1/10)...")
                    }
                    f.runtime.hang(id)
                }
                f.enqueue()
                assertEquals(ListenableWorker.Result.failure(), withTimeout(5000) { f.worker().doWork() })
                val row = f.store.get(f.id)!!
                assertEquals(JobStage.FAILED, row.stage)
                assertEquals(DownloadError.Failed, row.error)
                assertEquals(0, row.transportResumes)
                assertEquals(0, row.stallRestarts)
                assertEquals(1, f.runtime.transfers.get())
                assertEquals(1, f.work().size)
                assertFalse(f.part.parentFile!!.exists())
            }
        }
    }

    @Test fun actualTaggingAndTitleRewriteCallbacksRefusePause() = runBlocking {
        for (phase in listOf("[Metadata]", "[EmbedThumbnail]", "[FixupM4a]", "title")) {
            val suffix = if (phase == "title") "mp4" else "m4a"
            val format = Format("opaque-choice", suffix, phase == "title", true, size = Size.Exact(1000))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Fixture("post-${phase.filter(Char::isLetter)}", EngineLimits(), format = format).use { f ->
                val output = File(f.part.parentFile, "output.$suffix")
                f.runtime.transfer = { _, _, line ->
                    output.writeBytes(ByteArray(1000))
                    line("nezzel:opaque-choice:1000:1000:NA")
                    if (phase != "title") {
                        line("$phase Rewriting downloaded audio")
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    File(output.parentFile, "result-path.txt").writeText(output.path)
                    ""
                }
                f.runtime.rewrite = { _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                f.enqueue()
                val running = async(Dispatchers.Default) { f.worker().doWork() }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    assertFalse(f.coordinator.pause(f.id))
                    assertEquals(JobStage.TRANSFERRING, f.store.get(f.id)!!.stage)
                    assertEquals(1000L, output.length())
                    release.countDown()
                    assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { running.await() })
                    assertEquals(JobStage.COMPLETED, f.store.get(f.id)!!.stage)
                    assertEquals(1, f.provider.rows.size)
                    assertEquals(1000L, f.provider.file.length())
                    assertEquals(1, f.runtime.transfers.get())
                } finally { release.countDown(); running.cancelAndJoin() }
            }
        }
    }

    @Test fun quotaStopCancelsRestrictedWorkSoExplicitRetryCanSchedule() = runBlocking {
        Fixture("quota-stop", EngineLimits(firstByteMs = 5000)).use { f ->
            f.enqueue(partial = true)
            val worker = f.worker()
            val running = launch(Dispatchers.Default) { worker.doWork() }
            try {
                withTimeout(5000) { f.runtime.entered.receive() }
                assertEquals(WorkInfo.State.ENQUEUED, f.work().single().state)
                worker.stop(WorkInfo.STOP_REASON_QUOTA)
            } finally { running.cancelAndJoin() }
            assertEquals(JobStage.FAILED, f.store.get(f.id)!!.stage)
            assertEquals(DownloadError.StoppedByBattery, f.store.get(f.id)!!.error)
            assertTrue(withTimeout(6000) { f.coordinator.retry(f.id) })
            assertEquals(JobStage.ENQUEUED, f.store.get(f.id)!!.stage)
            assertEquals(1, f.work().count { !it.state.isFinished })
            val work = f.work().single { !it.state.isFinished }
            WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(work.id)
            assertEquals(1, f.runtime.transfers.get()) // Test scheduler runs its placeholder, never a second native writer.
        }
    }

    /** The shared [command] helper's "exact/source" ID is FakeEngine-only; the real adapter rejects it before execute. */
    private fun engineCommand(id: String, selected: Format? = null): DownloadCommand {
        val source = LinkRevision(LinkInput.parse("https://example.com/clip?token=private")!!, 7)
        val format = selected ?: Format("opaque-choice", "mp4", true, true, height = 720, size = Size.Exact(16L * 1024 * 1024),
            sourceFormatId = "exact-source")
        return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source,
            format.id, "clip") as ConfirmResult.Confirmed).command
    }

    private inner class Fixture(val id: String, limits: EngineLimits, cold: Boolean = false,
        val format: Format? = null) : AutoCloseable {
        val runtime = Runtime(cold)
        // Robolectric shares process-wide engine state per SDK sandbox; a share stranded by another class would
        // block this fixture's exclusive updater.
        val engine = YoutubedlEngine(runtime, limits, YtdlpInfoCache(app.noBackupFilesDir), native = NativeState())
        val store = JobStore(app, null)
        val provider = TestDownloadsProvider(File(app.cacheDir, "$id-published"), app.packageName)
        val part = File(app.filesDir, "downloads/$id/output.mp4.part")
        val phases = mutableListOf<String>()
        val coordinator: DownloadCoordinator
        init {
            WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                        object : Worker(context, params) { override fun doWork() = Result.success() }
                }).build())
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE })
            coordinator = DownloadCoordinator(app, engine, store,
                MediaStorePublisher(app.contentResolver, space, app.packageName) {}, space = space)
            (app as IsolatedTestApplication).coordinatorOverride = coordinator
        }
        fun enqueue(partial: Boolean = false) {
            assertTrue(store.enqueue(engineCommand(id, format)))
            if (partial) {
                store.startTransfer(id, "${dev.ahmed.savlino.BuildConfig.VERSION_CODE}:engine-42:${YtdlpCommand.ARGV_VERSION}")
                part.parentFile!!.mkdirs(); part.writeText("keep")
                store.requeue(id, true)
            }
            // A real confirmed info-cache file keeps the adapter on U2a's partial adoption path.
            File(app.noBackupFilesDir, "ytdlp-info/$id.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        }
        fun worker() = TestListenableWorkerBuilder<DownloadWorker>(app)
            .setInputData(workDataOf(DownloadWorker.JOB_ID to id))
            .setWorkerFactory(DownloadWorker.Factory(coordinator))
            .setProgressUpdater { _, _, data ->
                phases += data.getString("phase")!!
                com.google.common.util.concurrent.SettableFuture.create<Void>().apply { set(null) }
            }.build()
        fun work() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(id).get()
        fun assertQueued(partial: Boolean) {
            val row = store.get(id)!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertEquals(partial, row.hasPartial)
            assertNotNull(row.sourceUrl); assertNull(row.error)
            assertFalse(phases.contains("FAILED"))
        }
        override fun close() { runtime.releaseAll(); store.close() }
    }

    private class Runtime(cold: Boolean) : YtdlpRuntime {
        override val initialization = EngineInitialization().apply { if (!cold) installedVersion = "engine-42" }
        private val stopped = ConcurrentHashMap<String, CountDownLatch>()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val updating = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val destroys = AtomicInteger()
        val transfers = AtomicInteger()
        var hangVersion = false
        var rewrite: (File, String) -> Unit = { _, _ -> }
        var transfer: (List<String>, String, (String) -> Unit) -> String = { _, id, _ -> hang(id) }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return if (hangVersion) hang(processId) else "engine-42"
            transfers.incrementAndGet(); entered.trySend(Unit)
            return transfer(args, processId, line)
        }
        fun hang(id: String): String {
            check(stopped.computeIfAbsent(id) { CountDownLatch(1) }.await(5, TimeUnit.SECONDS))
            error("native process stopped")
        }
        override fun rewriteTitle(file: File, title: String, processId: String) = rewrite(file, title)
        override fun destroy(processId: String): Boolean {
            destroys.incrementAndGet(); stopped.computeIfAbsent(processId) { CountDownLatch(1) }.countDown()
            return true
        }
        override fun update() { updating.countDown(); check(releaseUpdate.await(5, TimeUnit.SECONDS)) }
        fun releaseAll() { releaseUpdate.countDown(); stopped.values.forEach { it.countDown() }; entered.close() }
    }
}
