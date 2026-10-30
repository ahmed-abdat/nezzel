package dev.ahmed.savlino.jobs

import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveUpdateManifestTest {
    @Test fun requestsPromotedNotificationsNextToPostNotifications() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(requested.toString(), "android.permission.POST_PROMOTED_NOTIFICATIONS" in requested)
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
    }

    /** Below Android 16 QPR2 the gate is closed without touching any API 36 member (no linkage error). */
    // SDK coverage: LiveUpdate.kt:16 short-circuits below API36; one pre-36 SDK exercises that branch.
    @Test @Config(sdk = [29]) fun promotionStaysOffBelowAndroid16() {
        org.junit.Assert.assertFalse(LiveUpdate.supported)
    }
}
