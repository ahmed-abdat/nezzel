package dev.ahmed.savlino.update

import android.content.Context
import android.content.Intent
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

internal fun updateCheckJitterMillis(random: Random = Random.Default): Long = random.nextLong(TimeUnit.HOURS.toMillis(6) + 1)
internal fun requiredUpdateSpace(size: Long): Long = 2 * size + AppUpdates.MARGIN
internal fun hasUpdateSpace(context: Context, size: Long, allocatable: () -> Long = {
    val storage = context.getSystemService(StorageManager::class.java)
    storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
}, usable: () -> Long = { context.cacheDir.usableSpace }): Boolean =
    (try { allocatable() } catch (_: Exception) { usable() }) >= requiredUpdateSpace(size)

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, ERROR }
enum class UpdateFailure { NETWORK, SPACE, VERIFY, INSTALL }
enum class UpdateCheck { NONE, CHECKING, LATEST, AVAILABLE, FAILED }
class WhatsNew(val version: String, val english: List<String>, val arabic: List<String>) {
    fun notes(language: String): List<String> = if (language == "ar" && arabic.isNotEmpty()) arabic else english
}
data class AppUpdateState(val automatic: Boolean = true, val release: Release? = null, val skipped: Long? = null,
    val dismissed: Boolean = false, val check: UpdateCheck = UpdateCheck.NONE, val stage: UpdateStage = UpdateStage.IDLE,
    val bytes: Long = 0, val failure: UpdateFailure? = null, val transfer: Release? = null,
    val confirmation: Intent? = null, val lastChecked: Long? = null, val whatsNew: WhatsNew? = null) {
    fun badge(current: Long) = release?.let { UpdatePolicy.offer(it.code, current, skipped) } == true
    fun card(current: Long) = !dismissed && badge(current)
}

/** Process-owned work; no Activity or file survives as an authorization to install. */
class AppUpdates internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val httpFactory: () -> UpdateHttp) {
    constructor(context: Context, scope: CoroutineScope) : this(context, scope, { UpdateHttp(BuildConfig.VERSION_NAME) })
    val available = UpdatePolicy.enabled(context.packageName)
    internal val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val appPrefs = AppPreferences(context)
    private val mutable = MutableStateFlow(AppUpdateState(automatic = appPrefs.appUpdates,
        release = if (available) cachedRelease()?.takeIf { it.code > BuildConfig.VERSION_CODE } else null,
        skipped = prefs.getLong("skipped", -1).takeIf { it > 0 },
        lastChecked = prefs.getLong("success", 0).takeIf { prefs.contains("success") },
        whatsNew = readWhatsNew()))
    val state: StateFlow<AppUpdateState> = mutable.asStateFlow()
    init { currentNotes() } // Discard notes retained by an older build, even before About opens
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
    @Synchronized fun start() {
        if (!available || started) return
        started = true
        schedule()
        scope.launch {
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
    }
    private fun schedule() {
        val work = WorkManager.getInstance(context)
        if (!available || !appPrefs.appUpdates) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(DAY + updateCheckJitterMillis(), TimeUnit.MILLISECONDS)
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
            val checked = System.currentTimeMillis()
            if (!prefs.edit().putLong("success", checked).putString("release", bytes.toString(Charsets.UTF_8))
                    .putString("release-format", format).commit())
                throw java.io.IOException()
            mutable.update { it.copy(release = release, lastChecked = checked, dismissed = if (it.release?.code == release?.code) it.dismissed else false,
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
                if (readyFile != null && state.value.transfer?.code == release.code &&
                    state.value.transfer?.digest == release.digest) return@withLock
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
                    if (file.length() != release.size) httpFactory().use { client ->
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
        if (failure == UpdateFailure.INSTALL) clearInstall()
        cleanFiles()
        mutable.update { it.copy(stage = UpdateStage.ERROR, failure = failure, confirmation = null) }
    }
    internal fun holdConfirmation(intent: Intent) { mutable.update { it.copy(stage = UpdateStage.INSTALLING, confirmation = intent) } }
    internal fun takeConfirmation(): Intent? = mutable.getAndUpdate { it.copy(confirmation = null) }.confirmation
    internal fun installing() { mutable.update { it.copy(stage = UpdateStage.INSTALLING) } }
    internal fun installed() {
        cleanFiles()
        mutable.update { it.copy(release = null, transfer = null, stage = UpdateStage.IDLE, confirmation = null) }
    }
    internal fun cleanFiles(keep: Set<String> = emptySet()) {
        readyFile = null
        prefs.edit().remove("ready-file").remove("ready-code").commit()
        directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
    internal fun recordInstall(session: Int, release: Release): Boolean = prefs.edit()
        .putInt("install-session", session).putLong("install-code", release.code)
        .putString("install-notes", JSONObject().put("version", release.version)
            .put("en", JSONArray(release.english)).put("ar", JSONArray(release.arabic)).toString()).commit()
    internal fun clearInstall(): Boolean {
        val editor = prefs.edit()
        // Only a completed update to this build can become About's retained notes.
        readWhatsNew()?.let { notes -> editor.putString("current-notes", encodeNotes(notes)) }
        val cleared = editor.remove("install-code").remove("install-session").remove("install-notes").commit()
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
        if (english.isEmpty() && arabic.isEmpty()) null else WhatsNew(json.getString("version"), english, arabic)
    } catch (_: Exception) { null }
    private fun encodeNotes(notes: WhatsNew): String = JSONObject().put("code", BuildConfig.VERSION_CODE)
        .put("version", notes.version).put("en", JSONArray(notes.english)).put("ar", JSONArray(notes.arabic)).toString()
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
            ?.takeIf { it.english.isNotEmpty() || it.arabic.isNotEmpty() }?.let { WhatsNew(it.version, it.english, it.arabic) } ?: return null
        prefs.edit().putString("install-notes", JSONObject().put("version", migrated.version)
            .put("en", JSONArray(migrated.english)).put("ar", JSONArray(migrated.arabic)).toString()).commit()
        return migrated
    }
    fun currentNotes(): WhatsNew? {
        if (!available) return null
        val raw = prefs.getString("current-notes", null)
        val current = try { JSONObject(raw ?: "{}").optLong("code", -1) == BuildConfig.VERSION_CODE.toLong() }
            catch (_: Exception) { false }
        if (raw != null && !current) prefs.edit().remove("current-notes").commit()
        // A pending first presentation is also available from About before it is dismissed.
        return readWhatsNew() ?: if (current) decodeNotes(raw) else null
    }
    internal fun refreshWhatsNew() { mutable.update { it.copy(whatsNew = readWhatsNew()) } }
    fun dismissWhatsNew() {
        if (state.value.whatsNew == null) return
        clearInstall()
    }
    fun consumeUpdated(): Boolean {
        if (!available || state.value.whatsNew != null || prefs.getLong("install-code", -1) != BuildConfig.VERSION_CODE.toLong()) return false
        cleanFiles()
        return clearInstall()
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
