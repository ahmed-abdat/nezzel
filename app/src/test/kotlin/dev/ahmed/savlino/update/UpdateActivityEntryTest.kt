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

    @Test fun firstRunDefersEvenImportantSheetUntilANewAppOpen() {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        app.getSharedPreferences("nezzel", android.content.Context.MODE_PRIVATE).edit().remove("onboarding_seen").commit()
        val prefs = app.getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
        val manifest = org.json.JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", 99999).put("important", true)
            .put("apk", org.json.JSONObject().put("name", "update.apk").put("size", 100).put("sha256", "a".repeat(64)))
            .put("notes", org.json.JSONObject().put("en", org.json.JSONArray().put("Improved")))
        prefs.edit().putString("release", manifest.toString()).putString("release-format", "manifest")
            .putLong("success", System.currentTimeMillis()).commit()
        AppPreferences(app).apply { suggestionsEnabled = false; automaticOptions = false }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(app.appUpdates.state.value.release!!.important)
            assertNull(app.appUpdates.state.value.offerSheet)
            assertFalse(prefs.getBoolean("sheet-99999", false))
        } finally { activity.pause().stop().destroy() }
        AppPreferences(app).markOnboardingSeen()
        val reopened = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertNotNull(app.appUpdates.state.value.offerSheet) }
        finally { reopened.pause().stop().destroy() }
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
