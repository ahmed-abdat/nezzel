package dev.ahmed.savlino.update

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.ahmed.savlino.jobs.NezzelApplication
import kotlinx.coroutines.*

/** The status PendingIntent is explicit and private; only this session can change updater state. */
internal object UpdateInstaller {
    const val STATUS = "dev.ahmed.savlino.UPDATE_INSTALL_STATUS"
    internal fun sessionParams(context: Context, release: Release) = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName); setSize(release.size)
        setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
    }
    suspend fun install(context: Context, updates: AppUpdates) = withContext(Dispatchers.IO) {
        if (!updates.available || updates.state.value.stage != UpdateStage.READY || !context.packageManager.canRequestPackageInstalls()) return@withContext
        val release = updates.state.value.transfer ?: return@withContext
        val file = updates.readyFile ?: return@withContext
        updates.installing()
        if (!hasUpdateSpace(context, release.size)) { updates.fail(UpdateFailure.SPACE); return@withContext }
        if (!UpdateVerifier.verify(context, file, release)) { updates.fail(UpdateFailure.VERIFY); return@withContext }
        val installer = context.packageManager.packageInstaller
        var id: Int? = null
        try {
            val params = sessionParams(context, release)
            val sessionId = installer.createSession(params); id = sessionId
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input -> session.openWrite("base.apk", 0, release.size).use { out -> input.copyTo(out); session.fsync(out) } }
                if (!updates.recordInstall(sessionId, release)) throw java.io.IOException()
                val status = Intent(context, UpdateInstallReceiver::class.java).setAction(STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
            }
        } catch (cancelled: CancellationException) {
            id?.let { try { installer.abandonSession(it) } catch (_: Exception) {} }
            updates.clearInstall()
            updates.fail(UpdateFailure.INSTALL); throw cancelled
        } catch (_: Exception) {
            id?.let { try { installer.abandonSession(it) } catch (_: Exception) {} }
            updates.clearInstall()
            updates.fail(UpdateFailure.INSTALL)
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!UpdatePolicy.enabled(context.packageName) || intent.action != UpdateInstaller.STATUS) return
        val updates = (context.applicationContext as NezzelApplication).appUpdates
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != updates.prefs.getInt("install-session", -1)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) failed(context, updates, id)
                else updates.holdConfirmation(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> { updates.installed(); updates.prefs.edit().remove("install-session").commit() }
            PackageInstaller.STATUS_FAILURE_STORAGE -> failed(context, updates, id, UpdateFailure.SPACE)
            else -> failed(context, updates, id)
        }
    }
    private fun failed(context: Context, updates: AppUpdates, id: Int, failure: UpdateFailure = UpdateFailure.INSTALL) {
        try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
        updates.clearInstall()
        updates.fail(failure)
    }
}
