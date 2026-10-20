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
                assertEquals(listOf("queued", "running"),
                    dev.ahmed.savlino.downloads.visibleJobs(f.coordinator.progress.value).map { it.jobId })
                val scheduled = WorkManager.getInstance(app).getWorkInfosForUniqueWork("queued").get().single().id
                assertFalse(withTimeout(5000) { f.coordinator.retry("queued") })
                assertEquals(scheduled, WorkManager.getInstance(app).getWorkInfosForUniqueWork("queued").get().single().id)
                f.beforeSchedule = { error("Scheduler unavailable") }
                assertTrue(withTimeout(5000) { f.coordinator.enqueue(command("retry-queued")) })
                assertTrue(f.coordinator.progress.value.single { it.jobId == "retry-queued" }.awaitingSchedule)
                f.beforeSchedule = {}
                assertTrue(withTimeout(5000) { f.coordinator.retry("retry-queued") })
                val retried = f.coordinator.progress.value.single { it.jobId == "retry-queued" }
                assertEquals(DownloadPhase.PREPARING, retried.phase)
                assertTrue(retried.waiting)
                assertFalse(retried.awaitingSchedule)
                assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork("retry-queued").get().size)
                assertEquals(JobStage.ENQUEUED, f.store.get("queued")!!.stage)
                assertFalse(running.isCompleted)
            } finally { f.release.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun transfersStaySerialAndQueuedCancellationCannotStartOrCancelTheOtherJob() = runBlocking {
        heldTransfer().use { f ->
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
                assertEquals(JobStage.ENQUEUED, f.store.get("preparing")!!.stage)
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

    private fun heldTransfer(): HeldTransfer {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        return HeldTransfer()
    }
    private inner class HeldTransfer : AutoCloseable {
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
                return TransferResult.Failure(DownloadError.Network)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE }), workManager = {
            beforeSchedule(); WorkManager.getInstance(app)
        })
        override fun close() = store.close()
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
            val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("double").get()
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
            assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork("schedule-failure").get().size)
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
            assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(original.jobId).get().size)
        } finally { store.close() }
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
            assertEquals(1, wm.getWorkInfosForUniqueWork("finishing").get().count { !it.state.isFinished })
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
            assertEquals(JobStage.FAILED, store.get("interrupted")!!.stage)
            assertEquals(DownloadError.Interrupted, store.get("interrupted")!!.error)
            assertNotNull(store.get("interrupted")!!.sourceUrl)
            assertFalse(File(app.filesDir, "downloads/interrupted").exists())
        } finally { store.close() }
    }
}

internal fun command(id: String, size: Size = Size.Exact(4)): DownloadCommand {
    val source = LinkRevision(LinkInput.parse("https://example.com/clip?token=private")!!, 7)
    val format = Format("opaque-choice", "mp4", true, true, height = 720, size = size,
        sourceFormatId = "exact/source")
    return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source,
        format.id, "clip") as ConfirmResult.Confirmed).command
}
