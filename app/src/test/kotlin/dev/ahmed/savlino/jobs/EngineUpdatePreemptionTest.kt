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

/** A link or confirmed download never waits for the automatic update's network stage (lead 2026-10-23). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EngineUpdatePreemptionTest {
    private class StagedEngine : EngineAdapter by FakeEngine() {
        var version = "2025.11.12"
        var fetching = CompletableDeferred<Unit>()
        var releaseFetch = CompletableDeferred<Unit>()
        var prepared: PreparedUpdate = PreparedUpdate.Unstaged
        var onFetched: () -> Unit = {}
        var prepares = 0; var installs = 0; var discards = 0; var fullUpdates = 0
        var fetchCancelled = false
        var holdManual: CompletableDeferred<Unit>? = null
        override suspend fun installedVersion() = version
        override suspend fun prepareYtdlpUpdate(): PreparedUpdate {
            prepares++
            fetching.complete(Unit)
            try { releaseFetch.await() } catch (cancelled: CancellationException) { fetchCancelled = true; throw cancelled }
            onFetched()
            return prepared
        }
        override suspend fun installYtdlpUpdate(prepared: PreparedUpdate): EngineUpdateResult {
            if (prepared is PreparedUpdate.Failed) return prepared.failure
            installs++
            version = "2026.10.21"
            return EngineUpdateResult.Success("2025.11.12", version)
        }
        override fun discardYtdlpUpdate(prepared: PreparedUpdate) { discards++ }
        override suspend fun updateYtdlp(): EngineUpdateResult {
            fullUpdates++
            holdManual?.await()
            version = "2026.10.21"
            return EngineUpdateResult.Success("2025.11.12", version)
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("engine-maintenance", 0)
    private val day = 86_400_000L
    private val now = 20 * day
    @org.junit.Before fun establishedInstall() { prefs.edit().putBoolean("launched", true).commit() }

    private suspend fun preempt(admit: suspend (EngineMaintenance, () -> Unit) -> Unit, previousAttempt: Long?) = coroutineScope {
        previousAttempt?.let { prefs.edit().putLong("attempt", it).commit() }
        val engine = StagedEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        val update = async { maintenance.checkOnStartup() }
        withTimeout(2000) { engine.fetching.await() }
        assertEquals(now, prefs.getLong("attempt", -1)) // Stamped before network, as before.
        assertEquals(EngineUpdateStatus.UPDATING, maintenance.state.value.status)
        var entered = false
        // The network stage never finishes on its own: admission within 1 s means it did not wait for it.
        withTimeout(1000) { admit(maintenance) { entered = true } }
        assertTrue(entered)
        assertFalse(withTimeout(1000) { update.await() })
        assertTrue(engine.fetchCancelled)
        assertEquals(0, engine.installs); assertEquals(0, engine.fullUpdates)
        assertEquals("2025.11.12", engine.installedVersion()) // Old runtime untouched.
        if (previousAttempt == null) assertFalse(prefs.contains("attempt"))
        else assertEquals(previousAttempt, prefs.getLong("attempt", -1))
        assertFalse(prefs.contains("success"))
        assertEquals(EngineUpdateStatus.IDLE, maintenance.state.value.status)
        // At most one automatic attempt per launch.
        assertFalse(maintenance.checkOnStartup())
        assertEquals(1, engine.prepares)
        // The next launch retries under the usual cadence.
        engine.releaseFetch.complete(Unit)
        assertTrue(EngineMaintenance(app, engine, { false }, { now }).checkOnStartup())
        assertEquals(2, engine.prepares); assertEquals(1, engine.fullUpdates) // Unstaged fake: the whole update runs under the gate.
        assertEquals(now, prefs.getLong("attempt", -1))
    }

    @Test fun inspectionCancelsAutomaticUpdateAndIsAdmittedAtOnce() = runBlocking {
        preempt({ maintenance, enter -> maintenance.withInspection { enter() } }, previousAttempt = null)
    }

    @Test fun confirmedDownloadCancelsAutomaticUpdateAndRestoresTheEarlierAttempt() = runBlocking {
        preempt({ maintenance, enter -> maintenance.withDownload { enter() } }, previousAttempt = now - 3 * day)
    }

    @Test fun downloadQueuedWhenTheFetchEndsDropsTheStagedReleaseWithoutInstalling() = runBlocking {
        var queued = false
        val engine = StagedEngine().apply { onFetched = { queued = true }; releaseFetch.complete(Unit) }
        val maintenance = EngineMaintenance(app, engine, { queued }, { now })
        assertFalse(maintenance.checkOnStartup())
        assertEquals(1, engine.discards); assertEquals(0, engine.installs)
        assertEquals("2025.11.12", engine.installedVersion())
        assertFalse(prefs.contains("attempt"))
        assertEquals(EngineUpdateStatus.IDLE, maintenance.state.value.status)
    }

    @Test fun failedVerificationCountsAsAnAttempt() = runBlocking {
        val engine = StagedEngine().apply {
            prepared = PreparedUpdate.Failed(EngineUpdateResult.Failure(DownloadError.Failed)); releaseFetch.complete(Unit)
        }
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        assertFalse(maintenance.checkOnStartup())
        assertEquals(EngineUpdateStatus.FAILED, maintenance.state.value.status)
        assertEquals(now, prefs.getLong("attempt", -1))
        assertEquals("2025.11.12", maintenance.state.value.version)
        assertFalse(EngineMaintenance(app, engine, { false }, { now + day / 2 }).checkOnStartup())
        assertEquals(1, engine.prepares)
    }

    @Test fun manualUpdateDuringAutomaticNetworkStageIsBusy() = runBlocking {
        val engine = StagedEngine()
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        val update = async { maintenance.checkOnStartup() }
        withTimeout(2000) { engine.fetching.await() }
        assertFalse(maintenance.updateNow())
        assertEquals(0, engine.fullUpdates)
        engine.releaseFetch.complete(Unit)
        assertTrue(withTimeout(2000) { update.await() })
        assertEquals(1, engine.fullUpdates)
    }

    @Test fun manualUpdateStillHoldsInspectionsUntilItEnds() = runBlocking {
        val engine = StagedEngine().apply { holdManual = CompletableDeferred() }
        val maintenance = EngineMaintenance(app, engine, { false }, { now })
        val update = async { maintenance.updateNow() }
        withTimeout(2000) { maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
        var entered = false
        val inspection = async { maintenance.withInspection { entered = true } }
        try {
            assertNull(withTimeoutOrNull(300) { inspection.await() })
            assertFalse(entered)
        } finally { engine.holdManual!!.complete(Unit) }
        assertTrue(withTimeout(2000) { update.await() })
        withTimeout(2000) { inspection.await() }
        assertTrue(entered)
        assertEquals(0, engine.prepares); assertEquals(1, engine.fullUpdates)
    }
}
