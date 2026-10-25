package dev.ahmed.savlino

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.media.ImageInspect
import dev.ahmed.savlino.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineUpdateFlowTest {
    private class UpdatingEngine : EngineAdapter by FakeEngine() {
        override var isAvailable = true
        var version = "2025.11.12"
        var failure: DownloadError? = DownloadError.Unsupported
        var updateFails = false
        var unreadableAfterUpdate = false
        var hold: CompletableDeferred<Unit>? = null
        var inspectHold: CompletableDeferred<Unit>? = null
        val inspected = mutableListOf<String>()
        override suspend fun installedVersion() = version.takeUnless { unreadableAfterUpdate && it == "2026.08.19" }
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            inspectHold?.await()
            return failure?.let { InspectResult.Failure(it) } ?: InspectResult.Success(Inspection(source, version,
                listOf(Format("720", "mp4", true, true, height = 720)),
                listOf(PresetChoice(Preset.BALANCED, "720")), title = "Real title"))
        }
        override suspend fun updateYtdlp(): EngineUpdateResult {
            hold?.await()
            isAvailable = true
            version = "2026.08.19"
            failure = null
            return if (updateFails) EngineUpdateResult.Failure(DownloadError.Network)
                else EngineUpdateResult.Success("2025.11.12", version)
        }
    }
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun await(job: Job) = runBlocking {
        withTimeout(5000) { while (job.isActive) { pump(); delay(5) } }
        pump()
    }
    @Test fun neverUpdatedUnsupportedAndFailedInspectionsOfferUpdateThenReinspectSameLink() {
        for (error in listOf(DownloadError.Unsupported, DownloadError.Failed)) {
            val app = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
            val store = JobStore(app)
            val engine = UpdatingEngine().apply { failure = error }
            val coordinator = DownloadCoordinator(app, engine, store)
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
            try {
                vm.edit("https://example.com/video"); vm.checkLink(); pump()
                assertTrue(vm.canUpdateAndRetry)
                assertEquals("2025.11.12", engine.version)
                await(vm.updateEngineAndRetry()!!)
                assertTrue(vm.optionsState is OptionsState.Ready)
                assertFalse(vm.canUpdateAndRetry)
                assertEquals(listOf("https://example.com/video", "https://example.com/video"), engine.inspected)
                assertTrue(store.all().isEmpty()) // Reinspection never confirms/enqueues media.
                assertTrue(vm.downloadEnabled)
            } finally { vm.dismissOptions(); store.close() }
        }
    }
    @Test fun firstRunMaintenanceAfterFailedFirstReadReinspectsOnceOnlyWhenUpdateSucceeds() {
        for (updateFails in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
            val store = JobStore(app)
            val engine = UpdatingEngine().apply { failure = DownloadError.Failed; this.updateFails = updateFails }
            val coordinator = DownloadCoordinator(app, engine, store)
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
            try {
                vm.edit("https://example.com/video"); vm.checkLink(); pump()
                assertEquals(OptionsState.Error(DownloadError.Failed), vm.optionsState)
                vm.suggest(true, true, true) { error("An open sheet guards the clipboard") }
                // Automatic first-run maintenance runs only after that first read finished.
                runBlocking { withTimeout(5000) { assertEquals(!updateFails, coordinator.maintenance.checkWhenInspectionIdle()) } }
                pump()
                if (updateFails) {
                    assertEquals(OptionsState.Error(DownloadError.Failed), vm.optionsState)
                    assertEquals(listOf("https://example.com/video"), engine.inspected)
                } else {
                    assertTrue(vm.optionsState is OptionsState.Ready)
                    assertEquals(listOf("https://example.com/video", "https://example.com/video"), engine.inspected)
                }
                assertTrue(store.all().isEmpty())
            } finally { vm.dismissOptions(); store.close() }
        }
    }
    @Test fun updateDoesNotReinspectAnErrorThatNoEngineReadProduced() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { failure = null }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        try {
            vm.edit("https://example.com/video"); vm.checkLink(); pump()
            assertTrue(vm.optionsState is OptionsState.Ready)
            // Event links are refused before any engine read (review scenario).
            vm.edit("https://www.facebook.com/events/123"); vm.checkLink(); pump()
            assertEquals(OptionsState.Error(DownloadError.Unsupported), vm.optionsState)
            runBlocking { assertTrue(coordinator.maintenance.updateNow()) }; pump()
            assertEquals(OptionsState.Error(DownloadError.Unsupported), vm.optionsState)
            assertEquals(listOf("https://example.com/video"), engine.inspected)
        } finally { vm.dismissOptions(); store.close() }
    }
    @Test fun updateCompletionCannotReopenDismissedSheetOrInspectEditedLink() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        try {
            vm.edit("https://example.com/video"); vm.checkLink(); pump()
            val job = vm.updateEngineAndRetry()!!
            vm.edit("https://example.com/new"); vm.dismissOptions()
            engine.hold!!.complete(Unit)
            await(job)
            assertEquals(OptionsState.Hidden, vm.optionsState)
            assertEquals(listOf("https://example.com/video"), engine.inspected)
            assertTrue(store.all().isEmpty())
        } finally { vm.dismissOptions(); store.close() }
    }
    @Test fun failedUpdateThatReplacesBinaryInvalidatesOldSelection() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { failure = null; updateFails = true }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        try {
            vm.edit("https://example.com/video"); vm.checkLink(); pump()
            assertTrue(vm.downloadEnabled)
            runBlocking { assertFalse(coordinator.maintenance.updateNow()) }; pump()
            // Item 8: the replaced binary makes the sheet stale. Download reads the link again on it and queues the fresh row.
            assertTrue(vm.downloadEnabled)
            vm.confirmDownload(); pump()
            assertEquals(2, engine.inspected.size)
            waitWhile({ pump() }) { store.all().isEmpty() }
            assertEquals("2026.08.19", store.all().single().engineVersion)
        } finally { vm.dismissOptions(); store.close() }
    }
    @Test fun busyUpdateCannotRestoreStaleConfirmationAfterReplacementVersionReadFailed() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { failure = null; updateFails = true; unreadableAfterUpdate = true }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        try {
            vm.edit("https://example.com/video"); vm.checkLink(); pump()
            assertTrue(vm.downloadEnabled)
            runBlocking { assertFalse(coordinator.maintenance.updateNow()) }; pump()
            assertFalse(vm.downloadEnabled)
            store.enqueue(command("queued"))
            runBlocking { assertFalse(coordinator.maintenance.updateNow()) }; pump()
            assertEquals(EngineUpdateStatus.BUSY, coordinator.maintenance.state.value.status)
            assertFalse("A busy status must not revive the old inspected binary version", vm.downloadEnabled)
        } finally { vm.dismissOptions(); store.close() }
    }
    @Test fun inspectionWaitsForManualMaintenanceThenUsesCurrentGenerationForConfirmation() {
        for (failedUpdate in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
            val store = JobStore(app)
            val engine = UpdatingEngine().apply { hold = CompletableDeferred(); updateFails = failedUpdate }
            val coordinator = DownloadCoordinator(app, engine, store)
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
            // Automatic maintenance yields on a fresh install; an explicit update still owns admission.
            val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
            try {
                runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
                vm.paste("https://example.com/video"); pump()
                assertTrue(vm.optionsState is OptionsState.Checking)
                assertTrue(vm.engineWaiting)
                assertTrue(engine.inspected.isEmpty())
                assertTrue(store.all().isEmpty())
                engine.hold!!.complete(Unit)
                await(updating)
                runBlocking { withTimeout(2000) { while (vm.optionsState is OptionsState.Checking) { pump(); delay(5) } } }
                assertTrue(vm.optionsState is OptionsState.Ready)
                assertTrue("Generation must be captured after maintenance, or confirmation stays disabled", vm.downloadEnabled)
                assertFalse(vm.engineWaiting)
                assertEquals(listOf("https://example.com/video"), engine.inspected)
                assertTrue(store.all().isEmpty())
            } finally { vm.dismissOptions(); engine.hold!!.complete(Unit); await(updating); store.close() }
        }
    }

    @Test fun firstInspectionPrecedesAutomaticMaintenanceAndReplacementInvalidatesItsSelection() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { failure = null; hold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        var updating: Deferred<Boolean>? = null
        try {
            runBlocking { assertFalse(coordinator.maintenance.checkOnStartup()) }
            vm.paste("https://example.com/video"); pump()
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertTrue(vm.downloadEnabled)
            assertFalse(vm.engineWaiting)
            assertEquals(0L, coordinator.maintenance.state.value.generation)
            assertEquals("2025.11.12", (vm.optionsState as OptionsState.Ready).inspection.engineVersion)
            vm.suggest(true, true, true) { error("An open sheet guards the clipboard") }
            updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.checkWhenInspectionIdle() }
            // An open options sheet keeps the user's choice usable: maintenance waits until it closes.
            runBlocking { delay(300) }; pump()
            assertNotEquals(EngineUpdateStatus.UPDATING, coordinator.maintenance.state.value.status)
            assertTrue(vm.downloadEnabled)
            vm.dismissOptions(); vm.edit(""); pump() // A link left in the field is still pending.
            runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
            engine.hold!!.complete(Unit); await(updating)
            vm.paste("https://example.com/video"); pump()
            assertTrue(vm.downloadEnabled)
            assertEquals("2026.08.19", (vm.optionsState as OptionsState.Ready).inspection.engineVersion)
            assertEquals(1L, coordinator.maintenance.state.value.generation)
            assertTrue(store.all().isEmpty())
        } finally { vm.dismissOptions(); engine.hold!!.complete(Unit); updating?.let(::await); store.close() }
    }

    @Test fun dismissDuringReadinessWaitCannotInspectOrReopenAfterMaintenance() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
        try {
            runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
            vm.paste("https://example.com/video"); pump()
            assertTrue(vm.engineWaiting)
            vm.dismissOptions(); pump()
            assertEquals(OptionsState.Hidden, vm.optionsState)
            engine.hold!!.complete(Unit); await(updating); pump()
            assertTrue(engine.inspected.isEmpty())
            assertEquals(OptionsState.Hidden, vm.optionsState)
            assertTrue(store.all().isEmpty())
            runBlocking { assertTrue(coordinator.maintenance.updateNow()) }
        } finally { vm.dismissOptions(); engine.hold!!.complete(Unit); await(updating); store.close() }
    }

    @Test fun maintenanceDefersWhileViewModelInspectionIsRunning() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { failure = null; inspectHold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        try {
            vm.paste("https://example.com/video"); pump()
            assertEquals(listOf("https://example.com/video"), engine.inspected)
            runBlocking { assertFalse(coordinator.maintenance.checkOnStartup()) }
            assertEquals("2025.11.12", engine.version)
            engine.inspectHold!!.complete(Unit); pump()
            assertTrue(vm.optionsState is OptionsState.Ready)
            // An open options sheet still defers automatic maintenance; closing it admits the update.
            runBlocking { assertFalse(coordinator.maintenance.checkOnStartup()) }
            vm.dismissOptions(); vm.edit(""); pump() // A link left in the field is still pending.
            runBlocking { assertTrue(coordinator.maintenance.checkOnStartup()) }
        } finally { engine.inspectHold!!.complete(Unit); vm.dismissOptions(); store.close() }
    }

    @Test fun temporaryUnavailabilityDuringMaintenanceWaitsInsteadOfFailingBeforeAdmission() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator)
        val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
        try {
            runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
            // A failed first extraction can temporarily set EngineInitialization.isAvailable=false.
            engine.isAvailable = false
            vm.paste("https://example.com/video"); pump()
            assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Checking)
            assertTrue(vm.engineWaiting)
            assertTrue(engine.inspected.isEmpty())
            engine.hold!!.complete(Unit); await(updating); pump()
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertTrue(vm.downloadEnabled)
            assertEquals(listOf("https://example.com/video"), engine.inspected)
            assertTrue(store.all().isEmpty())
        } finally { vm.dismissOptions(); engine.hold!!.complete(Unit); await(updating); store.close() }
    }

    @Test fun durableQueuedDownloadBlocksUpdateBeforeWorkerBegins() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        // Exercise queue exclusion independently of the fresh-install first-inspection deferral.
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
        val store = JobStore(app)
        val engine = UpdatingEngine()
        store.enqueue(command("queued"))
        val coordinator = DownloadCoordinator(app, engine, store)
        try {
            assertFalse(coordinator.maintenance.updateNow())
            assertFalse(coordinator.maintenance.checkOnStartup())
            assertEquals(EngineUpdateStatus.BUSY, coordinator.maintenance.state.value.status)
            assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
            assertEquals("2025.11.12", engine.version)
            assertEquals(JobStage.ENQUEUED, store.get("queued")!!.stage)
            store.fail("queued", DownloadError.Cancelled)
            assertTrue(coordinator.maintenance.checkOnStartup())
            assertEquals("2026.08.19", engine.version)
        } finally { store.close() }
    }

    @Test fun imageInspectionDoesNotWaitForYtdlpMaintenance() {
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val coordinator = DownloadCoordinator(app, engine, store)
        val images = object : ImageInspect {
            override suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute) =
                MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.IMAGE)
            override fun cancel(operationId: String) { }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, images = images)
        val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
        try {
            runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
            vm.paste("https://example.com/photo.jpg"); pump()
            assertEquals(OptionsState.ImageError(ImageFailure.NETWORK, directImage = true, phase = ImagePhase.IMAGE), vm.optionsState)
            assertFalse(updating.isCompleted)
            assertTrue(engine.inspected.isEmpty())
            assertTrue(store.all().isEmpty())
        } finally { vm.dismissOptions(); engine.hold!!.complete(Unit); await(updating); pump(); store.close() }
    }
}
