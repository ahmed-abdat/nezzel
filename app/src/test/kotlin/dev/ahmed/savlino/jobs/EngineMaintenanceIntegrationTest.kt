package dev.ahmed.savlino.jobs

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EngineMaintenanceIntegrationTest {
    private class HeldUpdater : EngineAdapter by FakeEngine() {
        val release = CompletableDeferred<Unit>()
        val cancellations = mutableListOf<String>()
        override suspend fun installedVersion() = "engine-42"
        override suspend fun updateYtdlp(): EngineUpdateResult {
            release.await()
            return EngineUpdateResult.Success("engine-42", "engine-42")
        }
        override fun cancel(operationId: String) { cancellations += operationId }
    }
    @Test fun enqueueWaitsForUpdaterBeforePersistingOrSchedulingNewJob() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        val engine = HeldUpdater()
        val coordinator = DownloadCoordinator(app, engine, store)
        coordinator.initialize()
        val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
        withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        val enqueuing = async(Dispatchers.IO) { coordinator.enqueue(command("enqueue-update")) }
        try {
            assertNull("Enqueue must wait for maintenance", withTimeoutOrNull(250) { enqueuing.await() })
            assertNull(store.get("enqueue-update"))
            assertTrue(coordinator.progress.value.isEmpty())
            assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("enqueue-update").get().isEmpty())
            engine.release.complete(Unit)
            assertTrue(updating.await())
            assertTrue(withTimeout(5000) { enqueuing.await() })
            assertEquals(JobStage.ENQUEUED, store.get("enqueue-update")!!.stage)
            assertTrue(coordinator.progress.value.single().waiting)
            assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork("enqueue-update").get().size)
        } finally { engine.release.complete(Unit); updating.cancelAndJoin(); enqueuing.cancelAndJoin(); store.close() }
    }
    @Test fun startupRecoveryWaitsForUpdaterBeforeTouchingFailedStaging() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        store.enqueue(command("startup")); store.fail("startup", DownloadError.Network)
        val staging = File(app.filesDir, "downloads/startup/partial").apply { parentFile!!.mkdirs(); writeText("partial") }
        val engine = HeldUpdater()
        val coordinator = DownloadCoordinator(app, engine, store)
        val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
        withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        val initializing = async(Dispatchers.IO) { coordinator.initialize() }
        try {
            assertNull("Startup must wait for maintenance", withTimeoutOrNull(250) { initializing.await(); true })
            assertTrue(staging.exists())
            engine.release.complete(Unit)
            assertTrue(updating.await())
            withTimeout(5000) { initializing.await() }
            assertFalse(staging.exists())
        } finally { engine.release.complete(Unit); updating.cancelAndJoin(); initializing.cancelAndJoin(); store.close() }
    }
    @Test fun failedRetryWaitsForUpdaterBeforeResettingExactJournal() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val store = JobStore(app)
        val original = command("retry-update")
        store.enqueue(original); store.fail(original.jobId, DownloadError.Network)
        val engine = HeldUpdater()
        val coordinator = DownloadCoordinator(app, engine, store)
        coordinator.initialize()
        val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
        withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        val retrying = async(Dispatchers.IO) { coordinator.retry(original.jobId) }
        try {
            assertNull("Retry must wait for maintenance", withTimeoutOrNull(250) { retrying.await() })
            assertEquals(JobStage.FAILED, store.get(original.jobId)!!.stage)
            engine.release.complete(Unit)
            assertTrue(updating.await())
            assertTrue(withTimeout(5000) { retrying.await() })
            val restored = store.get(original.jobId)!!.command()!!
            assertEquals(original.source.link.rawUrl, restored.source.link.rawUrl)
            assertEquals(original.source.revision, restored.source.revision)
            assertEquals(original.engineVersion, restored.engineVersion)
            assertEquals(original.format, restored.format)
            assertEquals(original.filename, restored.filename)
            assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(original.jobId).get().size)
        } finally { engine.release.complete(Unit); updating.cancelAndJoin(); retrying.cancelAndJoin(); store.close() }
    }
    @Test fun enqueueCannotScheduleUntilMaintenanceReleasesGate() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = HeldUpdater()
        val scheduled = CompletableDeferred<Unit>()
        val coordinator = DownloadCoordinator(app, engine, store, workManager = {
            scheduled.complete(Unit); error("Scheduling unavailable")
        })
        coordinator.initialize()
        val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
        withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        val enqueuing = async(Dispatchers.IO) { coordinator.enqueue(command("enqueue-update")) }
        try {
            assertNull("Scheduling must wait for maintenance", withTimeoutOrNull(250) { scheduled.await(); true })
            engine.release.complete(Unit)
            assertTrue(updating.await())
            assertTrue(withTimeout(5000) { enqueuing.await() })
            assertEquals(JobStage.ENQUEUED, store.get("enqueue-update")!!.stage)
            assertTrue(coordinator.progress.value.single().awaitingSchedule)
        } finally { engine.release.complete(Unit); updating.cancelAndJoin(); enqueuing.cancelAndJoin(); store.close() }
    }
    @Test fun immediateCancellationSignalDoesNotWaitForMaintenance() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = HeldUpdater()
        val coordinator = DownloadCoordinator(app, engine, store)
        val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
        withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        try {
            coordinator.requestCancellation("cancel-now")
            assertEquals(listOf("cancel-now"), engine.cancellations)
            assertFalse(updating.isCompleted)
        } finally { engine.release.complete(Unit); updating.cancelAndJoin(); store.close() }
    }
}
