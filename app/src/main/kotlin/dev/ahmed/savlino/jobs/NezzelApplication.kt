package dev.ahmed.savlino.jobs

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.*

/** On-demand WorkManager initialization; workers cannot pass the coordinator's startup barrier. */
open class NezzelApplication : Application(), Configuration.Provider {
    open val downloads by lazy { DownloadCoordinator(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appUpdates by lazy { dev.ahmed.savlino.update.AppUpdates(this, scope) }
    /** One-shot image Save: no journal or worker; leftovers of a killed Save are swept at start. */
    open val media by lazy { dev.ahmed.savlino.media.MediaServices(this, { downloads.engine }, scope) }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(DownloadWorker.Factory(downloads)).build()
    private var startupMaintenance: Job? = null
    /** Activity opens this gate after the tour; restored replays also start without network work. */
    @Synchronized open fun startMaintenanceAfterOnboarding() {
        if (startupMaintenance != null) return
        startupMaintenance = scope.launch { downloads.start(scope).join(); downloads.maintenance.checkWhenInspectionIdle() }
    }
    override fun onCreate() {
        super.onCreate()
        dev.ahmed.savlino.diagnostics.CrashReports.install(this)
        dev.ahmed.savlino.DebugStrictMode.install()
        scope.launch { downloads.maintenance.onLaunch(); downloads.start(scope).join() }
        scope.launch {
            try {
                if (downloads.engine.isAvailable && downloads.engine.warmUp()) downloads.maintenance.refreshVersion()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Inspection owns the localized failure/retry; no raw diagnostics. */ }
        }
        scope.launch { media.saver.sweep() }
    }
}
