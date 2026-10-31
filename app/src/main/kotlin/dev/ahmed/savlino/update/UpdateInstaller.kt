package dev.ahmed.savlino.update

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.ahmed.savlino.jobs.NezzelApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** The status PendingIntent is explicit and private; only this session can change updater state. */
internal object UpdateInstaller {
    const val STATUS = "dev.ahmed.savlino.UPDATE_INSTALL_STATUS"
    internal fun sessionParams(context: Context, release: Release) = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName); setSize(release.size)
        setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
    }
    suspend fun install(context: Context, updates: AppUpdates, silent: Boolean = false): Boolean = updates.installGate.withLock { withContext(Dispatchers.IO) {
        if (!updates.available || updates.state.value.stage != UpdateStage.READY || !context.packageManager.canRequestPackageInstalls()) return@withContext false
        val release = updates.state.value.transfer ?: return@withContext false
        val file = updates.readyFile ?: return@withContext false
        if (silent && !updates.silentInstallAllowed()) return@withContext false
        if (!hasUpdateSpace(context, release.size)) { updates.fail(UpdateFailure.SPACE); return@withContext false }
        if (!UpdateVerifier.verify(context, file, release)) { updates.fail(UpdateFailure.VERIFY); return@withContext false }
        if (silent && !updates.reserveSilentAttempt(release.code)) return@withContext false
        val installer = context.packageManager.packageInstaller
        updates.installing()
        var id: Int? = null
        try {
            // READY with a recorded session means this process lost the previous session's UI state.
            val existing = updates.prefs.getInt("install-session", -1)
            if (existing >= 0) {
                if (installer.getSessionInfo(existing) != null) installer.abandonSession(existing)
                updates.clearInstall()
            }
            val params = sessionParams(context, release)
            val sessionId = installer.createSession(params); id = sessionId
            installer.openSession(sessionId).use { session ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val written = file.inputStream().use { input -> session.openWrite("base.apk", 0, release.size).use { out ->
                    val count = java.security.DigestInputStream(input, digest).copyTo(out)
                    session.fsync(out)
                    count
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (written != release.size || !hash.equals(release.digest, ignoreCase = true)) {
                    session.abandon()
                    updates.clearInstall()
                    updates.fail(UpdateFailure.VERIFY)
                    return@withContext false
                }
                if (!updates.recordInstall(sessionId, release, silent)) throw java.io.IOException()
                val status = Intent(context, UpdateInstallReceiver::class.java).setAction(STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                if (silent && (!updates.silentInstallAllowed(verified = true) || !context.packageManager.canRequestPackageInstalls())) {
                    session.abandon()
                    updates.clearInstall()
                    updates.releaseSilentAttempt(release.code)
                    updates.deferInstall()
                    return@withContext false
                }
                currentCoroutineContext().ensureActive()
                session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
            }
            true
        } catch (cancelled: CancellationException) {
            id?.let { try { installer.abandonSession(it) } catch (_: Exception) {} }
            updates.clearInstall()
            if (silent) { updates.releaseSilentAttempt(release.code); updates.deferInstall() } else updates.fail(UpdateFailure.INSTALL)
            throw cancelled
        } catch (_: Exception) {
            id?.let { try { installer.abandonSession(it) } catch (_: Exception) {} }
            updates.clearInstall()
            if (silent) updates.deferInstall() else updates.fail(UpdateFailure.INSTALL)
            false
        }
    } }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!UpdatePolicy.enabled(context.packageName) || intent.action != UpdateInstaller.STATUS) return
        val updates = (context.applicationContext as NezzelApplication).appUpdates
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != updates.prefs.getInt("install-session", -1)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                updates.stopSilentAttempts()
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) failed(context, updates, id)
                else {
                    updates.holdConfirmation(confirmation)
                    if (updates.prefs.getBoolean("install-silent", false) &&
                        !updates.postNotification(ready = true, confirmation = confirmation)) {
                        try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
                        updates.clearInstall()
                        updates.deferInstall()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                updates.installed()
                updates.prefs.edit().remove("install-session").remove("install-silent").commit()
                UpdateNotifications(context).cancel()
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> failed(context, updates, id, UpdateFailure.SPACE)
            else -> failed(context, updates, id)
        }
    }
    private fun failed(context: Context, updates: AppUpdates, id: Int, failure: UpdateFailure = UpdateFailure.INSTALL) {
        try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
        updates.installFailed(failure)
    }
}
