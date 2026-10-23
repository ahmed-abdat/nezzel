package dev.ahmed.savlino.jobs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.work.WorkInfo
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError

/** Battery-limit stops (OS evidence only) and the settings page that lifts them. Needs no battery permission. */
object BatterySettings {
    /**
     * Job quota, background restriction, standby bucket. Never inferred from network errors.
     * DEVICE_STATE is left out: Android also uses it for memory pressure, so it proves nothing about battery.
     */
    private val batteryStopReasons = setOf(WorkInfo.STOP_REASON_QUOTA,
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_APP_STANDBY)

    @StringRes val errorText = R.string.download_battery_error
    @StringRes val buttonText = R.string.battery_settings
    @StringRes val samsungHint = R.string.battery_hint_samsung

    /** Stop reasons exist on API 31+ only; older releases report nothing we can trust. */
    fun batteryStopped(reason: Int, sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 31 && reason in batteryStopReasons

    /** Row button: only for an OS battery stop, and only while Nezzel is still battery optimized. */
    fun offersButton(error: DownloadError?, exempt: Boolean): Boolean = error == DownloadError.StoppedByBattery && !exempt

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Samsung also sleeps apps outside the system list; its menu names vary, so the hint stays one line. */
    fun showsSamsungHint(manufacturer: String = Build.MANUFACTURER): Boolean = manufacturer.equals("samsung", ignoreCase = true)

    /** Optimization list first, then the app info page. False when neither opens. */
    fun open(context: Context): Boolean {
        for (intent in intents(context.packageName)) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent); return true }
            catch (_: ActivityNotFoundException) {}
            catch (_: SecurityException) {}
        }
        return false
    }

    internal fun intents(packageName: String): List<Intent> = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}
