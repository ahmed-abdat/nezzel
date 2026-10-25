package dev.ahmed.savlino.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.*
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPackageInstaller
import java.io.File
import java.security.MessageDigest

@Implements(PackageInstaller::class)
class ChangedUpdateInstaller : ShadowPackageInstaller() {
    @Implementation override fun createSession(params: PackageInstaller.SessionParams): Int {
        // The archive was verified, but its bytes change before the installer reads it.
        File(RuntimeEnvironment.getApplication().cacheDir, "updates/update.apk").writeText("tampered update")
        return super.createSession(params)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = IsolatedTestApplication::class)
class SilentUpdateRetryTest {
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val apk get() = File(app.cacheDir, "updates/update.apk")
    private var downloads = 0
    private fun prepare(code: Long = 99999) {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val bytes = "verified update".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        apk.apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val manifest = JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", code)
            .put("apk", JSONObject().put("name", apk.name).put("size", bytes.size).put("sha256", digest))
            .put("notes", JSONObject().put("en", JSONArray().put("Improved")))
        app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
            .putString("release", manifest.toString()).putString("release-format", "manifest")
            .putLong("ready-code", code).putString("ready-file", apk.name).commit()
        fun identity(version: Long) = PackageInfo().apply {
            packageName = app.packageName; longVersionCode = version
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("01020304"))) }
        }
        shadowOf(app.packageManager).apply {
            installPackage(identity(BuildConfig.VERSION_CODE.toLong()))
            setPackageArchiveInfo(apk.absolutePath, identity(code))
            setCanRequestPackageInstalls(true)
        }
    }
    private fun cold(scope: CoroutineScope) = AppUpdates(app, scope) {
        downloads++
        error("A verified APK must be reused")
    }
    private fun status(updates: AppUpdates, result: Int, confirmation: Boolean = false) {
        UpdateInstallReceiver().onReceive(app, Intent(UpdateInstaller.STATUS)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, updates.prefs.getInt("install-session", -1))
            .putExtra(PackageInstaller.EXTRA_STATUS, result).apply {
                if (confirmation) putExtra(Intent.EXTRA_INTENT, Intent("dev.ahmed.savlino.TEST_CONFIRM"))
            })
    }
    @Test fun aSilentTryDeferredBeforeCommitIsGivenBack() = runBlocking {
        prepare()
        val updates = cold(this)
        assertTrue(updates.reserveSilentAttempt(99999))
        assertTrue(updates.silentAttempted(99999))
        updates.releaseSilentAttempt(99999)
        assertFalse(updates.silentAttempted(99999))
        assertTrue(updates.reserveSilentAttempt(99999))
        coroutineContext.cancelChildren()
    }
    @Test fun rejectedSilentSessionKeepsApkAndNewerReleaseGetsItsOwnAttempt() = runBlocking {
        prepare()
        val first = app.appUpdates
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertTrue("Install rejection must retain the verified APK", apk.isFile)
        val next = cold(this)
        next.notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
        assertEquals(99999L, next.prefs.getLong("notified", -1))
        prepare(100000)
        val newer = cold(this)
        newer.notifyInBackground(true)
        assertEquals(0, downloads)
        assertEquals(100000L, newer.prefs.getLong("install-code", -1))
        assertEquals(1, app.packageManager.packageInstaller.allSessions.size)
    }
    @Test fun pendingPromptIsNotRecreatedOrRenotifiedByColdWorkers() = runBlocking {
        prepare()
        val first = app.appUpdates
        first.notifyInBackground(false)
        val session = first.prefs.getInt("install-session", -1)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertEquals(99999L, first.prefs.getLong("notified", -1))
        assertEquals(1, manager.activeNotifications.size)
        manager.cancel(UpdateNotifications.ID) // User dismisses it.
        cold(this).notifyInBackground(true)
        cold(this).notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(listOf(session), app.packageManager.packageInstaller.allSessions.map { it.sessionId })
    }
    @Test fun mutedPendingPromptConsumesTheAttemptWithoutDeletingApk() = runBlocking {
        prepare()
        val first = app.appUpdates
        first.notifyInBackground(false)
        shadowOf(manager).setNotificationsEnabled(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        cold(this).notifyInBackground(true)
        cold(this).notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(apk.isFile)
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun pendingPromptHonorsAnEarlierNotification() = runBlocking {
        prepare()
        val first = app.appUpdates
        first.postNotification()
        assertEquals(99999L, first.prefs.getLong("notified", -1))
        manager.cancel(UpdateNotifications.ID)
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun pendingPromptHonorsTheThreeDayDismissalWindow() = runBlocking {
        prepare()
        val first = app.appUpdates
        first.dismiss()
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun busyEngineMaintenanceFallsBackToReadyNotification() = runBlocking {
        prepare()
        val release = CompletableDeferred<Unit>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun updateYtdlp(): EngineUpdateResult {
                release.await()
                return EngineUpdateResult.Success("test", "test")
            }
        }
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine, store)
            app.coordinatorOverride = coordinator
            val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
            try {
                withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
                val updates = cold(this)
                updates.notifyInBackground(false)
                assertFalse(updates.prefs.contains("install-session"))
                assertEquals(1, manager.activeNotifications.size)
                assertEquals(99999L, updates.prefs.getLong("notified", -1))
            } finally {
                release.complete(Unit); updating.cancelAndJoin(); app.coordinatorOverride = null
            }
        }
    }
    @Test @Config(shadows = [ChangedUpdateInstaller::class])
    fun changedBytesAfterVerificationAreNeverCommitted() = runBlocking {
        prepare()
        val updates = cold(this)
        updates.notifyInBackground(false)
        assertEquals(UpdateFailure.VERIFY, updates.state.value.failure)
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
        assertFalse(updates.prefs.contains("install-session"))
    }
}
