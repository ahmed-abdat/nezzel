package dev.ahmed.savlino.update

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.storage.StorageManager
import androidx.work.*
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.locale.AppPreferences
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal fun updateCheckJitterMillis(random: Random = Random.Default): Long = random.nextLong(TimeUnit.MINUTES.toMillis(30) + 1)
internal fun requiredUpdateSpace(size: Long): Long = 2 * size + AppUpdates.MARGIN
internal fun hasUpdateSpace(context: Context, size: Long, allocatable: () -> Long = {
    val storage = context.getSystemService(StorageManager::class.java)
    storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
}, usable: () -> Long = { context.cacheDir.usableSpace }): Boolean =
    (try { allocatable() } catch (_: Exception) { usable() }) >= requiredUpdateSpace(size)

internal fun canPrepareUpdate(context: Context): Boolean = try {
    val network = context.getSystemService(ConnectivityManager::class.java)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    network.activeNetwork != null &&
        battery != null && !battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, true)
} catch (_: Exception) { false }

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, ERROR }
enum class UpdateFailure { NETWORK, SPACE, VERIFY, INSTALL }
enum class UpdateCheck { NONE, CHECKING, LATEST, AVAILABLE, FAILED }
class WhatsNew(val version: String, val english: List<String>, val arabic: List<String>, val french: List<String> = emptyList()) {
    fun notes(language: String): List<String> = plainUpdateNotes(when {
        language == "ar" && arabic.isNotEmpty() -> arabic
        language == "fr" && french.isNotEmpty() -> french
        else -> english
    })
}
data class AppUpdateState(val automatic: Boolean = true, val release: Release? = null, val skipped: Long? = null,
    val dismissedAt: Long? = null, val check: UpdateCheck = UpdateCheck.NONE, val stage: UpdateStage = UpdateStage.IDLE,
    val bytes: Long = 0, val failure: UpdateFailure? = null, val transfer: Release? = null,
    val confirmation: Intent? = null, val lastChecked: Long? = null, val whatsNew: WhatsNew? = null,
    val offerSheet: Release? = null) {
    fun badge(current: Long) = release?.let { UpdatePolicy.offer(it.code, current, skipped) } == true
    fun card(current: Long, now: Long = System.currentTimeMillis()) =
        badge(current) && (dismissedAt == null || now - dismissedAt >= UpdatePolicy.REMINDER_AGE)
}

