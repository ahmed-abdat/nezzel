package dev.ahmed.savlino.jobs

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.awaitClose

/** On-demand WorkManager initialization; workers cannot pass the coordinator's startup barrier. */
open class NezzelApplication : Application(), Configuration.Provider {
    val preferences by lazy { dev.ahmed.savlino.locale.AppPreferences(this) }
    private val thermalStatus = MutableStateFlow(android.os.PowerManager.THERMAL_STATUS_NONE)
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { thermalStatus.value = it }
    open val downloads by lazy { DownloadCoordinator(this, parallelDownloads = preferences.parallelDownloadsFlow,
        thermalStatus = thermalStatus, usage = usage) }
    // A failed background step (job store, widget, update schedule) must not close the app; the next launch retries it.
    private val contained = CoroutineExceptionHandler { _, _ -> }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + contained)
    /** Process-lifetime collectors stay out of [scope], so its startup jobs can be joined. */
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.IO + contained)
    /** UI and notifications share a single SQLite poller. */
    val playlistBatches by lazy { downloads.playlistBatches.stateIn(collectors, SharingStarted.WhileSubscribed(5_000), emptyList()) }
    val appUpdates by lazy { dev.ahmed.savlino.update.AppUpdates(this, scope) }
    /** Private inbox for failure/crash reports and the daily usage counts, all only while Help improve Nezzel is on. */
    open val reports by lazy { dev.ahmed.savlino.report.InboxClient() }
    open val usage: dev.ahmed.savlino.usage.UsageEvents by lazy { usageStore }
    internal val usageStore by lazy {
        val crashes = dev.ahmed.savlino.diagnostics.CrashReports.store(this)
        dev.ahmed.savlino.usage.Usage(getSharedPreferences("usage", MODE_PRIVATE), { preferences.helpImprove },
            { path, json -> reports.post(path, json) }, scope, dev.ahmed.savlino.BuildConfig.VERSION_NAME,
            dev.ahmed.savlino.BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT,
            dev.ahmed.savlino.usage.PhoneSnapshot(this, preferences), object : dev.ahmed.savlino.usage.Usage.Crashes {
                override fun pending() = crashes.pending()
                override fun delete() = crashes.deleteReports()
                override fun engineVersion() = downloads.maintenance.state.value.version
            })
    }
    /** Off clears every counter, queued report and the crash file at once; the daily work stays only for what is on. */
    fun chooseHelpImprove(on: Boolean) {
        preferences.helpImprove = on
        // Off: drop counters, queued reports and the crash file. On again: a crash recorded while off is never sent.
        scope.launch { if (on) usageStore.dropCrash() else usageStore.clear() }
        appUpdates.reschedule()
    }
    val widgets by lazy { dev.ahmed.savlino.shortcuts.WidgetUpdater(this, downloads.progress) }
    /** One-shot image Save: no journal or worker; leftovers of a killed Save are swept at start. */
    open val media by lazy { dev.ahmed.savlino.media.MediaServices(this, { downloads.engine }, scope, usage) }
    /** What the link screens use from [media]; tests swap in fakes here. */
    internal open val imageInspect: dev.ahmed.savlino.media.ImageInspect get() = media.inspect
    internal open val imageSaver: dev.ahmed.savlino.media.ImageSaver get() = media.saver
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
        // Before anything writes settings: the first start of an install decides the old-downloads offer.
        dev.ahmed.savlino.storage.OldDownloads.markFirstStart(this)
        // The heat guard is optional: a missing thermal service ("Listener failed to set") must not stop startup.
        runCatching {
            getSystemService(android.os.PowerManager::class.java)?.let { power ->
                thermalStatus.value = power.currentThermalStatus
                power.addThermalStatusListener(mainExecutor, thermalListener)
            }
        }
        dev.ahmed.savlino.diagnostics.CrashReports.install(this)
        dev.ahmed.savlino.DebugStrictMode.install()
        collectors.launch { widgets.start(this) }
        collectors.launch {
            downloads.initialize()
            downloads.batchFeeder.start(this,
                downloads.progress.map { rows -> rows.map { Triple(it.jobId, it.phase, it.paused) } }.distinctUntilChanged(),
                batchConnectivity())
        }
        collectors.launch {
            downloads.initialize()
            val notifications = DownloadNotifications(this@NezzelApplication)
            playlistBatches.collect { batches -> batches.forEach(notifications::playlist) }
        }
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
    private fun batchConnectivity() = callbackFlow {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                trySend(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            override fun onLost(network: android.net.Network) { trySend(false) }
        }
        val registered = try { manager?.registerDefaultNetworkCallback(callback); manager != null }
            catch (_: RuntimeException) { false }
        awaitClose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

}
