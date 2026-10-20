package dev.ahmed.savlino.update

import android.content.Context
import android.content.Intent
import androidx.work.*
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.locale.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, ERROR }
enum class UpdateFailure { NETWORK, SPACE, VERIFY, INSTALL }
enum class UpdateCheck { NONE, CHECKING, LATEST, AVAILABLE, FAILED }
data class AppUpdateState(val automatic: Boolean = true, val release: Release? = null, val skipped: Long? = null,
    val dismissed: Boolean = false, val check: UpdateCheck = UpdateCheck.NONE, val stage: UpdateStage = UpdateStage.IDLE,
    val bytes: Long = 0, val failure: UpdateFailure? = null, val transfer: Release? = null,
    val confirmation: Intent? = null) {
    fun card(current: Long) = !dismissed && release?.let { UpdatePolicy.offer(it.code, current, skipped) } == true
}

/** Process-owned work; no Activity or file survives as an authorization to install. */
class AppUpdates internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val httpFactory: () -> UpdateHttp) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, { UpdateHttp(BuildConfig.VERSION_NAME) })
    val available = UpdatePolicy.enabled(context.packageName)
    internal val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val appPrefs = AppPreferences(context)
    private val mutable = MutableStateFlow(AppUpdateState(automatic = appPrefs.appUpdates,
        release = if (available) prefs.getString("release", null)?.toByteArray()?.let { bytes ->
            when (prefs.getString("release-format", null)) {
                "manifest" -> Release.parseManifest(bytes)
                "api", null -> Release.parse(bytes)
                else -> null
            }
        }
            ?.takeIf { it.code > BuildConfig.VERSION_CODE } else null,
        skipped = prefs.getLong("skipped", -1).takeIf { it > 0 }))
    val state: StateFlow<AppUpdateState> = mutable.asStateFlow()
    private val checkGate = Mutex()
    private val filesGate = Mutex()
    private var transfer: Job? = null
    private var installation: Job? = null
    @Volatile private var http: UpdateHttp? = null
    internal val directory get() = File(context.cacheDir, "updates")
    internal var readyFile: File? = null; private set
    /** Activity calls once per process startup; a cold worker/receiver must not cause another check. */
    private var started = false
    private var swept = false
    private fun sweepOnce() { if (!swept) { cleanFiles(); swept = true } }
    @Synchronized fun start() {
        if (!available || started) return
        started = true
        schedule()
        scope.launch {
            filesGate.withLock { sweepOnce() }
            check(manual = false)
        }
    }
    @Synchronized fun setAutomatic(value: Boolean) {
        if (!available) return
        appPrefs.appUpdates = value
        mutable.update { it.copy(automatic = value) }
        schedule()
        if (!value) UpdateNotifications(context).cancel()
    }
    private fun schedule() {
        val work = WorkManager.getInstance(context)
        if (!available || !appPrefs.appUpdates) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build())
    }
    fun checkNow() { scope.launch { check(manual = true) } }
    suspend fun check(manual: Boolean, notify: Boolean = false) = checkGate.withLock {
        if (!available || (!manual && !appPrefs.appUpdates)) return@withLock
        if (!manual && prefs.contains("success") && System.currentTimeMillis() - prefs.getLong("success", 0) <= DAY) {
            if (notify) postNotification()
            return@withLock
        }
        if (manual) mutable.update { it.copy(check = UpdateCheck.CHECKING) }
        try {
            val (parsed, bytes, format) = withContext(Dispatchers.IO) { httpFactory().use { it.latestRelease() } }
            val release = parsed.takeIf { it.code > BuildConfig.VERSION_CODE }
            if (!prefs.edit().putLong("success", System.currentTimeMillis()).putString("release", bytes.toString(Charsets.UTF_8))
                    .putString("release-format", format).commit())
                throw java.io.IOException()
            mutable.update { it.copy(release = release, dismissed = if (it.release?.code == release?.code) it.dismissed else false,
                check = if (manual) { if (release == null) UpdateCheck.LATEST else UpdateCheck.AVAILABLE } else it.check) }
            if (notify) postNotification()
        } catch (cancelled: CancellationException) {
            if (manual) mutable.update { it.copy(check = UpdateCheck.NONE) }
            throw cancelled
        }
        catch (_: Exception) { if (manual) mutable.update { it.copy(check = UpdateCheck.FAILED) } }
    }
    @Synchronized internal fun postNotification() {
        val release = state.value.release ?: return
        if (UpdatePolicy.notify(context.packageName, appPrefs.appUpdates, release.code, BuildConfig.VERSION_CODE.toLong(),
                state.value.skipped, prefs.getLong("notified", -1)) && UpdateNotifications(context).post(release))
            prefs.edit().putLong("notified", release.code).commit()
    }
    fun dismiss() { mutable.update { it.copy(dismissed = true) } }
    @Synchronized fun skip(code: Long) {
        if (!available) return
        prefs.edit().putLong("skipped", code).commit()
        mutable.update { it.copy(skipped = code) }
        UpdateNotifications(context).cancel()
    }
    @Synchronized fun install() {
        if (installation?.isActive == true) return
        installation = scope.launch { UpdateInstaller.install(context, this@AppUpdates) }
    }
    fun download() {
        if (!available || transfer?.isActive == true || state.value.stage == UpdateStage.INSTALLING) return
        val release = state.value.release ?: return
        transfer = scope.launch(Dispatchers.IO) {
            filesGate.withLock {
                sweepOnce()
                cleanFiles()
                mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, transfer = release, failure = null, bytes = 0) }
                var file: File? = null
                try {
                    if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException()
                    if (directory.usableSpace < release.size + MARGIN) { fail(UpdateFailure.SPACE); return@withLock }
                    file = File(directory, release.name + ".part")
                    httpFactory().use { client ->
                        http = client
                        client.download(release, file) { bytes -> mutable.update { it.copy(bytes = bytes) } }
                    }
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(stage = UpdateStage.VERIFYING) }
                    if (!UpdateVerifier.verify(context, file, release)) { fail(UpdateFailure.VERIFY); return@withLock }
                    currentCoroutineContext().ensureActive()
                    readyFile = file
                    mutable.update { it.copy(stage = UpdateStage.READY) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    fail(if (isNoSpace(failure)) UpdateFailure.SPACE else UpdateFailure.NETWORK)
                }
                finally { http = null; if (readyFile != file) file?.delete() }
            }
        }
    }
    fun cancel() {
        val cancelled = transfer
        cancelled?.cancel()
        // close() evicts TLS connections, which is network I/O: keep it off the main thread.
        http?.let { client -> scope.launch(Dispatchers.IO) { client.close() } }
        mutable.update { it.copy(stage = UpdateStage.IDLE, failure = null, bytes = 0) }
        scope.launch {
            cancelled?.join()
            filesGate.withLock { if (transfer === cancelled) cleanFiles() }
        }
    }
    internal fun fail(failure: UpdateFailure) {
        cleanFiles()
        mutable.update { it.copy(stage = UpdateStage.ERROR, failure = failure, confirmation = null) }
    }
    internal fun holdConfirmation(intent: Intent) { mutable.update { it.copy(stage = UpdateStage.INSTALLING, confirmation = intent) } }
    internal fun takeConfirmation(): Intent? = mutable.getAndUpdate { it.copy(confirmation = null) }.confirmation
    internal fun installing() { mutable.update { it.copy(stage = UpdateStage.INSTALLING) } }
    internal fun cleanFiles() { readyFile = null; directory.listFiles()?.forEach { it.delete() } }
    fun consumeUpdated(): Boolean {
        if (!available || prefs.getLong("install-code", -1) != BuildConfig.VERSION_CODE.toLong()) return false
        cleanFiles()
        return prefs.edit().remove("install-code").remove("install-session").commit()
    }
    private fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.take(10).any {
        it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC || it.message?.contains("ENOSPC") == true
    }
    companion object { const val WORK = "app-updates"; private const val DAY = 86_400_000L; internal const val MARGIN = 64L * 1024 * 1024 }
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!UpdatePolicy.enabled(applicationContext.packageName)) return Result.success()
        val updates = (applicationContext as dev.ahmed.savlino.jobs.NezzelApplication).appUpdates
        updates.check(manual = false, notify = true)
        return Result.success()
    }
}
