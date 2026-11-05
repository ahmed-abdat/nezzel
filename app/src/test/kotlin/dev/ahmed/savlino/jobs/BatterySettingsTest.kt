package dev.ahmed.savlino.jobs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import dev.ahmed.savlino.domain.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BatterySettingsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun onlyBatteryStopReasonsMap() {
        for (reason in listOf(WorkInfo.STOP_REASON_QUOTA,
                WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_APP_STANDBY))
            assertTrue("$reason", BatterySettings.batteryStopped(reason, sdk = 31))
        for (reason in listOf(WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, WorkInfo.STOP_REASON_UNKNOWN,
                WorkInfo.STOP_REASON_NOT_STOPPED, WorkInfo.STOP_REASON_USER, WorkInfo.STOP_REASON_CANCELLED_BY_APP,
                WorkInfo.STOP_REASON_PREEMPT, WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
                WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW, WorkInfo.STOP_REASON_SYSTEM_PROCESSING))
            assertFalse("$reason", BatterySettings.batteryStopped(reason, sdk = 35))
    }

    @Test fun noStopReasonEvidenceBeforeApi31() {
        assertFalse(BatterySettings.batteryStopped(WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, sdk = 30))
    }

    @Test fun buttonOnlyForBatteryStopOrForegroundDenialWhileOptimized() {
        for (error in listOf(DownloadError.StoppedByBattery, DownloadError.ForegroundDenied)) {
            assertTrue(BatterySettings.offersButton(error, exempt = false))
            assertFalse(BatterySettings.offersButton(error, exempt = true))
        }
        for (error in listOf(DownloadError.Network, DownloadError.Offline, DownloadError.Interrupted,
                DownloadError.ExecutionRestricted, null))
            assertFalse("$error", BatterySettings.offersButton(error, exempt = false))
    }

    @Test fun batteryStopIsRetryable() = assertTrue(DownloadError.StoppedByBattery.retryable)

    @Test fun samsungHintOnlyOnSamsung() {
        assertTrue(BatterySettings.showsSamsungHint("samsung"))
        assertTrue(BatterySettings.showsSamsungHint("Samsung"))
        assertFalse(BatterySettings.showsSamsungHint("Google"))
    }

    @Test fun opensOptimizationListFirst() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) { started += intent } }
        assertTrue(BatterySettings.open(context))
        assertEquals(listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), started.map { it.action })
        assertTrue(started.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun fallsBackToAppDetails() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) throw ActivityNotFoundException()
                started += intent
            }
        }
        assertTrue(BatterySettings.open(context))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.single().action)
        assertEquals("package:${app.packageName}", started.single().dataString)
    }

    @Test fun reportsFalseWhenNothingOpens() {
        val context = object : ContextWrapper(app) { override fun startActivity(intent: Intent) = throw ActivityNotFoundException() }
        assertFalse(BatterySettings.open(context))
    }

    class RequestActivity : Activity() {
        val started = mutableListOf<Intent>()
        var reject: (Intent) -> Unit = {}
        override fun startActivity(intent: Intent) { started += intent; reject(intent) }
    }

    @Test fun requestsExemptionForThisPackageWithoutANewTask() {
        val controller = Robolectric.buildActivity(RequestActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(BatterySettings.requestExemption(activity))
            val intent = activity.started.single()
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
            assertEquals("package:${activity.packageName}", intent.dataString)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun refusedExemptionRequestFallsBackToListThenAppDetails() {
        val controller = Robolectric.buildActivity(RequestActivity::class.java).setup()
        try {
            val activity = controller.get()
            for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
                activity.started.clear()
                activity.reject = { intent ->
                    if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) throw failure
                }
                assertTrue(BatterySettings.requestExemption(activity))
                assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), activity.started.map { it.action })
                activity.started.clear()
                activity.reject = { intent ->
                    if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw failure
                }
                assertTrue(BatterySettings.requestExemption(activity))
                assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                    activity.started.map { it.action })
                assertEquals("package:${activity.packageName}", activity.started.last().dataString)
            }
            activity.reject = { throw SecurityException() }
            assertFalse(BatterySettings.requestExemption(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
