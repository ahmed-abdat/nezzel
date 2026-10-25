package dev.ahmed.savlino.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = IsolatedTestApplication::class)
class SilentUpdateIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.NezzelApplication
    private fun prepared(scope: CoroutineScope): AppUpdates {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val bytes = "verified update".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(app.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val manifest = JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", 99999)
            .put("apk", JSONObject().put("name", file.name).put("size", bytes.size).put("sha256", digest))
            .put("notes", JSONObject().put("en", JSONArray().put("Improved")))
        app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
            .putString("release", manifest.toString()).putString("release-format", "manifest")
            .putLong("ready-code", 99999).putString("ready-file", file.name).commit()
        fun identity(code: Long) = PackageInfo().apply {
            packageName = app.packageName; longVersionCode = code
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("01020304"))) }
        }
        shadowOf(app.packageManager).apply {
            installPackage(identity(BuildConfig.VERSION_CODE.toLong()))
            setPackageArchiveInfo(file.absolutePath, identity(99999))
            setCanRequestPackageInstalls(true)
        }
        return AppUpdates(app, scope) { error("A cached update must not need network") }
    }
    @Test fun coldWorkerInstallsVerifiedCachedApkWithoutAnotherDownload() = runBlocking {
        val updates = prepared(this)
        updates.notifyInBackground(canPrepare = false)
        assertEquals(UpdateStage.INSTALLING, updates.state.value.stage)
        val id = updates.prefs.getInt("install-session", -1)
        assertTrue(id >= 0)
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(id))
        assertTrue(updates.prefs.getBoolean("install-silent", false))
    }
    @Test fun foregroundDefersAndNextBackgroundRunUsesTheSameVerifiedFile() = runBlocking {
        val updates = prepared(this)
        updates.onAppOpen()
        updates.notifyInBackground(canPrepare = false)
        assertFalse(updates.prefs.contains("install-session"))
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        updates.onAppClosed()
        updates.notifyInBackground(canPrepare = false)
        assertTrue(updates.prefs.contains("install-session"))
    }
    @Test fun modifiedCachedApkNeverCreatesASession() = runBlocking {
        val updates = prepared(this)
        File(app.cacheDir, "updates/update.apk").appendText("tampered")
        updates.notifyInBackground(canPrepare = false)
        assertFalse(updates.prefs.contains("install-session"))
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
    }
    @Test fun coldProcessKeepsSilentSessionUntilAnExplicitManualRetry() = runBlocking {
        val first = prepared(this)
        first.notifyInBackground(canPrepare = false)
        val old = first.prefs.getInt("install-session", -1)
        assertTrue(old >= 0)
        val cold = AppUpdates(app, this) { error("No network") }
        cold.notifyInBackground(canPrepare = false)
        assertEquals(old, cold.prefs.getInt("install-session", -1))
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(old))
        assertTrue(UpdateInstaller.install(app, cold))
        val replacement = cold.prefs.getInt("install-session", -1)
        assertTrue(replacement >= 0)
        assertNotEquals(old, replacement)
        assertNull(app.packageManager.packageInstaller.getSessionInfo(old))
    }
    @Test @Config(application = GateFlipApplication::class)
    fun stoppedDuringFinalCommitGateKeepsTheVerifiedFileReadyForNextRun() = interruptedInstall(true)
    @Test @Config(application = GateFlipApplication::class)
    fun installationErrorKeepsTheVerifiedFileReadyForManualRetry() = interruptedInstall(false)
    private fun interruptedInstall(cancelled: Boolean) = runBlocking {
        val updates = prepared(this)
        var checks = 0
        (app as GateFlipApplication).whenDownloadsRead = {
            if (++checks == 3) {
                if (cancelled) throw CancellationException("Work stopped")
                else error("Install interrupted")
            }
        }
        try { updates.notifyInBackground(canPrepare = false) }
        catch (failure: CancellationException) { if (!cancelled) throw failure }
        assertEquals(3, checks)
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        assertTrue(updates.readyFile!!.isFile)
        assertFalse(updates.prefs.contains("install-session"))
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
    }
    @Test fun queuedPlaylistDefersInstallationBeforeAnyJobEntersTheProgressStream() = runBlocking {
        val updates = prepared(this)
        dev.ahmed.savlino.jobs.JobStore(app, links = dev.ahmed.savlino.jobs.batchCipher()).use { jobs ->
            val batches = dev.ahmed.savlino.jobs.BatchStore(jobs, dev.ahmed.savlino.jobs.batchCipher())
            assertTrue(batches.createLinks("pending", "Fixture", listOf(dev.ahmed.savlino.domain.LinkInput.parse("https://example.org/video")!!), dev.ahmed.savlino.domain.Preset.BEST))
            (app as IsolatedTestApplication).coordinatorOverride = dev.ahmed.savlino.jobs.DownloadCoordinator(app, store = jobs)
            try {
                assertTrue(app.downloads.progress.value.isEmpty())
                updates.notifyInBackground(canPrepare = false)
                assertEquals(UpdateStage.READY, updates.state.value.stage)
                assertFalse(updates.prefs.contains("install-session"))
                assertEquals(99999L, updates.prefs.getLong("notified", -1))
            } finally { (app as IsolatedTestApplication).coordinatorOverride = null }
        }
    }
}
