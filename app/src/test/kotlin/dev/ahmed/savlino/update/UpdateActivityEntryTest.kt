package dev.ahmed.savlino.update

import android.content.Intent
import android.os.Bundle
import dev.ahmed.savlino.MainActivity
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import dev.ahmed.savlino.locale.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = IsolatedTestApplication::class)
class UpdateActivityEntryTest {
    @Test fun receiverHoldsPendingConfirmationWithoutStartingAnActivity() {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        val updates = app.appUpdates
        updates.prefs.edit().putInt("install-session", 123).commit()
        val confirmation = Intent("dev.ahmed.savlino.TEST_INSTALL_CONFIRMATION")
        val status = Intent(UpdateInstaller.STATUS)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, 123)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation)
        UpdateInstallReceiver().onReceive(app, status)
        assertSame(confirmation, updates.state.value.confirmation)
        assertEquals(UpdateStage.INSTALLING, updates.state.value.stage)
        assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test fun notificationLaunchIsConsumedAndDoesNotOverrideRestoredDestination() {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        AppPreferences(app).apply { appUpdates = false; suggestionsEnabled = false; automaticOptions = false }
        val launch = Intent(app, MainActivity::class.java).setAction(UpdateNotifications.OPEN)
        val first = Robolectric.buildActivity(MainActivity::class.java, Intent(launch)).setup()
        val saved = Bundle()
        try {
            assertEquals("AppUpdate", first.get().links.destination)
            assertNull(first.get().intent.action)
            first.get().links.navigate("Settings")
            first.pause().saveInstanceState(saved).stop()
        } finally { first.destroy() }
        // Recreate with Android's original launch envelope and the saved navigation state.
        val restored = Robolectric.buildActivity(MainActivity::class.java, launch).create(saved).start().resume().visible()
        try {
            assertEquals("Settings", restored.get().links.destination)
            assertNull(restored.get().intent.action)
        } finally { restored.pause().stop().destroy() }
    }
}
