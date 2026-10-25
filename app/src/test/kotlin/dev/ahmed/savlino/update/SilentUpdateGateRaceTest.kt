package dev.ahmed.savlino.update

import android.app.Activity
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.NezzelApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class GateFlipApplication : NezzelApplication() {
    var whenDownloadsRead: () -> Unit = {}
    override fun onCreate() = Unit
    override val downloads: DownloadCoordinator get() {
        whenDownloadsRead()
        return super.downloads
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = GateFlipApplication::class)
class SilentUpdateGateRaceTest {
    @Test fun foregroundEntryDuringDownloadCheckStillDefersCommit() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val updates = AppUpdates(app, this) { error("No network") }
        app.whenDownloadsRead = { app.visibility.onActivityStarted(Activity()) }
        assertFalse(updates.silentInstallAllowed(verified = true))
    }
    @Test fun switchOffDuringDownloadCheckStillDefersCommit() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val updates = AppUpdates(app, this) { error("No network") }
        app.whenDownloadsRead = { app.preferences.appUpdates = false }
        assertFalse(updates.silentInstallAllowed(verified = true))
    }
}
