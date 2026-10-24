package dev.ahmed.savlino.jobs

import android.content.Context
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.engine.EngineUpdateResult
import dev.ahmed.savlino.engine.EngineReadiness
import dev.ahmed.savlino.engine.PreparedUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EngineUpdateStatus { IDLE, UPDATING, SUCCEEDED, FAILED, BUSY }
data class EngineMaintenanceState(val version: String? = null, val everSucceeded: Boolean = false,
    val status: EngineUpdateStatus = EngineUpdateStatus.IDLE, val generation: Long = 0,
    /** The last update failed its self-test and the bundled yt-dlp was restored. Durable until a success. */
    val usingBuiltIn: Boolean = false)

/** Process owner. Cadence is durable; inspections and downloads exclude the exclusive updater. */
class EngineMaintenance(context: Context, private val engine: EngineAdapter,
    private val hasDownloads: () -> Boolean, private val now: () -> Long = System::currentTimeMillis,
    private val nativeWaitMs: Long = 90_000,
    /** Downloads that would lose more than their partial bytes: everything except paused rows. */
    private val hasRunningDownloads: () -> Boolean = hasDownloads) {
    private val prefs = context.getSharedPreferences("engine-maintenance", Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val changes = MutableStateFlow(0L)
    @Volatile private var pendingLink = false
    @Volatile private var clipboardChecked = false
    private var launchRecorded = false
    private var firstLaunch = true
    private var inspectionFinished = false
    /** The automatic update's network stage, run outside [gate]; admission cancels it. Accessed only under gate. */
    private var preemptible: Job? = null
    /** A link pre-empted this process's automatic update; the next launch retries under the usual cadence. */
    private var preempted = false
    /** Local discovery only; publish startup warm-up's installed version without attempting an update. */
    suspend fun refreshVersion() = withContext(Dispatchers.IO) {
        gate.withLock {
            try { mutableState.value = mutableState.value.copy(version = engine.installedVersion()?.takeIf(String::isNotBlank)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unknown remains honest; this is not a failed maintenance attempt. */ }
        }
    }
    /** Boolean only: unaccepted URLs never leave the ViewModel. */
    fun setPendingLink(pending: Boolean) {
        pendingLink = pending
        changes.update { it + 1 }
    }
    /** One foreground intake decision per process, including a guarded/disabled clipboard read. */
    fun foregroundClipboardChecked() {
        clipboardChecked = true
        changes.update { it + 1 }
    }
    /** Called even while onboarding is showing; no network or version process. */
    suspend fun onLaunch() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!launchRecorded) {
                firstLaunch = !prefs.getBoolean("launched", false) && !prefs.contains("success")
                prefs.edit().putBoolean("launched", true).commit()
                launchRecorded = true
                changes.update { it + 1 }
            }
        }
    }
    private fun inspectionBusy() = pendingLink || inspections > 0 || (firstLaunch && !inspectionFinished)
    /** Wait for the first attempt/pending intake; download and cadence deferrals still end this launch's check. */
    suspend fun checkWhenInspectionIdle(): Boolean {
        onLaunch()
        while (true) {
            val revision = changes.value
            if (clipboardChecked && gate.withLock { !inspectionBusy() }) {
                val decision = update(false)
                if (decision != UpdateDecision.INSPECTION_BUSY) return decision == UpdateDecision.UPDATED
            }
            changes.first { it != revision }
        }
    }
    private var inspections = 0 // Accessed only under gate; includes inspection version/generation capture.
    private var downloads = 0 // Accessed only under gate; includes admission and the full transfer lifetime.
    private val mutableState = MutableStateFlow(EngineMaintenanceState(everSucceeded = prefs.contains("success"),
        usingBuiltIn = prefs.getBoolean(BUILT_IN, false)))
    val state = mutableState.asStateFlow()
    suspend fun <T> withDownload(block: suspend () -> T): T {
        var admitted = false
        try {
            admit { downloads++; admitted = true }
            return EngineReadiness.within(nativeWaitMs, block)
        } finally {
            if (admitted) withContext(NonCancellable) { gate.withLock { downloads-- } }
        }
    }
    suspend fun <T> withInspection(waiting: (Boolean) -> Unit = {}, block: suspend () -> T): T {
        var admitted = false
        try {
            waiting(true)
            try { admit { inspections++; admitted = true } } finally { waiting(false) }
            return EngineReadiness.within(nativeWaitMs, block)
        } finally {
            if (admitted) withContext(NonCancellable) { gate.withLock {
                inspections--
                inspectionFinished = true
                changes.update { it + 1 }
            } }
        }
    }
    private suspend fun admit(register: () -> Unit) {
        // Manual maintenance and every install keep this gate, including the version refresh; healthy updates
        // may exceed a media readiness deadline. Wait for release, cancellably, then start that deadline.
        // An automatic update's network stage runs without it: a link or download cancels that stage at once.
        gate.withLock {
            preemptible?.cancel()
            register()
        }
    }
    private enum class UpdateDecision { UPDATED, NO_UPDATE, INSPECTION_BUSY, PREEMPTED }
    suspend fun checkOnStartup(): Boolean = update(false) == UpdateDecision.UPDATED
    /** [includePaused]: the user confirmed that paused downloads start over (their resume recipe pins the engine). */
    suspend fun updateNow(includePaused: Boolean = false): Boolean = update(true, includePaused) == UpdateDecision.UPDATED
    private suspend fun update(manual: Boolean, includePaused: Boolean = false): UpdateDecision = withContext(Dispatchers.IO) {
        if (!manual) onLaunch()
        if (!gate.tryLock()) return@withContext busy()
        var locked = true
        if (preemptible != null) { gate.unlock(); return@withContext busy() } // Automatic network stage running.
        try {
            if (downloads > 0 || (if (manual && includePaused) hasRunningDownloads() else hasDownloads())) {
                mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.BUSY)
                return@withContext UpdateDecision.NO_UPDATE
            }
            if (inspections > 0) {
                mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.BUSY)
                return@withContext if (manual) UpdateDecision.NO_UPDATE else UpdateDecision.INSPECTION_BUSY
            }
            if (!manual && inspectionBusy()) return@withContext UpdateDecision.INSPECTION_BUSY
            mutableState.value = mutableState.value.copy(version = engine.installedVersion())
            // Version discovery can await first extraction. A link arriving meanwhile wins over automatic maintenance.
            if (!manual && inspectionBusy()) return@withContext UpdateDecision.INSPECTION_BUSY
            val time = now()
            if (!manual && (preempted || (prefs.contains("success") && time - prefs.getLong("success", 0) < 7 * DAY) ||
                    (prefs.contains("attempt") && time - prefs.getLong("attempt", 0) < DAY))) return@withContext UpdateDecision.NO_UPDATE
            val previousAttempt = if (prefs.contains("attempt")) prefs.getLong("attempt", 0) else null
            // Persist before network work so process death cannot cause an automatic retry loop.
            if (!prefs.edit().putLong("attempt", time).commit()) {
                mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.FAILED)
                return@withContext UpdateDecision.NO_UPDATE
            }
            mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.UPDATING,
                generation = mutableState.value.generation + 1)
            var succeeded = false
            var preemptedNow = false
            try {
                val result = if (manual) engine.updateYtdlp() else {
                    // Network stage without the gate, so admission can cancel it; the install re-takes the gate.
                    val stage = async {
                        try { engine.prepareYtdlpUpdate() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { PreparedUpdate.Failed(EngineUpdateResult.Failure(DownloadError.Failed)) }
                    }
                    preemptible = stage
                    gate.unlock(); locked = false
                    stage.join()
                    withContext(NonCancellable) { gate.lock(); locked = true }
                    preemptible = null
                    val prepared = if (stage.isCancelled) null else stage.await()
                    if (prepared == null || inspections > 0 || downloads > 0 || hasDownloads()) {
                        prepared?.let(engine::discardYtdlpUpdate)
                        // A pre-empted attempt is not an attempt: restore the stamp, never retry in this process.
                        prefs.edit().apply { if (previousAttempt == null) remove("attempt") else putLong("attempt", previousAttempt) }.commit()
                        preempted = true; preemptedNow = true
                        mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.IDLE)
                        return@withContext UpdateDecision.PREEMPTED
                    }
                    // Unstaged: the engine has no separate network stage, so the whole update runs under the gate.
                    if (prepared is PreparedUpdate.Unstaged) engine.updateYtdlp() else engine.installYtdlpUpdate(prepared)
                }
                succeeded = result is EngineUpdateResult.Success
                if (succeeded) succeeded = prefs.edit().putLong("success", now()).remove(BUILT_IN).commit()
                else if ((result as? EngineUpdateResult.Failure)?.usingBuiltIn == true) prefs.edit().putBoolean(BUILT_IN, true).commit()
            } finally {
                // A failed updater can still replace the binary. Refresh even on cancellation.
                if (!preemptedNow) withContext(NonCancellable) {
                    val version = try { engine.installedVersion() } catch (_: Exception) { null }
                    mutableState.value = EngineMaintenanceState(version, prefs.contains("success"),
                        if (succeeded) EngineUpdateStatus.SUCCEEDED else EngineUpdateStatus.FAILED, mutableState.value.generation,
                        prefs.getBoolean(BUILT_IN, false))
                }
            }
            if (succeeded) UpdateDecision.UPDATED else UpdateDecision.NO_UPDATE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.FAILED)
            UpdateDecision.NO_UPDATE // Raw updater diagnostics can contain URLs; keep only a localized Settings result.
        } finally {
            // Unlocked only when this caller was cancelled during the network stage, which cancels that child too.
            if (locked) gate.unlock() else withContext(NonCancellable) { gate.withLock { preemptible = null } }
        }
    }
    private fun busy(): UpdateDecision {
        if (mutableState.value.status != EngineUpdateStatus.UPDATING)
            mutableState.value = mutableState.value.copy(status = EngineUpdateStatus.BUSY)
        return UpdateDecision.NO_UPDATE
    }
    private companion object { const val DAY = 86_400_000L; const val BUILT_IN = "using-built-in" }
}