/** Process-owned work; no Activity or file survives as an authorization to install. */
class AppUpdates internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis, private val httpFactory: (count: Boolean) -> UpdateHttp) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, httpFactory = { count -> UpdateHttp(BuildConfig.VERSION_NAME, count = count) })
    val available = UpdatePolicy.enabled(context.packageName)
    internal val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val appPrefs = AppPreferences(context)
    private val mutable = MutableStateFlow(AppUpdateState(automatic = appPrefs.appUpdates,
        release = if (available) cachedRelease()?.takeIf { it.code > BuildConfig.VERSION_CODE } else null,
        skipped = prefs.getLong("skipped", -1).takeIf { it > 0 },
        lastChecked = prefs.getLong("success", 0).takeIf { prefs.contains("success") },
        whatsNew = readWhatsNew()))
    val state: StateFlow<AppUpdateState> = mutable.asStateFlow()
    @Volatile private var foreground = false
    private var handledInOpen: Long? = null
    private val bundledNotes by lazy { BundledWhatsNew.read(context.assets) }
    init { currentNotes(); refreshPresentation() } // Discard notes retained by an older build, even before About opens
    private val checkGate = Mutex()
    private val filesGate = Mutex()
    internal val installGate = Mutex()
    private val scheduleLock = Any()
    private var transfer: Job? = null
    private var installation: Job? = null
    /** Set by the silent gate: install did not start only because the app is open or media work is busy. */
    @Volatile private var deferredForPresenceOrWork = false
    @Volatile private var http: UpdateHttp? = null
    internal val directory get() = File(context.cacheDir, "updates")
    internal var readyFile: File? = null; private set
    /** Activity calls once per process startup; a cold worker/receiver must not cause another check. */
    private var started = false
    private var swept = false
    private fun partMatches(release: Release): Boolean = try {
        val identity = JSONObject(File(directory, release.name + ".part.json").readText())
        identity.getLong("code") == release.code && identity.getLong("size") == release.size &&
            identity.getString("sha256").equals(release.digest, ignoreCase = true) &&
            File(directory, release.name + ".part").let { it.isFile && it.length() in 1..release.size }
    } catch (_: Exception) { false }
    /** Must run under filesGate on IO, including when Download precedes start. */
    private fun sweepOnce() {
        if (swept) return
        val release = state.value.release
        val ready = release?.let { File(directory, it.name) }?.takeIf {
            prefs.getLong("ready-code", -1) == release.code && prefs.getString("ready-file", null) == release.name
        }
        val part = release?.takeIf(::partMatches)?.let { setOf(it.name + ".part", it.name + ".part.json") }.orEmpty()
        directory.listFiles()?.filter { it != ready && it.name !in part }?.forEach { it.delete() }
        if (ready != null && UpdateVerifier.verify(context, ready, release)) {
            readyFile = ready
            mutable.update { it.copy(stage = UpdateStage.READY, transfer = release, bytes = release.size) }
        } else {
            ready?.delete()
            prefs.edit().remove("ready-code").remove("ready-file").commit()
        }
        swept = true
    }
    /** Everything runs on [scope]: WorkManager's first start must not hold up a cold launch's shared or copied link. */
    @Synchronized fun start() {
        if (!available || started) return
        started = true
        scope.launch {
            schedule()
            withContext(Dispatchers.IO) { filesGate.withLock { sweepOnce() } }
            check(manual = false)
        }
    }
    @Synchronized fun setAutomatic(value: Boolean) {
        if (!available) return
        appPrefs.appUpdates = value
        mutable.update { it.copy(automatic = value) }
        schedule()
        if (!value) UpdateNotifications(context).cancel()
        refreshPresentation()
    }
    // Reads the switch under the lock, so a toggle during start-up's background schedule still ends in the switch's state.
    private fun schedule(): Unit = synchronized(scheduleLock) {
        val work = WorkManager.getInstance(context)
        if (!available || !appPrefs.appUpdates) work.cancelUniqueWork(CLOSE_WORK)
        // The same periodic work carries the usage send, so it stays while either switch is on.
        if (!available || !appPrefs.appUpdates && !appPrefs.helpImprove) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(updateCheckJitterMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build())
    }
    fun reschedule() { if (available) scope.launch { schedule() } }
    fun checkNow() { scope.launch { check(manual = true) } }
    /** True when a background notify left a ready update uninstalled only because the app is open or media work is busy. */
    suspend fun check(manual: Boolean, notify: Boolean = false): Boolean = checkGate.withLock {
        if (!available || (!manual && !appPrefs.appUpdates)) return@withLock false
        if (!manual && prefs.contains("success") && now() - prefs.getLong("success", 0) <= UpdatePolicy.CHECK_AGE) {
            return@withLock if (notify) notifyInBackground() else false
        }
        if (manual) mutable.update { it.copy(check = UpdateCheck.CHECKING) }
        try {
            // The site counts phones per day, so only the first check of a UTC day goes through it.
            val day = now() / UpdatePolicy.DAY
            val count = prefs.getLong("counted-day", -1) != day
            val (parsed, bytes, format) = withContext(Dispatchers.IO) { httpFactory(count).use { it.latestRelease() } }
            if (count && format == "manifest") prefs.edit().putLong("counted-day", day).apply()
            val release = parsed.takeIf { it.code > BuildConfig.VERSION_CODE }
            val checked = now()
            if (!prefs.edit().putLong("success", checked).putString("release", bytes.toString(Charsets.UTF_8))
                    .putString("release-format", format).commit())
                throw java.io.IOException()
            mutable.update { it.copy(release = release, lastChecked = checked,
                check = if (manual) { if (release == null) UpdateCheck.LATEST else UpdateCheck.AVAILABLE } else it.check) }
            refreshPresentation()
            if (notify) notifyInBackground() else false
        } catch (cancelled: CancellationException) {
            if (manual) mutable.update { it.copy(check = UpdateCheck.NONE) }
            throw cancelled
        }
        catch (_: Exception) {
            if (manual) mutable.update { it.copy(check = UpdateCheck.FAILED) }
            false
        }
    }
    @Synchronized internal fun postNotification(ready: Boolean = false, confirmation: Intent? = null): Boolean {
        val release = (if (confirmation != null) state.value.transfer else null) ?: state.value.release ?: return false
        if (prefs.contains("dismissed-${release.code}") &&
            now() - prefs.getLong("dismissed-${release.code}", 0) < UpdatePolicy.REMINDER_AGE) return false
        if (UpdatePolicy.notify(context.packageName, appPrefs.appUpdates, release.code, BuildConfig.VERSION_CODE.toLong(),
                state.value.skipped, prefs.getLong("notified", -1), release.important,
                prefs.getLong("notified-at-${release.code}", 0).takeIf { prefs.contains("notified-at-${release.code}") }, now()) &&
            UpdateNotifications(context).post(ready, confirmation)) {
            prefs.edit().putLong("notified", release.code).putLong("notified-at-${release.code}", now()).commit()
            return true
        }
        return false
    }
    fun dismiss() {
        val code = state.value.release?.code ?: return
        prefs.edit().putLong("dismissed-$code", now()).commit()
        refreshPresentation()
    }
    @Synchronized fun onAppOpen() {
        if (foreground || !available) return
        foreground = true
        handledInOpen = null
        refreshPresentation()
    }
    @Synchronized fun onAppClosed() {
        foreground = false
        mutable.update { it.copy(offerSheet = null) }
        synchronized(scheduleLock) {
            if (available && appPrefs.appUpdates && state.value.badge(BuildConfig.VERSION_CODE.toLong())) {
                WorkManager.getInstance(context).enqueueUniqueWork(CLOSE_WORK, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AppUpdateWorker>()
                        .setInitialDelay(1, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true).build())
                        .addTag(CLOSE_RUN)
                        .build())
            }
        }
    }
    fun offerSheetShown(code: Long) { prefs.edit().putBoolean("sheet-$code", true).commit() }
    @Synchronized fun dismissOfferSheet() {
        handledInOpen = state.value.offerSheet?.code
        mutable.update { it.copy(offerSheet = null) }
    }
    @Synchronized private fun refreshPresentation() {
        val state = state.value
        val release = state.release
        val code = release?.code
        val offered = state.badge(BuildConfig.VERSION_CODE.toLong())
        if (code != null && state.automatic && offered && !prefs.contains("offered-$code"))
            prefs.edit().putLong("offered-$code", now()).commit()
        val due = code != null && prefs.contains("offered-$code") &&
            now() - prefs.getLong("offered-$code", 0) >= UpdatePolicy.REMINDER_AGE && !prefs.getBoolean("sheet-$code", false)
        val sheet = release?.takeIf {
            foreground && state.automatic && it.code > BuildConfig.VERSION_CODE && (offered || it.important) &&
                (state.offerSheet?.code == code || handledInOpen != code && (it.important || due))
        }
        val dismissedAt = code?.let { c -> prefs.getLong("dismissed-$c", 0).takeIf {
            prefs.contains("dismissed-$c") && now() - it < UpdatePolicy.REMINDER_AGE
        } }
        mutable.update { it.copy(dismissedAt = dismissedAt, offerSheet = sheet) }
    }
    internal suspend fun silentInstallAllowed(verified: Boolean = readyFile != null && state.value.stage == UpdateStage.READY): Boolean {
        val app = context.applicationContext as? dev.ahmed.savlino.jobs.NezzelApplication
            ?: return false.also { deferredForPresenceOrWork = false }
        if (android.os.Build.VERSION.SDK_INT < 31 || !appPrefs.appUpdates || !verified) {
            deferredForPresenceOrWork = false
            return false
        }
        if (app.visibility.foreground || foreground) {
            deferredForPresenceOrWork = context.packageManager.canRequestPackageInstalls()
            return false
        }
        val busy = mediaBusy(app)
        // Read the switch and visibility after the IO suspension, immediately before the caller commits.
        val present = app.visibility.foreground || foreground
        val allowed = canSilentlyUpdate(android.os.Build.VERSION.SDK_INT, appPrefs.appUpdates, present, busy, verified)
        deferredForPresenceOrWork = !allowed && appPrefs.appUpdates && (present || busy) &&
            context.packageManager.canRequestPackageInstalls()
        return allowed
    }
    internal fun isAppForeground() = foreground || (context.applicationContext as? dev.ahmed.savlino.jobs.NezzelApplication)?.visibility?.foreground == true
    private suspend fun mediaBusy(app: dev.ahmed.savlino.jobs.NezzelApplication): Boolean {
        val downloads = app.downloads
        return downloads.hasQueuedOrRunning() ||
            downloads.maintenance.state.value.status == dev.ahmed.savlino.jobs.EngineUpdateStatus.UPDATING ||
            app.imageSaver.outcome.value is dev.ahmed.savlino.media.ImageSaveState.Saving ||
            dev.ahmed.savlino.statuses.StatusSaver.busy
    }
    /** Manual auto-install only while someone is in Nezzel and no media transfer would be killed by the process swap. */
    private suspend fun manualInstallAllowed(): Boolean {
        if (!isAppForeground()) return false
        val app = context.applicationContext as? dev.ahmed.savlino.jobs.NezzelApplication ?: return true
        return isAppForeground() && !mediaBusy(app)
    }
    /** The same download and verifier precede both manual and owner-enabled background installation. */
    internal suspend fun notifyInBackground(canPrepare: Boolean = canPrepareUpdate(context)): Boolean {
        val release = state.value.release ?: return false
        if (!appPrefs.appUpdates || !state.value.badge(BuildConfig.VERSION_CODE.toLong())) return false
        withContext(Dispatchers.IO) { filesGate.withLock { sweepOnce() } }
        if (canPrepare) launchDownload()?.join()
        val current = state.value
        val ready = readyFile != null && current.stage == UpdateStage.READY &&
            current.transfer?.code == release.code && current.transfer?.digest == release.digest
        if (ready && android.os.Build.VERSION.SDK_INT >= 31 && !silentAttempted(release.code)) {
            if (silentInstallAllowed() && UpdateInstaller.install(context, this, silent = true)) return false
            if (deferredForPresenceOrWork) {
                // Someone using Nezzel sees the in-app update card; a notification on top would only interrupt.
                if (!isAppForeground()) postNotification(readyFile != null && state.value.stage == UpdateStage.READY)
                return true
            }
        }
        if (isAppForeground()) return false
        postNotification(readyFile != null && state.value.stage == UpdateStage.READY)
        return false
    }
    @Synchronized fun skip(code: Long) {
        if (!available) return
        prefs.edit().putLong("skipped", code).commit()
        mutable.update { it.copy(skipped = code) }
        refreshPresentation()
        UpdateNotifications(context).cancel()
    }
    @Synchronized fun install() {
        if (installation?.isActive == true) return
        installation = scope.launch { UpdateInstaller.install(context, this@AppUpdates) }
    }
    @Synchronized fun download() {
        if (installation?.isActive == true) return
        val release = state.value.release ?: return
        installation = scope.launch {
            val download = launchDownload() ?: return@launch
            download.join()
            currentCoroutineContext().ensureActive()
            val current = state.value
            if (current.stage == UpdateStage.READY && current.transfer?.code == release.code &&
                current.transfer?.digest == release.digest && manualInstallAllowed())
                UpdateInstaller.install(context, this@AppUpdates)
        }
    }
    @Synchronized private fun launchDownload(): Job? {
        if (!available || state.value.stage == UpdateStage.INSTALLING) return null
        transfer?.takeIf { it.isActive }?.let { return it }
        val release = state.value.release ?: return null
        transfer = scope.launch(Dispatchers.IO) {
            filesGate.withLock {
                sweepOnce()
                if (readyFile != null && state.value.transfer?.code == release.code &&
                    state.value.transfer?.digest == release.digest) {
                    if (!UpdateVerifier.verify(context, readyFile!!, release)) fail(UpdateFailure.VERIFY)
                    return@withLock
                }
                val resume = partMatches(release)
                val keep = if (resume) setOf(release.name + ".part", release.name + ".part.json") else emptySet()
                cleanFiles(keep)
                mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, transfer = release, failure = null, bytes = 0) }
                var file: File? = null
                var keepPart = false
                try {
                    if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException()
                    if (!hasUpdateSpace(context, release.size)) { fail(UpdateFailure.SPACE); return@withLock }
                    file = File(directory, release.name + ".part")
                    File(directory, release.name + ".part.json").outputStream().use { out ->
                        out.write(JSONObject().put("code", release.code).put("size", release.size)
                            .put("sha256", release.digest).toString().toByteArray())
                        out.fd.sync()
                    }
                    if (file.length() != release.size) httpFactory(false).use { client ->
                        http = client
                        client.download(release, file) { bytes -> mutable.update { it.copy(bytes = bytes) } }
                    }
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(stage = UpdateStage.VERIFYING) }
                    if (!UpdateVerifier.verify(context, file, release)) { fail(UpdateFailure.VERIFY); return@withLock }
                    currentCoroutineContext().ensureActive()
                    val ready = File(directory, release.name)
                    if (!file.renameTo(ready)) throw java.io.IOException()
                    if (!prefs.edit().putString("ready-file", ready.name).putLong("ready-code", release.code).commit()) {
                        ready.delete(); throw java.io.IOException()
                    }
                    File(directory, release.name + ".part.json").delete()
                    readyFile = ready
                    mutable.update { it.copy(stage = UpdateStage.READY, bytes = release.size) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    val reason = if (isNoSpace(failure)) UpdateFailure.SPACE else UpdateFailure.NETWORK
                    keepPart = reason == UpdateFailure.NETWORK && file?.isFile == true && file.length() in 1..release.size
                    if (keepPart) mutable.update { it.copy(stage = UpdateStage.ERROR, failure = reason, confirmation = null) }
                    else fail(reason)
                }
                finally {
                    http = null
                    if (!keepPart) {
                        file?.delete()
                        File(directory, release.name + ".part.json").delete()
                    }
                }
            }
        }
        return transfer
    }
    fun cancel() {
        // The button exists only during download. Once the session is copying, cancel would abandon it or delete the APK.
        if (state.value.stage == UpdateStage.INSTALLING || installGate.isLocked) return
        installation?.cancel()
        if (state.value.stage == UpdateStage.INSTALLING || installGate.isLocked) return
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
        if (failure == UpdateFailure.INSTALL) { installFailed(failure); return }
        cleanFiles()
        mutable.update { it.copy(stage = UpdateStage.ERROR, failure = failure, confirmation = null) }
    }
    internal fun installFailed(failure: UpdateFailure) {
        stopSilentAttempts()
        clearInstall()
        mutable.update { it.copy(stage = if (readyFile?.isFile == true) UpdateStage.READY else UpdateStage.ERROR,
            failure = failure, confirmation = null) }
    }
    internal fun silentAttempted(code: Long): Boolean = prefs.getInt("silent-attempts-$code", 0) > 0 ||
        prefs.getBoolean("install-silent", false) && prefs.getLong("install-code", -1) == code
    /** Reserve before creating a session; a killed or rejected session cannot loop on a cold worker. */
    internal fun reserveSilentAttempt(code: Long): Boolean = !silentAttempted(code) &&
        prefs.edit().putInt("silent-attempts-$code", 1).commit()
    /** A silent try deferred before commit (app opened, work cancelled) never reached Android, so it is given back. */
    internal fun releaseSilentAttempt(code: Long) { prefs.edit().remove("silent-attempts-$code").commit() }
    internal fun stopSilentAttempts() {
        val code = prefs.getLong("install-code", -1)
        if (prefs.getBoolean("install-silent", false) && code > 0)
            prefs.edit().putInt("silent-attempts-$code", 1).commit()
    }
    internal fun holdConfirmation(intent: Intent) { mutable.update { it.copy(stage = UpdateStage.INSTALLING, confirmation = intent) } }
    internal fun takeConfirmation(): Intent? = mutable.getAndUpdate { it.copy(confirmation = null) }.confirmation
    internal fun installing() { mutable.update { it.copy(stage = UpdateStage.INSTALLING) } }
    internal fun deferInstall() { mutable.update { it.copy(stage = UpdateStage.READY, confirmation = null) } }
    internal fun installed() {
        cleanFiles()
        mutable.update { it.copy(release = null, transfer = null, stage = UpdateStage.IDLE, confirmation = null, offerSheet = null) }
    }
    internal fun cleanFiles(keep: Set<String> = emptySet()) {
        readyFile = null
        prefs.edit().remove("ready-file").remove("ready-code").commit()
        directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
    internal fun recordInstall(session: Int, release: Release, silent: Boolean = false): Boolean = prefs.edit()
        .putInt("install-session", session).putLong("install-code", release.code)
        .putBoolean("install-silent", silent)
        .putString("install-notes", JSONObject().put("version", release.version)
            .put("en", JSONArray(release.english)).put("ar", JSONArray(release.arabic)).put("fr", JSONArray(release.french)).toString()).commit()
    internal fun clearInstall(): Boolean {
        val editor = prefs.edit()
        // Only a completed update to this build can become About's retained notes.
        readWhatsNew()?.let { notes -> editor.putString("current-notes", encodeNotes(notes)) }
        val cleared = editor.remove("install-code").remove("install-session").remove("install-notes").remove("install-silent").commit()
        if (cleared) mutable.update { it.copy(whatsNew = null) }
        return cleared
    }
    private fun decodeNotes(raw: String?): WhatsNew? = try {
        val json = JSONObject(raw ?: "{}")
        fun notes(language: String): List<String> = json.optJSONArray(language)?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty()
        val english = notes("en")
        val arabic = notes("ar")
        if (english.isEmpty() && arabic.isEmpty()) null else WhatsNew(json.getString("version"), english, arabic, notes("fr"))
    } catch (_: Exception) { null }
    private fun encodeNotes(notes: WhatsNew): String = JSONObject().put("code", BuildConfig.VERSION_CODE)
        .put("version", notes.version).put("en", JSONArray(notes.english)).put("ar", JSONArray(notes.arabic)).put("fr", JSONArray(notes.french)).toString()
    private fun cachedRelease(): Release? = prefs.getString("release", null)?.toByteArray()?.let { bytes ->
        when (prefs.getString("release-format", null)) {
            "manifest" -> Release.parseManifest(bytes)
            "api", null -> Release.parse(bytes)
            else -> null
        }
    }
    private fun readWhatsNew(): WhatsNew? {
        if (!available || prefs.getLong("install-code", -1) != BuildConfig.VERSION_CODE.toLong()) return null
        decodeNotes(prefs.getString("install-notes", null))?.let { return it }
        // 0.4.4 records install-code but not install-notes; its cached offer is the release that was just installed.
        // Copy its notes now: the next successful check replaces that cache.
        val migrated = cachedRelease()?.takeIf { it.code == BuildConfig.VERSION_CODE.toLong() }
            ?.takeIf { it.english.isNotEmpty() || it.arabic.isNotEmpty() }?.let { WhatsNew(it.version, it.english, it.arabic, it.french) } ?: return null
        prefs.edit().putString("install-notes", JSONObject().put("version", migrated.version)
            .put("en", JSONArray(migrated.english)).put("ar", JSONArray(migrated.arabic)).put("fr", JSONArray(migrated.french)).toString()).commit()
        return migrated
    }
    fun currentNotes(): WhatsNew? {
        if (!available) return null
        val raw = prefs.getString("current-notes", null)
        val current = try { JSONObject(raw ?: "{}").optLong("code", -1) == BuildConfig.VERSION_CODE.toLong() }
            catch (_: Exception) { false }
        if (raw != null && !current) prefs.edit().remove("current-notes").commit()
        // A pending first presentation is also available from About before it is dismissed.
        return readWhatsNew() ?: (if (current) decodeNotes(raw) else null) ?: bundledNotes
    }
    internal fun refreshWhatsNew() { mutable.update { it.copy(whatsNew = readWhatsNew()) } }
    fun dismissWhatsNew() {
        if (state.value.whatsNew == null) return
        clearInstall()
    }
    fun consumeUpdated(): Boolean {
        if (!available || state.value.whatsNew != null || prefs.getLong("install-code", -1) != BuildConfig.VERSION_CODE.toLong()) return false
        cleanFiles()
        return clearInstall().also { if (it) (context.applicationContext as? dev.ahmed.savlino.jobs.NezzelApplication)
            ?.usage?.count(dev.ahmed.savlino.usage.UsageKeys.UPDATE_INSTALLED) }
    }
    private fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.take(10).any {
        it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC || it.message?.contains("ENOSPC") == true
    }
    companion object {
        const val WORK = "app-updates"
        internal const val CLOSE_WORK = "app-updates-on-close"
        /** Marks the one-shot enqueued from [onAppClosed], so a skipped silent install can retry without changing the periodic run. */
        internal const val CLOSE_RUN = "close-run"
        internal const val MARGIN = 64L * 1024 * 1024
    }
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!UpdatePolicy.enabled(applicationContext.packageName)) return Result.success()
        val app = applicationContext as dev.ahmed.savlino.jobs.NezzelApplication
        val deferred = app.appUpdates.check(manual = false, notify = true)
        app.usageStore.sendDaily()
        if (AppUpdates.CLOSE_RUN in tags && deferred && runAttemptCount < 5) return Result.retry()
        return Result.success()
    }
}
