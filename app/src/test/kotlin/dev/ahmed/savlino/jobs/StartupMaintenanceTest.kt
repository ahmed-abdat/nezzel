package dev.ahmed.savlino.jobs

import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.ui.LinkViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class StartupMaintenanceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private open class Engine : EngineAdapter by FakeEngine() {
        var updates = 0
        override suspend fun installedVersion() = "test-version"
        override suspend fun updateYtdlp(): EngineUpdateResult {
            updates++
            return EngineUpdateResult.Success("test-version", "test-version")
        }
    }
    @Test fun linkDismissedBeforeBusyDecisionReturnsStillResumesMaintenance() = runBlocking {
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val resumptions = kotlinx.coroutines.channels.Channel<Runnable>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                check(resumptions.trySend(block).isSuccess)
            }
        }
        val engine = object : Engine() {
            override suspend fun installedVersion(): String {
                entered.complete(Unit); release.await(); return "test-version"
            }
        }
        val maintenance = EngineMaintenance(app, engine, { false })
        val checking = async(caller, start = CoroutineStart.UNDISPATCHED) { maintenance.checkWhenInspectionIdle() }
        try {
            withTimeout(2000) {
                resumptions.receive().run() // Resume after recording the launch; begin the version lookup.
                entered.await()
                maintenance.setPendingLink(true)
                release.complete(Unit)
                val afterBusyDecision = resumptions.receive() // IO has skipped maintenance and released the gate.
                assertEquals(0, engine.updates)
                assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
                maintenance.setPendingLink(false)
                afterBusyDecision.run()
                while (!checking.isCompleted) resumptions.receive().run()
                assertTrue(checking.await())
                assertEquals(1, engine.updates)
            }
        } finally {
            release.complete(Unit); checking.cancel()
            withTimeout(2000) { while (!checking.isCompleted) resumptions.receive().run() }
            checking.join(); resumptions.close()
        }
    }

    @Test fun linkArrivingDuringVersionLookupDefersThenResumesThisLaunch() = runBlocking {
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val engine = object : Engine() {
            override suspend fun installedVersion(): String {
                entered.complete(Unit); release.await(); return "test-version"
            }
        }
        val maintenance = EngineMaintenance(app, engine, { false })
        val checking = async { maintenance.checkWhenInspectionIdle() }
        try {
            withTimeout(2000) { entered.await() }
            maintenance.setPendingLink(true)
            release.complete(Unit)
            assertNull(withTimeoutOrNull(100) { checking.await() })
            assertEquals(0, engine.updates)
            assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
            maintenance.setPendingLink(false)
            assertTrue(withTimeout(2000) { checking.await() })
            assertEquals(1, engine.updates)
        } finally { release.complete(Unit); checking.cancelAndJoin() }
    }

    @Test fun firstLaunchDefersWithoutConsumingAttemptUntilFirstInspectionEnds() = runBlocking {
        val engine = Engine()
        val maintenance = EngineMaintenance(app, engine, { false })
        assertFalse(maintenance.checkOnStartup())
        assertEquals(0, engine.updates)
        assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inspecting = launch { maintenance.withInspection { entered.complete(Unit); release.await() } }
        entered.await()
        assertFalse(maintenance.checkOnStartup())
        release.complete(Unit); inspecting.join()
        assertTrue(maintenance.checkOnStartup())
        assertEquals(1, engine.updates)
    }
    @Test fun failedFirstInspectionAlsoReleasesDeferral() = runBlocking {
        val engine = Engine(); val maintenance = EngineMaintenance(app, engine, { false })
        assertFalse(maintenance.checkOnStartup())
        runCatching { maintenance.withInspection { error("inspection failed") } }
        assertTrue(maintenance.checkOnStartup())
    }
    @Test fun nextLaunchMayMaintainEvenWithoutAnyInspection() = runBlocking {
        val engine = Engine()
        assertFalse(EngineMaintenance(app, engine, { false }).checkOnStartup())
        assertTrue(EngineMaintenance(app, engine, { false }).checkOnStartup())
        assertEquals(1, engine.updates)
    }
    @Test fun pendingPasteDefersAutomaticButDoesNotChangeManualUpdate() = runBlocking {
        val engine = Engine()
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine, store)
            coordinator.maintenance.withInspection { } // First attempt already ended.
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator).apply { automaticOptions = false }
            vm.paste("https://youtu.be/pending")
            assertFalse(coordinator.maintenance.checkOnStartup())
            assertEquals(0, engine.updates)
            assertTrue(coordinator.maintenance.updateNow())
        }
    }
    @Test fun clearingPendingLinkReleasesAutomaticMaintenance() = runBlocking {
        val engine = Engine()
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine, store)
            coordinator.maintenance.withInspection { }
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator).apply { automaticOptions = false }
            vm.edit("https://youtu.be/pending")
            assertFalse(coordinator.maintenance.checkOnStartup())
            vm.edit("")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(coordinator.maintenance.checkOnStartup())
        }
    }
}
