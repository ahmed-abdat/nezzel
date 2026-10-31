package dev.ahmed.savlino.jobs

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EngineMaintenanceTest {
    private class UpdatingEngine : EngineAdapter by FakeEngine() {
        var version = "2025.11.12"
        var fail = false
        var rolledBack = false
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun installedVersion() = version
        override suspend fun updateYtdlp(): EngineUpdateResult {
            hold?.await()
            version = "2026.08.19"
            if (rolledBack) { version = "2025.11.12"; return EngineUpdateResult.Failure(DownloadError.Failed, usingBuiltIn = true) }
            return if (fail) EngineUpdateResult.Failure(DownloadError.Network)
                else EngineUpdateResult.Success("2025.11.12", version)
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    @org.junit.Before fun establishedInstall() {
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
    }
    private val day = 86_400_000L
    @Test fun localVersionDiscoveryPublishesVersionWithoutMaintenance() = runBlocking {
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { false })
        maintenance.refreshVersion()
        assertEquals("2025.11.12", maintenance.state.value.version)
        assertEquals(EngineUpdateStatus.IDLE, maintenance.state.value.status)
        assertFalse(maintenance.state.value.everSucceeded)
        val prefs = app.getSharedPreferences("engine-maintenance", 0)
        assertFalse(prefs.contains("attempt"))
        assertFalse(prefs.contains("success"))
    }
    @Test fun failedLocalVersionDiscoveryKeepsUnknownWithoutUpdateFailure() = runBlocking {
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion(): String? = throw IllegalStateException()
        }
        val maintenance = EngineMaintenance(app, engine, { false })
        maintenance.refreshVersion()
        assertNull(maintenance.state.value.version)
        assertEquals(EngineUpdateStatus.IDLE, maintenance.state.value.status)
        assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
    }
    @Test fun pausedOnlyDownloadsWaitUnlessUpdateNowWasConfirmed() = runBlocking {
        var running = false
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { true }, hasRunningDownloads = { running })
        assertFalse("weekly maintenance never asks; it waits", maintenance.checkOnStartup())
        assertFalse(maintenance.updateNow())
        assertEquals(EngineUpdateStatus.BUSY, maintenance.state.value.status)
        running = true
        assertFalse("a running or queued download keeps the busy rule", maintenance.updateNow(includePaused = true))
        assertEquals("2025.11.12", engine.version)
        running = false
        assertTrue(maintenance.updateNow(includePaused = true))
        assertEquals("2026.08.19", engine.version)
    }
    @Test fun successfulCheckIsDurableAndSuppressesAutomaticChecksForSevenDays() = runBlocking {
        var now = 20 * day
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        assertTrue(maintenance.checkOnStartup())
        assertEquals("2026.08.19", maintenance.state.value.version)
        assertTrue(maintenance.state.value.everSucceeded)
        engine.version = "sentinel"
        now += 6 * day
        assertFalse(EngineMaintenance(app, engine, { false }, { now }).checkOnStartup())
        assertEquals("sentinel", engine.version)
        now += day
        assertTrue(EngineMaintenance(app, engine, { false }, { now }).checkOnStartup())
        assertEquals("2026.08.19", engine.version)
    }
    @Test fun failedChecksAreSilentTypedSettingsStateRefreshActualVersionAndRateLimitedAcrossRestart() = runBlocking {
        var now = 20 * day
        val engine = UpdatingEngine().apply { fail = true }
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        assertFalse(maintenance.checkOnStartup())
        assertEquals(EngineUpdateStatus.FAILED, maintenance.state.value.status)
        assertEquals("2026.08.19", maintenance.state.value.version)
        assertFalse(maintenance.state.value.everSucceeded)
        engine.version = "unchanged"
        now += day - 1
        assertFalse(EngineMaintenance(app, engine, { false }, { now }).checkOnStartup())
        assertEquals("unchanged", engine.version)
        now++
        engine.fail = false
        assertTrue(EngineMaintenance(app, engine, { false }, { now }).checkOnStartup())
    }
    @Test fun activeOrQueuedDownloadDefersWithoutConsumingAttemptAndManualRetryBypassesCadence() = runBlocking {
        var busy = true
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { busy }, { 20 * day })
        assertFalse(maintenance.checkOnStartup())
        assertEquals("2025.11.12", engine.version)
        assertEquals(EngineUpdateStatus.BUSY, maintenance.state.value.status)
        busy = false
        assertTrue(maintenance.checkOnStartup())
        engine.version = "old again"
        assertTrue(maintenance.updateNow())
        assertEquals("2026.08.19", engine.version)
    }
    @Test fun updateDuringTransferReturnsBusyAndDoesNotReplaceBinary() = runBlocking {
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transfer = launch { maintenance.withDownload { entered.complete(Unit); release.await() } }
        entered.await()
        try {
            assertFalse(maintenance.updateNow())
            assertEquals(EngineUpdateStatus.BUSY, maintenance.state.value.status)
            assertEquals("2025.11.12", engine.version)
        } finally { release.complete(Unit); transfer.join() }
        assertTrue(maintenance.checkOnStartup())
        assertEquals("2026.08.19", engine.version)
    }

    @Test fun downloadPermitsOverlapAndCancellationKeepsMaintenanceOutUntilLastRelease() = runBlocking {
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        val entered = CompletableDeferred<Unit>()
        val transfer = launch { maintenance.withDownload { entered.complete(Unit); awaitCancellation() } }
        val secondEntered = CompletableDeferred<Unit>()
        var second: Job? = null
        try {
            withTimeout(5000) { entered.await() }
            second = launch { maintenance.withDownload { secondEntered.complete(Unit); awaitCancellation() } }
            withTimeout(5000) { secondEntered.await() }
            transfer.cancelAndJoin()
            assertFalse(maintenance.updateNow())
            assertEquals("2025.11.12", engine.version)
            second.cancelAndJoin()
            assertTrue(maintenance.updateNow())
            assertEquals("2026.08.19", engine.version)
        } finally { transfer.cancelAndJoin(); second?.cancelAndJoin() }
    }

    @Test fun concurrentUpdatesCoalesceAndTransferCannotEnterUntilUpdateFinishes() = runBlocking {
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        val updating = async { maintenance.updateNow() }
        withTimeout(5000) { maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        assertEquals(EngineUpdateStatus.UPDATING, maintenance.state.value.status)
        assertFalse(maintenance.updateNow())
        var transferEntered = false
        val transfer = launch { maintenance.withDownload { transferEntered = true } }
        yield(); assertFalse(transferEntered)
        engine.hold!!.complete(Unit)
        assertTrue(updating.await())
        transfer.join(); assertTrue(transferEntered)
    }

    @Test fun inspectionDefersMaintenanceWithoutConsumingStartupAttempt() = runBlocking {
        val engine = UpdatingEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        val entered = CompletableDeferred<Unit>()
        val inspection = launch { maintenance.withInspection { entered.complete(Unit); awaitCancellation() } }
        try {
            withTimeout(2000) { entered.await() }
            assertFalse(maintenance.checkOnStartup())
            assertEquals("2025.11.12", engine.version)
            assertFalse(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
        } finally { withTimeout(2000) { inspection.cancelAndJoin() } }
        assertTrue(maintenance.checkOnStartup())
    }

    @Test fun inspectionWaitsForMaintenanceFailureAndCancellationDoesNotLeakPermit() = runBlocking {
        val engine = UpdatingEngine().apply { hold = CompletableDeferred(); fail = true }
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        val update = async { maintenance.updateNow() }
        withTimeout(2000) { maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        var entered = 0
        val cancelled = launch { maintenance.withInspection { entered++ } }
        yield()
        withTimeout(2000) { cancelled.cancelAndJoin() }
        val inspection = async { maintenance.withInspection { entered++; engine.installedVersion() } }
        try {
            assertNull(withTimeoutOrNull(100) { inspection.await() })
            assertEquals(0, entered)
        } finally { engine.hold!!.complete(Unit) }
        assertFalse(withTimeout(2000) { update.await() })
        assertEquals("2026.08.19", withTimeout(2000) { inspection.await() })
        assertEquals(1, entered)
        engine.fail = false
        assertTrue(maintenance.updateNow()) // No leaked inspection permits after the failed updater/cancel.
    }

    @Test fun slowHealthyMaintenanceOutlastsOldReadinessWindowThenAdmitsInspection() = runBlocking {
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        // Scale the old 90-second deadline to 90 ms; a healthy update completes at ~110 ms.
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day }, nativeWaitMs = 90)
        val update = async { maintenance.updateNow() }
        withTimeout(2000) { maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        var waiting = false
        var entered = false
        val inspection = async { maintenance.withInspection(waiting = { waiting = it }) {
            entered = true
            engine.installedVersion()
        } }
        try {
            assertNull(withTimeoutOrNull(110) { inspection.await() })
            assertTrue(waiting)
            assertFalse(entered)
            assertEquals(EngineUpdateStatus.UPDATING, maintenance.state.value.status)
        } finally { engine.hold!!.complete(Unit) }
        assertTrue(withTimeout(2000) { update.await() })
        assertEquals("2026.08.19", withTimeout(2000) { inspection.await() })
        assertFalse(waiting)
        assertTrue(entered)
    }

    @Test fun waitingInspectionAndDownloadCanCancelBeyondOldReadinessWindow() = runBlocking {
        val engine = UpdatingEngine().apply { hold = CompletableDeferred() }
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day }, nativeWaitMs = 90)
        val update = async { maintenance.updateNow() }
        withTimeout(2000) { maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        var entered = false
        val inspection = launch { maintenance.withInspection { entered = true } }
        val download = launch { maintenance.withDownload { entered = true } }
        try {
            assertNull(withTimeoutOrNull(150) { inspection.join(); true })
            assertFalse(entered)
            withTimeout(2000) { inspection.cancelAndJoin(); download.cancelAndJoin() }
            assertFalse(entered)
            assertFalse(update.isCompleted)
        } finally { engine.hold!!.complete(Unit); withTimeout(2000) { update.await() } }
        assertTrue(maintenance.updateNow()) // Cancellations cannot leak shared permits.
    }

    @Test fun rolledBackUpdateIsDurableInSettingsStateUntilALaterSuccess() = runBlocking {
        val engine = UpdatingEngine().apply { rolledBack = true }
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        assertFalse(maintenance.updateNow())
        assertEquals(EngineUpdateStatus.FAILED, maintenance.state.value.status)
        assertTrue(maintenance.state.value.usingBuiltIn)
        assertEquals("2025.11.12", maintenance.state.value.version)
        assertTrue(EngineMaintenance(app, engine, { false }, { 20 * day }).state.value.usingBuiltIn)
        engine.rolledBack = false
        assertTrue(maintenance.updateNow())
        assertFalse(maintenance.state.value.usingBuiltIn)
        assertFalse(EngineMaintenance(app, engine, { false }, { 20 * day }).state.value.usingBuiltIn)
    }

    private class EarlyEngine : EngineAdapter by FakeEngine() {
        var version = "2025.11.12"
        var fail = false
        var runs = 0
        var prepares = 0
        var blockNetwork = false
        val fetching = CompletableDeferred<Unit>()
        val fetchCancelled = CompletableDeferred<Unit>()
        override suspend fun installedVersion() = version
        override suspend fun prepareYtdlpUpdate(): PreparedUpdate {
            prepares++
            if (!fetching.isCompleted) fetching.complete(Unit)
            if (blockNetwork) try { awaitCancellation() } catch (error: CancellationException) {
                if (!fetchCancelled.isCompleted) fetchCancelled.complete(Unit)
                throw error
            }
            return PreparedUpdate.Unstaged
        }
        override suspend fun updateYtdlp(): EngineUpdateResult {
            runs++
            if (fail) return EngineUpdateResult.Failure(DownloadError.Network)
            version = "2026.08.19"
            return EngineUpdateResult.Success("2025.11.12", version)
        }
    }
    private val prefs get() = app.getSharedPreferences("engine-maintenance", 0)

    @Test fun earlyUpdateRunsDespiteASuccessThreeDaysAgoAndResetsTheWeeklyCadence() = runBlocking {
        val now = 20 * day
        prefs.edit().putLong("success", now - 3 * day).putLong("attempt", now).putBoolean("using-built-in", true).commit()
        val engine = EarlyEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        assertTrue(maintenance.state.value.usingBuiltIn)
        assertTrue(maintenance.updateAfterBreakage())
        assertEquals(1, engine.runs)
        assertEquals("2026.08.19", engine.version)
        assertEquals(now, prefs.getLong("success", -1))
        assertEquals(now, prefs.getLong("attempt", -1)) // Weekly stamp is not rewritten.
        assertEquals(now, prefs.getLong("early-attempt", -1))
        assertFalse(maintenance.state.value.usingBuiltIn)
        assertFalse(prefs.contains("using-built-in"))
        val later = EarlyEngine()
        assertFalse(EngineMaintenance(app, later, { false }, { now }).checkOnStartup())
        assertEquals(0, later.runs)
    }

    @Test fun secondEarlyAttemptWithinADayDoesNothing() = runBlocking {
        var now = 20 * day
        val engine = EarlyEngine().apply { fail = true }
        assertFalse(EngineMaintenance(app, engine, { false }, { now }).updateAfterBreakage())
        assertEquals(1, engine.runs)
        assertEquals(now, prefs.getLong("early-attempt", -1))
        val again = EarlyEngine()
        assertFalse(EngineMaintenance(app, again, { false }, { now + day - 1 }).updateAfterBreakage())
        assertEquals(0, again.runs)
        assertFalse(prefs.contains("attempt"))
        now += day
        val later = EarlyEngine()
        assertTrue(EngineMaintenance(app, later, { false }, { now }).updateAfterBreakage())
        assertEquals(1, later.runs)
    }

    @Test fun earlyUpdateRunsWithAPendingLinkOnFirstLaunch() = runBlocking {
        prefs.edit().clear().commit()
        val engine = EarlyEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { 20 * day })
        maintenance.setPendingLink(true)
        assertTrue(maintenance.updateAfterBreakage())
        assertEquals(1, engine.runs)
        assertTrue(prefs.contains("early-attempt"))
    }

    @Test fun queuedDownloadBlocksEarlyUpdateWithoutConsumingTheStamp() = runBlocking {
        var queued = true
        val engine = EarlyEngine()
        val maintenance = EngineMaintenance(app, engine, { queued }, { 20 * day })
        assertFalse(maintenance.updateAfterBreakage())
        assertEquals(EngineUpdateStatus.BUSY, maintenance.state.value.status)
        assertEquals(0, engine.prepares)
        assertEquals(0, engine.runs)
        assertFalse(prefs.contains("early-attempt"))
        queued = false
        assertTrue(maintenance.updateAfterBreakage())
        assertEquals(1, engine.runs)
    }

    @Test fun earlyUpdateSkipsWhenSuccessIsUnderADayOld() = runBlocking {
        val now = 20 * day
        prefs.edit().putLong("success", now - 3_600_000).commit()
        val engine = EarlyEngine()
        assertFalse(EngineMaintenance(app, engine, { false }, { now }).updateAfterBreakage())
        assertEquals(0, engine.prepares)
        assertEquals(0, engine.runs)
        assertFalse(prefs.contains("early-attempt"))
        assertEquals(now - 3_600_000, prefs.getLong("success", -1))
    }

    @Test fun earlyStampIsWrittenBeforeNetworkSoProcessDeathDoesNotRetry() = runBlocking {
        val now = 20 * day
        val engine = EarlyEngine().apply { blockNetwork = true }
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        val job = launch { maintenance.updateAfterBreakage() }
        withTimeout(2000) { engine.fetching.await() }
        assertEquals(now, prefs.getLong("early-attempt", -1))
        assertFalse(prefs.contains("attempt"))
        job.cancelAndJoin()
        withTimeout(2000) { engine.fetchCancelled.await() }
        assertEquals(now, prefs.getLong("early-attempt", -1))
        val restarted = EarlyEngine()
        assertFalse(EngineMaintenance(app, restarted, { false }, { now + day - 1 }).updateAfterBreakage())
        assertEquals(0, restarted.prepares)
        assertEquals(0, restarted.runs)
    }

    @Test fun preemptionRestoresTheEarlyStamp() = runBlocking {
        val now = 20 * day
        val previous = now - 3 * day
        prefs.edit().putLong("early-attempt", previous).commit()
        val engine = EarlyEngine().apply { blockNetwork = true }
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        val update = async { maintenance.updateAfterBreakage() }
        withTimeout(2000) { engine.fetching.await() }
        assertEquals(now, prefs.getLong("early-attempt", -1))
        var entered = false
        withTimeout(5000) { maintenance.withInspection { entered = true } }
        assertTrue(entered)
        withTimeout(2000) { engine.fetchCancelled.await() }
        assertFalse(withTimeout(2000) { update.await() })
        assertEquals(previous, prefs.getLong("early-attempt", -1))
        assertFalse(prefs.contains("attempt"))
        assertFalse(prefs.contains("success"))
        assertEquals(EngineUpdateStatus.IDLE, maintenance.state.value.status)
        assertFalse(maintenance.updateAfterBreakage())
        assertEquals(1, engine.prepares)
        engine.blockNetwork = false
        assertTrue(EngineMaintenance(app, engine, { false }, { now }).updateAfterBreakage())
        assertEquals(2, engine.prepares)
        assertEquals(1, engine.runs)
    }
}
