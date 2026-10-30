package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.engine.EngineEvent
import dev.ahmed.savlino.engine.EngineUpdateResult
import dev.ahmed.savlino.engine.TransferResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import android.content.pm.ProviderInfo
import dev.ahmed.savlino.media.MediaProbe
import dev.ahmed.savlino.storage.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadCoordinatorTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun enqueueAndRetryPersistVisibleQueueWhileAnotherTransferIsBlocked() = runBlocking {
        heldTransfer().use { f ->
            assertTrue(f.coordinator.enqueue(command("running")))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            try {
                assertNotNull("Fake transfer must start before the second enqueue",
                    withTimeoutOrNull(5000) { f.entered.await(); true })
                assertTrue(withTimeout(5000) { f.coordinator.enqueue(command("queued")) })
                assertFalse(running.isCompleted)
                assertEquals(JobStage.TRANSFERRING, f.store.get("running")!!.stage)
                assertEquals(JobStage.ENQUEUED, f.store.get("queued")!!.stage)
                val queued = f.coordinator.progress.value.single { it.jobId == "queued" }
                assertEquals(DownloadPhase.PREPARING, queued.phase)
                assertTrue(queued.waiting)
                assertFalse(queued.awaitingSchedule)
                // The running job leads; the queue follows in run order.
                assertEquals(listOf("running", "queued"),
                    dev.ahmed.savlino.downloads.visibleJobs(f.coordinator.progress.value).map { it.jobId })
                val scheduled = downloadRuns(app, "queued").single().id
                assertFalse(withTimeout(5000) { f.coordinator.retry("queued") })
                assertEquals(scheduled, downloadRuns(app, "queued").single().id)
                f.beforeSchedule = { error("Scheduler unavailable") }
                assertTrue(withTimeout(5000) { f.coordinator.enqueue(command("retry-queued")) })
                assertTrue(f.coordinator.progress.value.single { it.jobId == "retry-queued" }.awaitingSchedule)
                f.beforeSchedule = {}
                assertTrue(withTimeout(5000) { f.coordinator.retry("retry-queued") })
                val retried = f.coordinator.progress.value.single { it.jobId == "retry-queued" }
                assertEquals(DownloadPhase.PREPARING, retried.phase)
                assertTrue(retried.waiting)
                assertFalse(retried.awaitingSchedule)
                assertEquals(1, downloadRuns(app, "retry-queued").size)
                assertEquals(JobStage.ENQUEUED, f.store.get("queued")!!.stage)
                assertFalse(running.isCompleted)
            } finally { f.release.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun transfersStaySerialAndQueuedCancellationCannotStartOrCancelTheOtherJob() = runBlocking {
        heldTransfer(limit = 1).use { f ->
            f.coordinator.enqueue(command("running")); f.coordinator.enqueue(command("queued"))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            var queued: Deferred<JobRecord?>? = null
            try {
                withTimeout(5000) { f.entered.await() }
                queued = async(Dispatchers.IO) { f.coordinator.execute("queued", {}, {}) }
                assertNull("Second worker must wait for the transfer slot", withTimeoutOrNull(250) { queued.await() })
                assertEquals(JobStage.ENQUEUED, f.store.get("queued")!!.stage)
                assertTrue(f.coordinator.progress.value.single { it.jobId == "queued" }.waiting)
                withTimeout(5000) { f.coordinator.cancel("queued") }
                assertEquals(JobStage.CANCELLED, f.store.get("queued")!!.stage)
                assertNull(f.store.get("queued")!!.sourceUrl)
                assertFalse(f.coordinator.progress.value.any { it.jobId == "queued" })
                assertEquals(JobStage.TRANSFERRING, f.store.get("running")!!.stage)
                assertFalse(running.isCompleted)
                f.release.complete(Unit)
                assertEquals(JobStage.FAILED, withTimeout(5000) { running.await() }!!.stage)
                assertEquals(JobStage.CANCELLED, withTimeout(5000) { queued.await() }!!.stage)
                assertEquals(listOf("running"), f.transferred.toList())
                assertEquals(DownloadError.Network, f.store.get("running")!!.error)
            } finally { f.release.complete(Unit); running.cancelAndJoin(); queued?.cancelAndJoin() }
        }
    }

    @Test fun maintenanceDefersThroughRunningAndQueuedJobsUntilQueueIsCancelled() = runBlocking {
        heldTransfer().use { f ->
            f.coordinator.enqueue(command("running")); f.coordinator.enqueue(command("queued"))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            try {
                withTimeout(5000) { f.entered.await() }
                assertFalse(withTimeout(5000) { f.coordinator.maintenance.updateNow() })
                assertEquals(EngineUpdateStatus.BUSY, f.coordinator.maintenance.state.value.status)
                assertEquals("engine-42", f.engine.installedVersion())
                f.release.complete(Unit)
                assertEquals(JobStage.FAILED, withTimeout(5000) { running.await() }!!.stage)
                assertFalse(f.coordinator.maintenance.updateNow())
                assertEquals(JobStage.ENQUEUED, f.store.get("queued")!!.stage)
                assertEquals("engine-42", f.engine.installedVersion())
                f.coordinator.cancel("queued")
                assertTrue(f.coordinator.maintenance.updateNow())
                assertEquals("updated", f.engine.installedVersion())
            } finally { f.release.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun pausedRowBlocksEngineMaintenanceAndStartupSchedulesOnlyQueuedRows() = runBlocking {
        heldTransfer().use { f ->
            f.store.enqueue(command("paused")); f.store.startTransfer("paused", "recipe")
            File(app.filesDir, "downloads/paused").apply { mkdirs(); resolve("output.mp4.part").writeText("paused") }
            f.store.pause("paused", true)
            f.store.enqueue(command("queued")); f.store.requeue("queued", true)
            File(app.filesDir, "downloads/queued").apply { mkdirs(); resolve("output.mp4.part").writeText("queued") }
            f.coordinator.initialize()
            assertEquals(JobStage.PAUSED, f.store.get("paused")!!.stage)
            assertTrue(f.coordinator.progress.value.single { it.jobId == "paused" }.paused)
            assertTrue(downloadRuns(app, "paused").isEmpty())
            // The network waits in the gate; the run itself has no constraint a lost network could stop.
            val chain = WorkManager.getInstance(app).getWorkInfosForUniqueWork("queued").get()
            assertEquals(2, chain.size)
            assertEquals(NetworkType.CONNECTED, chain.single { NetworkGateWorker::class.java.name in it.tags }.constraints.requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED, downloadRuns(app, "queued").single().constraints.requiredNetworkType)
            assertFalse("a queued row is not paused-only", f.coordinator.onlyPausedWaiting())
            assertFalse(f.coordinator.maintenance.updateNow(includePaused = true))
            f.coordinator.cancel("queued")
            assertFalse(f.coordinator.maintenance.updateNow())
            assertEquals(EngineUpdateStatus.BUSY, f.coordinator.maintenance.state.value.status)
            assertEquals("engine-42", f.engine.installedVersion())
            assertTrue(f.coordinator.onlyPausedWaiting())
            // Confirmed: paused bytes are given up (their resume recipe pins the old engine).
            assertTrue(f.coordinator.maintenance.updateNow(includePaused = true))
            assertEquals("updated", f.engine.installedVersion())
            assertEquals(JobStage.PAUSED, f.store.get("paused")!!.stage)
        }
    }

    @Test fun delayedRetrySchedulingFailureCannotOverwriteLiveOrFinishedProgress() = runBlocking {
        heldTransfer().use { f ->
            f.coordinator.enqueue(command("running"))
            f.beforeSchedule = { error("Scheduler unavailable") }
            f.coordinator.enqueue(command("queued"))
            assertTrue(f.coordinator.progress.value.single { it.jobId == "queued" }.awaitingSchedule)
            val scheduling = java.util.concurrent.CountDownLatch(1)
            val failSchedule = java.util.concurrent.CountDownLatch(1)
            val holdFirstSchedule = java.util.concurrent.atomic.AtomicBoolean(true)
            f.beforeSchedule = {
                if (holdFirstSchedule.compareAndSet(true, false)) {
                    scheduling.countDown()
                    check(failSchedule.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    error("Scheduler unavailable")
                }
            }
            val rescheduling = async(Dispatchers.IO) { f.coordinator.retry("queued") }
            var running: Deferred<JobRecord?>? = null
            var queued: Deferred<JobRecord?>? = null
            try {
                assertTrue(scheduling.await(5, java.util.concurrent.TimeUnit.SECONDS))
                running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
                withTimeout(5000) { f.entered.await() }
                // Retrying the active row is refused and must not reset its measured progress.
                assertFalse(withTimeout(5000) { f.coordinator.retry("running") })
                val live = f.coordinator.progress.value.single { it.jobId == "running" }
                assertEquals(DownloadPhase.DOWNLOADING, live.phase)
                assertFalse(live.waiting)
                queued = async(Dispatchers.IO) { f.coordinator.execute("queued", {}, {}) }
                f.release.complete(Unit)
                withTimeout(5000) { running.await(); queued.await() }
                assertEquals(listOf("running", "queued"), f.transferred.toList())
                failSchedule.countDown()
                assertTrue(withTimeout(5000) { rescheduling.await() })
                val result = f.coordinator.progress.value.single { it.jobId == "queued" }
                assertEquals(JobStage.FAILED, f.store.get("queued")!!.stage)
                assertEquals(DownloadError.Network, result.error)
                assertFalse(result.waiting)
                assertFalse(result.awaitingSchedule)
            } finally {
                failSchedule.countDown(); f.release.complete(Unit)
                rescheduling.cancelAndJoin(); running?.cancelAndJoin(); queued?.cancelAndJoin()
            }
        }
    }

    @Test fun retryCannotResetPreparingWorkerWhileForegroundPromotionIsBlocked() = runBlocking {
        heldTransfer().use { f ->
            f.coordinator.enqueue(command("preparing"))
            val foreground = CompletableDeferred<Unit>()
            val promote = CompletableDeferred<Unit>()
            val running = async(Dispatchers.IO) {
                f.coordinator.execute("preparing", { foreground.complete(Unit); promote.await() }, {})
            }
            try {
                withTimeout(5000) { foreground.await() }
                assertEquals(JobStage.TRANSFERRING, f.store.get("preparing")!!.stage)
                assertFalse(withTimeout(5000) { f.coordinator.retry("preparing") })
                val preparing = f.coordinator.progress.value.single()
                assertEquals(DownloadPhase.PREPARING, preparing.phase)
                assertFalse(preparing.waiting)
                assertTrue(f.transferred.isEmpty())
                promote.complete(Unit)
                withTimeout(5000) { f.entered.await() }
                f.release.complete(Unit)
                assertEquals(JobStage.FAILED, withTimeout(5000) { running.await() }!!.stage)
            } finally { promote.complete(Unit); f.release.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun pauseAllPausesRunningAndQueuedAndCountsOnlyRowsThatChanged() = runBlocking {
        heldTransfer().use { f ->
            assertTrue(f.coordinator.enqueue(command("running")))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            try {
                withTimeout(5000) { f.entered.await() }
                for (id in listOf("queued", "failed")) assertTrue(f.coordinator.enqueue(command(id)))
                f.store.fail("failed", DownloadError.Network)
                // Snapshot taken at tap time: a failed row and a vanished ID are refused, not counted.
                assertEquals(2, f.coordinator.pauseAll(listOf("running", "queued", "failed", "gone")))
                assertEquals(JobStage.PAUSED, f.store.get("running")!!.stage)
                assertEquals(JobStage.PAUSED, f.store.get("queued")!!.stage)
                assertEquals(JobStage.FAILED, f.store.get("failed")!!.stage)
                assertEquals(0, f.coordinator.pauseAll(listOf("running", "queued")))
            } finally { f.release.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun resumeAllQueuesEveryPausedRowAndHeatStillAdmitsOneAtATime() = runBlocking {
        heldTransfer(limit = 2, hot = true).use { f ->
            val ids = listOf("one", "two", "three")
            for (id in ids) assertTrue(f.coordinator.enqueue(command(id)))
            assertEquals(3, f.coordinator.pauseAll(ids))
            assertEquals(3, f.coordinator.resumeAll(ids + "gone"))
            for (id in ids) assertEquals(JobStage.ENQUEUED, f.store.get(id)!!.stage)
            val runs = ids.map { id -> async(Dispatchers.IO) { f.coordinator.execute(id, {}, {}) } }
            try {
                withTimeout(5000) { f.entered.await() }
                delay(300)
                assertEquals("hot: one transfer at a time", 1, f.transferred.size)
                f.release.complete(Unit)
                runs.forEach { withTimeout(5000) { it.await() } }
                assertEquals(ids.toSet(), f.transferred.toSet())
            } finally { f.release.complete(Unit); runs.forEach { it.cancelAndJoin() } }
        }
    }

    @Test fun retryAllRequeuesOnlyRetryableFailures() = runBlocking {
        heldTransfer().use { f ->
            for (id in listOf("net-1", "net-2", "gone")) f.store.enqueue(command(id))
            f.store.fail("net-1", DownloadError.Network); f.store.fail("net-2", DownloadError.Network)
            f.store.fail("gone", DownloadError.Network, retryable = false)
            assertEquals(2, f.coordinator.retryAll(listOf("net-1", "gone", "net-2")))
            assertEquals(JobStage.ENQUEUED, f.store.get("net-1")!!.stage)
            assertEquals(JobStage.ENQUEUED, f.store.get("net-2")!!.stage)
            assertEquals(JobStage.FAILED, f.store.get("gone")!!.stage)
        }
    }

    @Test fun resumePublishesExpiredPausedRowAndDeletesItsPartial() = runBlocking {
        heldTransfer().use { f ->
            assertTrue(f.coordinator.enqueue(command("expired-pause")))
            assertTrue(f.coordinator.pause("expired-pause"))
            val staging = File(app.filesDir, "downloads/expired-pause").apply { mkdirs() }
            staging.resolve("output.mp4.part").writeText("partial")
            assertTrue(f.coordinator.progress.value.single().paused)
            f.store.writableDatabase.execSQL("UPDATE jobs SET created_at=? WHERE job_id=?",
                arrayOf<Any?>(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000, "expired-pause"))

            assertFalse(f.coordinator.resume("expired-pause"))
            val progress = f.coordinator.progress.value.single()
            assertEquals(DownloadPhase.FAILED, progress.phase)
            assertEquals(DownloadError.FormatExpired, progress.error)
            assertFalse(progress.paused)
            assertFalse(progress.retryable)
            assertFalse(staging.exists())
        }
    }

    @Test fun resumePublishesExpiryThatWinsWhileWaitingForPreviousWork() = runBlocking {
        heldTransfer().use { f ->
            assertTrue(f.coordinator.enqueue(command("expiry-race")))
            assertTrue(f.coordinator.pause("expiry-race"))
            f.beforeSchedule = {
                f.store.writableDatabase.execSQL("UPDATE jobs SET created_at=? WHERE job_id=?",
                    arrayOf<Any?>(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000, "expiry-race"))
            }
            assertFalse(f.coordinator.resume("expiry-race"))
            val progress = f.coordinator.progress.value.single()
            assertEquals(DownloadPhase.FAILED, progress.phase)
            assertEquals(DownloadError.FormatExpired, progress.error)
            assertFalse(progress.paused)
        }
    }

    @Test fun unrelatedJournalReadPublishesEveryPausedExpiryWithoutResume() = runBlocking {
        heldTransfer().use { f ->
            for (id in listOf("expired-one", "expired-two")) {
                assertTrue(f.coordinator.enqueue(command(id)))
                assertTrue(f.coordinator.pause(id))
            }
            f.store.writableDatabase.execSQL("UPDATE jobs SET created_at=?",
                arrayOf<Any?>(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000))

            assertNull(f.store.get("unrelated")) // Scrubbing is triggered by any journal read.
            assertEquals(2, f.coordinator.progress.value.size)
            for (progress in f.coordinator.progress.value) {
                assertEquals(DownloadPhase.FAILED, progress.phase)
                assertEquals(DownloadError.FormatExpired, progress.error)
                assertFalse(progress.paused)
            }
        }
    }

    @Test fun twoTransfersRunTogetherAndMaintenanceWaitsForBoth() = runBlocking {
        heldTransfer().use { f ->
            f.coordinator.enqueue(command("one")); f.coordinator.enqueue(command("two"))
            val one = async(Dispatchers.IO) { f.coordinator.execute("one", {}, {}) }
            val two = async(Dispatchers.IO) { f.coordinator.execute("two", {}, {}) }
            try {
                withTimeout(5000) { while (f.transferred.size < 2) yield() }
                assertEquals(setOf("one", "two"), f.transferred.toSet())
                assertEquals(JobStage.TRANSFERRING, f.store.get("one")!!.stage)
                assertEquals(JobStage.TRANSFERRING, f.store.get("two")!!.stage)
                assertFalse(f.coordinator.maintenance.updateNow())
                one.cancelAndJoin()
                assertFalse(two.isCompleted)
                assertFalse(f.coordinator.maintenance.updateNow())
                f.release.complete(Unit); two.await()
                assertTrue(f.coordinator.maintenance.updateNow())
            } finally { f.release.complete(Unit); one.cancelAndJoin(); two.cancelAndJoin() }
        }
    }

    @Test fun pausedAndExpiredWaitersNeverTransferAndExpiryDoesNotWaitForTheActiveJob() = runBlocking {
        heldTransfer(limit = 1).use { f ->
            for (id in listOf("running", "paused", "expired")) f.coordinator.enqueue(command(id))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            var paused: Deferred<JobRecord?>? = null; var expired: Deferred<JobRecord?>? = null
            try {
                withTimeout(5000) { f.entered.await() }
                paused = async(Dispatchers.IO) { f.coordinator.execute("paused", {}, {}) }
                expired = async(Dispatchers.IO) { f.coordinator.execute("expired", {}, {}) }
                assertNull(withTimeoutOrNull(250) { paused.await() })
                assertNull(withTimeoutOrNull(250) { expired.await() })
                assertTrue(f.coordinator.pause("paused"))
                assertEquals(JobStage.PAUSED, withTimeout(5000) { paused.await() }!!.stage)
                val expiredStaging = File(app.filesDir, "downloads/expired").apply {
                    mkdirs(); resolve("output.mp4.part").writeText("partial")
                }
                f.store.writableDatabase.execSQL("UPDATE jobs SET created_at=? WHERE job_id='expired'",
                    arrayOf<Any?>(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000))
                f.store.get("unrelated")
                val row = withTimeout(5000) { expired.await() }!!
                assertEquals(JobStage.FAILED, row.stage); assertEquals(DownloadError.FormatExpired, row.error)
                assertFalse(expiredStaging.exists())
                assertFalse(running.isCompleted)
                f.release.complete(Unit); running.await()
                assertEquals(listOf("running"), f.transferred.toList())
            } finally { f.release.complete(Unit); running.cancelAndJoin(); paused?.cancelAndJoin(); expired?.cancelAndJoin() }
        }
    }

    @Test fun admissionRechecksTheJournalEvenWithoutAnExplicitSlotRemoval() = runBlocking {
        heldTransfer(limit = 1).use { f ->
            for (id in listOf("running", "stale", "next")) f.coordinator.enqueue(command(id))
            val running = async(Dispatchers.IO) { f.coordinator.execute("running", {}, {}) }
            var stale: Deferred<JobRecord?>? = null; var next: Deferred<JobRecord?>? = null
            try {
                withTimeout(5000) { f.entered.await() }
                stale = async(Dispatchers.IO) { f.coordinator.execute("stale", {}, {}) }
                assertNull(withTimeoutOrNull(250) { stale.await() })
                assertTrue(f.store.pause("stale")) // Bypass coordinator removal: row recheck must protect admission.
                next = async(Dispatchers.IO) { f.coordinator.execute("next", {}, {}) }
                f.release.complete(Unit)
                running.await()
                assertEquals(JobStage.PAUSED, withTimeout(5000) { stale.await() }!!.stage)
                withTimeout(5000) { next.await() }
                assertEquals(listOf("running", "next"), f.transferred.toList())
            } finally { f.release.complete(Unit); running.cancelAndJoin(); stale?.cancelAndJoin(); next?.cancelAndJoin() }
        }
    }

    @Test fun anotherTransferCannotSpendLivePublicationHeadroomAndCancellationReleasesIt() = runBlocking {
        heldTransfer(freeBytes = 1536L * 1024 * 1024).use { f ->
            for (id in listOf("one", "rejected", "next"))
                f.coordinator.enqueue(command(id, Size.Exact(512L * 1024 * 1024)))
            val one = async(Dispatchers.IO) { f.coordinator.execute("one", {}, {}) }
            try {
                withTimeout(5000) { f.entered.await() }
                val rejected = withTimeout(5000) { f.coordinator.execute("rejected", {}, {}) }!!
                assertEquals(JobStage.FAILED, rejected.stage)
                assertEquals(DownloadError.InsufficientSpace, rejected.error)
                assertEquals(listOf("one"), f.transferred.toList())
                one.cancelAndJoin()
                f.release.complete(Unit)
                withTimeout(5000) { f.coordinator.execute("next", {}, {}) }
                assertEquals(listOf("one", "next"), f.transferred.toList())
                assertEquals(DownloadError.Network, f.store.get("next")!!.error)
            } finally { f.release.complete(Unit); one.cancelAndJoin() }
        }
    }

    private fun heldTransfer(limit: Int = 2, freeBytes: Long = Long.MAX_VALUE, hot: Boolean = false): HeldTransfer {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        return HeldTransfer(limit, freeBytes, hot)
    }
    private inner class HeldTransfer(limit: Int, freeBytes: Long, hot: Boolean = false) : AutoCloseable {
        val store = JobStore(app)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transferred = java.util.concurrent.ConcurrentLinkedQueue<String>()
        @Volatile var beforeSchedule: () -> Unit = {}
        val engine = object : EngineAdapter by FakeEngine() {
            private var version = "engine-42"
            override suspend fun installedVersion() = version
            override suspend fun updateYtdlp(): EngineUpdateResult {
                version = "updated"
                return EngineUpdateResult.Success("engine-42", version)
            }
            override suspend fun download(command: DownloadCommand, directory: File,
                progress: (EngineEvent) -> Unit): TransferResult {
                transferred += command.jobId
                progress(EngineEvent.Progress(1, Size.Exact(4)))
                entered.complete(Unit)
                release.await()
                return TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            parallelDownloads = kotlinx.coroutines.flow.MutableStateFlow(limit),
            thermalStatus = kotlinx.coroutines.flow.MutableStateFlow(
                if (hot) android.os.PowerManager.THERMAL_STATUS_SEVERE else android.os.PowerManager.THERMAL_STATUS_NONE),
            space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                dev.ahmed.savlino.storage.FreeSpace { freeBytes }), workManager = {
            beforeSchedule(); WorkManager.getInstance(app)
        })
        override fun close() = store.close()
    }

    @Test fun clearLinkHistoryAlsoEmptiesTheEnginesRecentResults() = runBlocking {
        var cleared = 0
        val engine = object : EngineAdapter by FakeEngine() { override fun clearRecentLinks() { cleared++ } }
        val store = JobStore(app)
        try {
            DownloadCoordinator(app, engine = engine, store = store).clearShareLinks()
            assertEquals(1, cleared)
        } finally { store.close() }
    }

    @Test fun secondConfirmationKeepsOneJournalAndOneUniqueWork() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        try {
            assertTrue(coordinator.enqueue(command("double")))
            assertFalse(coordinator.enqueue(command("double")))
            assertEquals(1, store.all().size)
            val work = downloadRuns(app, "double")
            assertEquals(1, work.size)
            val restored = store.get("double")!!.command()!!
            assertEquals("exact/source", restored.format.sourceFormatId)
            assertEquals("engine-42", restored.engineVersion)
        } finally { store.close() }
    }

    @Test fun scheduleFailureRemainsVisibleAndRetriesSameConfirmedJob() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        var schedulingBlocked = true
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store, workManager = {
            if (schedulingBlocked) error("Scheduler unavailable") else WorkManager.getInstance(app)
        })
        try {
            assertTrue(coordinator.enqueue(command("schedule-failure")))
            assertEquals(JobStage.ENQUEUED, store.get("schedule-failure")!!.stage)
            assertTrue(coordinator.progress.value.single().awaitingSchedule)
            assertEquals(DownloadPhase.FAILED, coordinator.progress.value.single().phase)
            schedulingBlocked = false
            assertTrue(coordinator.retry("schedule-failure"))
            assertEquals(DownloadPhase.PREPARING, coordinator.progress.value.single().phase)
            assertEquals(1, store.all().size)
            assertEquals(1, downloadRuns(app, "schedule-failure").size)
        } finally { store.close() }
    }

    @Test fun scheduleFailureEventKeepsTheRowEpochAndRetryStartsWithoutOldBytes() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        var schedulingBlocked = true
        try {
            store.enqueue(command("epoch-schedule")); store.startTransfer("epoch-schedule", "recipe")
            store.requeue("epoch-schedule", true)
            val staging = File(app.filesDir, "downloads/epoch-schedule").apply { mkdirs(); resolve("output.mp4.part").writeText("old") }
            val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store, workManager = {
                if (schedulingBlocked) error("Scheduler unavailable") else WorkManager.getInstance(app)
            })
            coordinator.initialize()
            val row = store.get("epoch-schedule")!!
            val event = coordinator.progress.value.single()
            assertTrue(event.awaitingSchedule); assertEquals(DownloadPhase.FAILED, event.phase)
            assertTrue(row.restartEpoch > 0); assertEquals(row.restartEpoch, event.restartEpoch)
            assertEquals(row.hasPartial, event.hasPartial)
            schedulingBlocked = false
            assertTrue(coordinator.retry("epoch-schedule"))
            // Retry extracts fresh signed URLs: older bytes never continue.
            assertFalse(staging.exists())
            assertEquals(JobStage.ENQUEUED, store.get("epoch-schedule")!!.stage)
            assertFalse(store.get("epoch-schedule")!!.hasPartial)
            assertEquals(1, downloadRuns(app, "epoch-schedule").size)
        } finally { store.close() }
    }

    @Test fun restoredQueueSchedulingFailureDoesNotEscapeStartupAndRemainsCancellable() = runBlocking {
        val store = JobStore(app)
        store.enqueue(command("restored"))
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store,
            workManager = { error("Scheduler unavailable") })
        try {
            coordinator.initialize()
            assertTrue(coordinator.progress.value.single().awaitingSchedule)
            coordinator.dismiss("restored")
            assertEquals(1, coordinator.progress.value.size)
            coordinator.cancel("restored")
            assertEquals(JobStage.CANCELLED, store.get("restored")!!.stage)
            assertNull(store.get("restored")!!.sourceUrl)
        } finally { store.close() }
    }

    @Test fun removingPrunedFailureClearsStaleProgressButNeverLiveRecord() = runBlocking {
        val store = JobStore(app)
        store.enqueue(command("pruned")); store.fail("pruned", DownloadError.Network)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store, workManager = { error("unavailable") })
        try {
            coordinator.initialize()
            assertEquals("pruned", coordinator.progress.value.single().jobId)
            assertTrue(store.remove("pruned"))
            coordinator.removeFailed("pruned")
            assertTrue(coordinator.progress.value.isEmpty())
            coordinator.enqueue(command("live"))
            coordinator.removeFailed("live")
            assertEquals(JobStage.ENQUEUED, store.get("live")!!.stage)
            assertEquals("live", coordinator.progress.value.single().jobId)
        } finally { store.close() }
    }
    @Test fun cancelledJobsAreEvictedAndFailedJobsRetainedOnStartup() = runBlocking {
        val store = JobStore(app)
        store.enqueue(command("cancelled")); store.cancel("cancelled")
        store.enqueue(command("failed")); store.fail("failed", DownloadError.Network)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        try {
            coordinator.initialize()
            assertEquals(listOf("failed"), coordinator.progress.value.map { it.jobId })
        } finally { store.close() }
    }
    @Test fun startupSqliteFailureIsVisibleAndDoesNotEscapeApplicationScope() = runBlocking {
        val store = JobStore(app)
        store.writableDatabase.execSQL("DROP TABLE jobs")
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.set(failure) }
        try {
            coordinator.start(scope).join()
            assertEquals(DownloadError.ExecutionRestricted, coordinator.error.value)
            assertNull(uncaught.get())
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            // Recovery remains retryable after the local database becomes usable.
            store.onCreate(store.writableDatabase)
            coordinator.start(scope).join()
            assertNull(coordinator.error.value)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            store.close()
        }
    }

    @Test fun schedulerCancelFailureRemainsVisibleAfterDurableQueuedCancellation() = runBlocking {
        val store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store,
            workManager = { error("Scheduler unavailable") })
        try {
            coordinator.initialize()
            store.enqueue(command("cancel-scheduler"))
            coordinator.cancel("cancel-scheduler")
            assertEquals(JobStage.CANCELLED, store.get("cancel-scheduler")!!.stage)
            assertNull(store.get("cancel-scheduler")!!.sourceUrl)
            assertEquals(DownloadError.ExecutionRestricted, coordinator.error.value)
        } finally { store.close() }
    }

    @Test fun explicitRetryPreservesExactConfirmationAndSchedulesOnlyOneUniqueJob() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        val original = command("explicit-retry")
        store.enqueue(original, Preset.BALANCED); store.fail(original.jobId, DownloadError.Network)
        val created = store.get(original.jobId)!!.createdAt
        val staging = File(app.filesDir, "downloads/${original.jobId}/partial").apply { parentFile!!.mkdirs(); writeText("old bytes") }
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        try {
            coordinator.initialize()
            assertTrue(coordinator.progress.value.single().retryable)
            val results = listOf(async(Dispatchers.IO) { coordinator.retry(original.jobId) }, async(Dispatchers.IO) { coordinator.retry(original.jobId) }).awaitAll()
            assertEquals(1, results.count { it })
            val retried = store.get(original.jobId)!!
            assertEquals(JobStage.ENQUEUED, retried.stage)
            assertEquals(original.source.link.rawUrl, retried.sourceUrl)
            assertEquals(original.format, retried.format)
            assertEquals(original.engineVersion, retried.engineVersion)
            assertEquals(original.filename, retried.filename)
            assertEquals(Preset.BALANCED, retried.preset)
            assertEquals(created, retried.createdAt)
            assertFalse(staging.exists())
            assertEquals(1, downloadRuns(app, original.jobId).size)
        } finally { store.close() }
    }

    @Test fun qualitySourceReadsOnlyTheRetainedFailedLinkAndNeverSchedules() = runBlocking {
        JobStore(app).use { store ->
            val original = command("quality-source")
            store.enqueue(original)
            val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store,
                workManager = { error("Opening quality options must not schedule work") })
            assertNull(coordinator.sourceForQuality(original.jobId))
            store.fail(original.jobId, DownloadError.Network)
            assertEquals(original.source.link.rawUrl, coordinator.sourceForQuality(original.jobId))
            assertEquals(JobStage.FAILED, store.get(original.jobId)!!.stage)
            store.enqueue(command("no-space")); store.fail("no-space", DownloadError.InsufficientSpace)
            assertNull(coordinator.sourceForQuality("no-space"))
            store.clearShareLinks()
            assertNull(coordinator.sourceForQuality(original.jobId))
            assertNull(coordinator.sourceForQuality("missing"))
        }
    }
    @Test fun retryWaitsForOldWorkerToFinishFailureBeforeChangingJournal() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, params: WorkerParameters) = object : Worker(context, params) {
                override fun doWork(): Result { entered.countDown(); check(release.await(8, java.util.concurrent.TimeUnit.SECONDS)); return Result.failure() }
            }
        }).build())
        val wm = WorkManager.getInstance(app)
        val store = JobStore(app)
        store.enqueue(command("finishing")); store.fail("finishing", DownloadError.Network)
        val observingWork = java.util.concurrent.CountDownLatch(1)
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = "engine-42"
            override suspend fun updateYtdlp() = dev.ahmed.savlino.engine.EngineUpdateResult.Success("engine-42", "engine-42")
        }
        val coordinator = DownloadCoordinator(app, engine = engine, store = store, workManager = { observingWork.countDown(); wm })
        lateinit var staging: File
        try {
            coordinator.initialize()
            staging = File(app.filesDir, "downloads/finishing/partial").apply { parentFile!!.mkdirs(); writeText("keep until finished") }
            wm.enqueueUniqueWork("finishing", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DownloadWorker>().build()).result.get()
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            val retry = async(Dispatchers.IO) { coordinator.retry("finishing") }
            assertTrue(withContext(Dispatchers.IO) { observingWork.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertFalse(retry.isCompleted)
            assertEquals(JobStage.FAILED, store.get("finishing")!!.stage)
            assertTrue(staging.exists())
            // Waiting for old WorkInfo must leave the gate free for maintenance/old worker exit.
            assertTrue(coordinator.maintenance.updateNow())
            assertFalse(retry.isCompleted)
            release.countDown()
            assertTrue(withTimeout(6000) { retry.await() })
            assertEquals(JobStage.ENQUEUED, store.get("finishing")!!.stage)
            assertFalse(staging.exists())
            assertEquals(1, downloadRuns(app, "finishing").count { !it.state.isFinished })
        } finally { release.countDown(); store.close() }
    }
    @Test fun unfinishedOldWorkTimeoutPreservesFailedJournalAndStaging() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val wm = WorkManager.getInstance(app)
        val store = JobStore(app)
        store.enqueue(command("not-finished")); store.fail("not-finished", DownloadError.Network)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        try {
            coordinator.initialize()
            wm.enqueueUniqueWork("not-finished", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()).result.get()
            val before = store.get("not-finished")!!
            val staging = File(app.filesDir, "downloads/not-finished/partial").apply { parentFile!!.mkdirs(); writeText("keep") }
            assertFalse(coordinator.retry("not-finished"))
            val after = store.get("not-finished")!!
            assertEquals(JobStage.FAILED, after.stage)
            assertEquals(before.sourceUrl, after.sourceUrl)
            assertEquals(before.createdAt, after.createdAt)
            assertTrue(staging.exists())
            assertEquals(DownloadError.ExecutionRestricted, coordinator.error.value)
            assertEquals("not-finished", coordinator.progress.value.single().jobId)
        } finally { store.close() }
    }
    @Test fun cancellationEventsOnlyDescribeActualNewCancellation() = runBlocking {
        val store = JobStore(app)
        store.enqueue(command("old-cancel")); store.cancel("old-cancel")
        store.enqueue(command("cancel-now"))
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store, workManager = { error("scheduler blocked") })
        val received = kotlinx.coroutines.channels.Channel<DownloadProgress>(10)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.cancelled.collect { received.send(it) } }
        try {
            coordinator.initialize()
            assertTrue(received.tryReceive().isFailure)
            coordinator.cancel("cancel-now")
            assertEquals("cancel-now", withTimeout(5000) { received.receive() }.jobId)
            coordinator.cancel("cancel-now"); coordinator.cancel("missing")
            assertTrue(received.tryReceive().isFailure)
            assertTrue(coordinator.progress.value.isEmpty())
            assertTrue(coordinator.cancelled.replayCache.isEmpty())
        } finally { collector.cancelAndJoin(); store.close() }
    }
    @Test fun oldEnqueueCompletionCannotDismissNewInspection() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by FakeEngine() {
            override suspend fun inspect(source: LinkRevision, operationId: String): dev.ahmed.savlino.engine.InspectResult =
                dev.ahmed.savlino.engine.InspectResult.Success(Inspection(source, "engine-42", listOf(command("format").format)))
        }
        val coordinator = DownloadCoordinator(app, engine, store, workManager = {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            WorkManager.getInstance(app)
        })
        val vm = dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle(), engine = engine,
            currentEngineVersion = { "engine-42" }, downloads = coordinator, coordinatorScope = scope)
        try {
            vm.edit("https://example.com/first"); vm.checkLink()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.selectFormat("opaque-choice"); vm.confirmDownload(); vm.confirmDownload()
            assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            })
            vm.edit("https://example.com/new"); vm.checkLink()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(vm.optionsState is dev.ahmed.savlino.ui.OptionsState.Ready)
            release.countDown()
            kotlinx.coroutines.withTimeout(5000) {
                while (scope.coroutineContext[kotlinx.coroutines.Job]!!.children.any()) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    kotlinx.coroutines.yield()
                }
            }
            assertTrue(vm.optionsState is dev.ahmed.savlino.ui.OptionsState.Ready)
            assertEquals("https://example.com/new", vm.text)
            assertEquals(1, store.all().size)
        } finally { release.countDown(); scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); store.close() }
    }

    @Test fun hungOrphanCleanupCannotBlockStartupOrDownloadAdmission() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val space = object : dev.ahmed.savlino.storage.SpaceGate {
            override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) = Unit
            override fun beforePublication(outputBytes: Long) = Unit
        }
        val publisher = object : dev.ahmed.savlino.storage.Publisher by dev.ahmed.savlino.storage.DestinationPublisher(app, space) {
            override fun pendingCleanupUris() = listOf(android.net.Uri.parse("content://hung.documents/tree/root/document/old"))
            override fun abandon(uri: android.net.Uri, cancellation: android.os.CancellationSignal,
                sweepOpen: () -> Boolean) = abandon(uri)
            override fun abandon(uri: android.net.Uri): dev.ahmed.savlino.storage.RecoveryResult {
                entered.countDown()
                try { check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { finished.countDown() }
                return dev.ahmed.savlino.storage.MissingPublication
            }
        }
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store, publisher = publisher)
            val startup = async(Dispatchers.IO) { coordinator.initialize() }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                assertNull("Startup must keep workers behind the sweep barrier", withTimeoutOrNull(250) { startup.await() })
                withTimeout(5000) { startup.await() }
                assertTrue(withTimeout(1500) { coordinator.enqueue(command("admitted")) })
                assertEquals(JobStage.ENQUEUED, store.get("admitted")!!.stage)
                assertEquals(1L, finished.count)
            } finally {
                release.countDown()
                startup.cancelAndJoin()
                assertTrue(withContext(Dispatchers.IO) { finished.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            }
        }
    }

    @Test fun startupRecoversInterruptedRowsBeforeReschedulingQueuedWork() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        store.enqueue(command("interrupted")); store.startTransfer("interrupted")
        File(app.filesDir, "downloads/interrupted").apply { mkdirs(); resolve("partial").writeText("bytes") }
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        try {
            coordinator.initialize()
            coordinator.initialize()
            assertEquals(JobStage.ENQUEUED, store.get("interrupted")!!.stage)
            assertNull(store.get("interrupted")!!.error)
            assertFalse(store.get("interrupted")!!.hasPartial)
            assertNotNull(store.get("interrupted")!!.sourceUrl)
            assertFalse(File(app.filesDir, "downloads/interrupted").exists())
        } finally { store.close() }
    }

    // Published-file result checks.

    private val link = "https://example.com/clip?token=private-token"
    private val format = Format("f720", "mp4", true, true, videoCodec = "avc1", audioCodec = "mp4a", width = 1280, height = 720,
        size = Size.Exact(4))

    private var resultCheckStoreOpened = false

    @After fun tearDownResultCheck() {
        if (resultCheckStoreOpened) app.deleteDatabase(JobStore.DATABASE_NAME)
    }

    private fun checkedCommand(id: String): DownloadCommand {
        val source = LinkRevision(LinkInput.parse(link)!!, 1)
        return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
    }

    private class Fixture(val store: JobStore, val coordinator: DownloadCoordinator, val stagedSeen: MutableList<Boolean>) : AutoCloseable {
        override fun close() = store.close()
    }

    // graceMs: a probe that returns at once always lands inside the grace (capped at timeoutMs), however loaded the machine;
    // only tests whose probe is held by a latch use a short grace, so its expiry never races a verdict.
    private fun fixture(cipher: LinkCipher? = AesGcmLinkCipher(MemoryLinkKeys()), timeoutMs: Long = 4000, graceMs: Long = 10_000,
        probe: (Fixture) -> MediaProbe): Fixture {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val provider = TestDownloadsProvider(File(app.cacheDir, "provider.mp4"), app.packageName)
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE })
        val store = JobStore(app, null, cipher).also { resultCheckStoreOpened = true; it.all() } // warm SQLite
        lateinit var fixture: Fixture
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))
        val coordinator = DownloadCoordinator(app, engine, store, MediaStorePublisher(app.contentResolver, space, app.packageName) { },
            space = space, probe = { uri -> probe(fixture).probe(uri) }, checkTimeoutMs = timeoutMs, checkGraceMs = graceMs,
            probeThreads = SavedProbeExecutor.newExecutor(),
            parallelDownloads = kotlinx.coroutines.flow.MutableStateFlow(1))
        fixture = Fixture(store, coordinator, mutableListOf())
        return fixture
    }

    private suspend fun Fixture.finish(id: String): DownloadProgress = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { coordinator.completed.first { it.jobId == id } }
        store.enqueue(checkedCommand(id))
        assertEquals(JobStage.COMPLETED, withTimeout(10_000) { coordinator.execute(id, {}, {}) }!!.stage)
        withTimeout(5000) { event.await() }
    }

    @Test fun slowProbeReleasesTransferSlotAndCompletionPrecedesItsLateWarning() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val probes = java.util.concurrent.atomic.AtomicInteger()
        fixture(timeoutMs = 4000, graceMs = 2000) { MediaProbe {
            if (probes.incrementAndGet() == 1) {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); ProbeFacts(1, 0, 5000, 4)
            } else ProbeFacts(1, 1, 5000, 4)
        } }.use { f ->
            try {
                val first = async(Dispatchers.Default) { f.finish("late-problem") }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val next = f.finish("next-while-check-blocked") // the first probe is still held: completing proves the slot was freed
                assertEquals(ResultCheck.Verified(true, true), next.check)
                val done = withTimeout(10_000) { first.await() }
                assertNull(done.check)
                assertTrue(f.coordinator.warnings.value.isEmpty())
                release.countDown()
                val warning = withTimeout(10_000) { f.coordinator.warnings.first { it.any { w -> w.jobId == "late-problem" } } }.single()
                assertEquals(ResultCheck.Problem.NO_SOUND, warning.problem)
                assertTrue(warning.canRetry)
                assertEquals(JobStage.COMPLETED, f.store.get("late-problem")!!.stage)
            } finally { release.countDown() }
        }
    }

    @Test fun cancelAndRemoveDuringCommittedGraceStillDeliverOneCompletedAndNoCancellation() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture(graceMs = 300) { MediaProbe { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); ProbeFacts(1, 1, 5000, 4) } }.use { f ->
            val events = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val callbacks = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val completedCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator.completed.collect { events += it } }
            val cancelledCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator.cancelled.collect { cancelled += it } }
            try {
                val id = "cancel-committed-grace"
                f.store.enqueue(checkedCommand(id))
                val executing = async(Dispatchers.Default) {
                    f.coordinator.execute(id, { androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED }, {}, {}, { callbacks += it })
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // Probe admission happens only after commit and transfer/writer release, while its grace is pending.
                assertEquals(JobStage.COMPLETED, f.store.get(id)!!.stage)
                assertTrue("Cancel must interleave with the pending completion grace",
                    f.coordinator.progress.value.any { it.jobId == id && it.phase == DownloadPhase.SAVING })
                f.coordinator.cancel(id)
                f.coordinator.removeFailed(id)
                assertEquals(JobStage.COMPLETED, f.store.get(id)!!.stage)
                assertEquals(JobStage.COMPLETED, withTimeout(10_000) { executing.await() }!!.stage)
                release.countDown()
                withTimeout(10_000) { while (events.isEmpty()) delay(1) }
                yield()
                completedCollector.cancelAndJoin(); cancelledCollector.cancelAndJoin()
                assertEquals(listOf(DownloadPhase.COMPLETED), events.map { it.phase })
                assertEquals(listOf(DownloadPhase.COMPLETED), callbacks.map { it.phase })
                assertTrue(cancelled.isEmpty())
                assertNull(f.store.get(id)!!.sourceUrl)
            } finally {
                release.countDown(); completedCollector.cancelAndJoin(); cancelledCollector.cancelAndJoin()
            }
        }
    }

    @Test fun matchingFileIsVerifiedOnTheCompletedEventAndStagingWasGoneOnlyAfterSizeWasTaken() = runBlocking {
        fixture { f -> MediaProbe { f.stagedSeen += File(app.filesDir, "downloads/ok").exists(); ProbeFacts(1, 1, 5000, 4) } }.use { f ->
            ShadowLog.clear()
            val done = f.finish("ok")
            assertEquals(ResultCheck.Verified(video = true, audio = true), done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("ok")!!.stage)
            assertTrue(f.coordinator.warnings.value.isEmpty())
            assertEquals(listOf(false), f.stagedSeen) // probe ran after cleanup; staged size came from before it
            assertFalse(ShadowLog.getLogs().any { it.msg?.contains("private-token") == true || it.msg?.contains("example.com") == true })
        }
    }

    @Test fun sizeMismatchIsDamagedAndRaisesAWarningWithTryAgain() = runBlocking {
        fixture { MediaProbe { ProbeFacts(1, 1, 5000, 3) } }.use { f ->
            val done = f.finish("short")
            assertEquals(ResultCheck.Problem.DAMAGED, done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("short")!!.stage)
            val warning = f.coordinator.warnings.value.single()
            assertEquals("short", warning.jobId); assertTrue(warning.canRetry)
            f.coordinator.dismissWarning("short")
            assertTrue(f.coordinator.warnings.value.isEmpty())
        }
    }

    @Test fun throwingOrSlowProbeIsUncheckedAndNeverHangs() = runBlocking {
        fixture { MediaProbe { error("cannot open") } }.use { f ->
            val done = f.finish("throws")
            assertNull(done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("throws")!!.stage)
        }
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val slowRelease = java.util.concurrent.CountDownLatch(1)
        fixture(timeoutMs = 200) { MediaProbe { slowRelease.await(5, java.util.concurrent.TimeUnit.SECONDS); ProbeFacts(0, 0) } }.use { f ->
            val started = System.nanoTime()
            val done = f.finish("slow")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            assertNull(done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
            assertTrue(f.coordinator.warnings.value.isEmpty())
            // A cancel after the check changes nothing: the row stays COMPLETED.
            f.coordinator.cancel("slow")
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
            slowRelease.countDown()
        }
    }

    @Test fun tryAgainEnqueuesANewJobWithTheSameFormatAndLeavesTheOldRowAlone() = runBlocking {
        fixture { MediaProbe { ProbeFacts(1, 0, 5000, 4) } }.use { f ->
            assertEquals(ResultCheck.Problem.NO_SOUND, f.finish("silent").check)
            val before = f.store.get("silent")!!
            val newId = withTimeout(5000) { f.coordinator.tryAgain("silent") }!!
            assertNotEquals("silent", newId)
            val fresh = f.store.get(newId)!!
            assertEquals(JobStage.ENQUEUED, fresh.stage)
            assertEquals(format.id, fresh.format.id)
            val after = f.store.get("silent")!!
            assertEquals(JobStage.COMPLETED, after.stage)
            assertEquals(before.pendingUri, after.pendingUri); assertEquals(before.updatedAt, after.updatedAt)
            assertTrue(f.coordinator.warnings.value.none { it.jobId == "silent" })
        }
    }

    @Test fun noKeptLinkMeansNoTryAgain() = runBlocking {
        fixture(cipher = null) { MediaProbe { ProbeFacts(0, 1, 5000, 4) } }.use { f ->
            assertEquals(ResultCheck.Problem.NO_PICTURE, f.finish("nolink").check)
            assertFalse(f.coordinator.warnings.value.single().canRetry)
            assertNull(f.coordinator.tryAgain("nolink"))
        }
    }

    // Resume recipe compatibility.

    @Test fun legacyYtdlpRecipeAdoptsItsPartialWithoutRestarting() = checkResume("legacy")
    @Test fun nativeFallbackMarkerAdoptsYtdlpBytesOnNextAttempt() = checkResume("fallback")
    @Test fun differentArgvRecipeDiscardsOldBytes() = checkResume("changed")
    // S8: an app update alone keeps the bytes; another yt-dlp or argv still discards them.
    @Test fun olderVersionCodeWithTheSameEngineAndArgvAdoptsItsPartial() = checkResume("update")
    @Test fun olderVersionCodeWithAnotherEngineDiscardsOldBytes() = checkResume("update-engine")
    @Test fun olderVersionCodeWithAnotherArgvDiscardsOldBytes() = checkResume("update-argv")
    @Test fun nativeFallbackFromAnOlderVersionCodeAdoptsYtdlpBytes() = checkResume("update-fallback")
    // F5: a current recipe whose engine version is all digits is not mistaken for the old "<versionCode>:" form.
    @Test fun allDigitEngineVersionKeepsItsCurrentRecipe() = checkResume("digits")
    // F2: a finished native file (publish removed its sidecar, so the rerun sees ytdlp) is saved, not downloaded again.
    @Test fun finishedNativeFileWithoutItsSidecarIsAdopted() = checkResume("native-finished")
    @Test fun controlRecordNeverCountsAsPartialBytes() {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "empty-partial").canonicalFile.apply { mkdirs() }
        try {
            directory.resolve(DirectResume.PART).writeBytes(byteArrayOf())
            DirectResume.write(directory, DirectResume.Record("ETag", "\"a\"", 6))
            assertEquals(0L, stagingBytes(directory))
            assertFalse(stagingBytes(directory) > 0)
        } finally { directory.deleteRecursively() }
    }

    private fun checkResume(mode: String) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val store = JobStore(app)
        val id = "recipe-$mode"
        val command = command(id)
        val engineVersion = if (mode == "digits") "20251112" else "engine-42"
        val base = "$engineVersion:${YtdlpCommand.ARGV_VERSION}"
        val older = dev.ahmed.savlino.BuildConfig.VERSION_CODE - 1
        val previous = when (mode) {
            "legacy" -> "0:engine-42:${YtdlpCommand.ARGV_VERSION}"
            "fallback" -> "$base:engine=native"
            "update" -> "$older:$base:engine=ytdlp"
            "update-engine" -> "$older:engine-41:${YtdlpCommand.ARGV_VERSION}:engine=ytdlp"
            "update-argv" -> "$older:engine-42:${YtdlpCommand.ARGV_VERSION - 1}:engine=ytdlp"
            "update-fallback" -> "$older:$base:engine=native"
            "digits" -> "$base:engine=ytdlp"
            "native-finished" -> "$base:engine=native"
            else -> "old:recipe"
        }
        val dir = File(app.filesDir, "downloads/$id").apply { mkdirs() }
        val kept = if (mode == "native-finished") YtdlpCommand.finishedSource(command.format)!! else DirectResume.PART
        dir.resolve(kept).writeText("kept")
        if (mode.endsWith("fallback")) DirectResume.write(dir, DirectResume.Record(null, null, null))
        assertTrue(store.enqueue(command)); assertTrue(store.startTransfer(id, previous)); assertTrue(store.requeue(id, true))
        var actualPartial: String? = null
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = engineVersion
            override fun resumeEngine(command: DownloadCommand, directory: File) = "ytdlp"
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                actualPartial = directory.resolve(kept).takeIf { it.isFile }?.readText()
                return TransferResult.Failure(DownloadError.Failed, resumeAllowed = false)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE }))
        try {
            coordinator.execute(id, {}, {})
            assertEquals(if (mode in setOf("changed", "update-engine", "update-argv")) null else "kept", actualPartial)
            assertEquals("$base:engine=ytdlp", store.get(id)?.resumeRecipe)
        } finally { store.close(); dir.deleteRecursively() }
    }
}

internal fun command(id: String, size: Size = Size.Exact(4)): DownloadCommand {
    val source = LinkRevision(LinkInput.parse("https://example.com/clip?token=private")!!, 7)
    val format = Format("opaque-choice", "mp4", true, true, height = 720, size = size,
        sourceFormatId = "exact/source")
    return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source,
        format.id, "clip") as ConfirmResult.Confirmed).command
}
