package dev.ahmed.savlino.tips

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import dev.ahmed.savlino.MainActivity
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.statuses.StatusFolders
import java.util.concurrent.TimeUnit

/** Local reminders only. These preferences never enter the usage/report stores. */
internal class Tips(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("tips", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    val history get() = TipHistory(prefs.getLong("last-open", 0), prefs.getInt("sent", 0),
        prefs.getLong("last-tip", 0), prefs.getInt("next-feature", 0), prefs.getInt("ignored", 0))

    @Synchronized fun onOpen(time: Long = now(), fromTip: Boolean = false) {
        val opened = history.opened(time, fromTip)
        prefs.edit().putLong("last-open", time).putInt("sent", 0).putInt("ignored", opened.ignored).commit()
        manager.cancel(ID)
    }
    fun schedule() = WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<TipsWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())

    @Synchronized fun check(busy: Boolean) {
        val localized = AppLocale.localized(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, localized.getString(R.string.tips_title), NotificationManager.IMPORTANCE_DEFAULT))
        val allowed = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val before = history
        val time = now()
        if (!TipsPolicy.due(before, time, AppPreferences(context).tips, allowed, busy)) return
        val whatsapp = StatusFolders.PACKAGES.any { name ->
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
                    else @Suppress("DEPRECATION") context.packageManager.getApplicationInfo(name, 0)
                info.enabled
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
        val index = TipsPolicy.feature(before.nextFeature, whatsapp)
        val feature = FEATURES[index]
        val tap = Intent(context, MainActivity::class.java).setAction(OPEN)
        val notification = NotificationCompat.Builder(localized, CHANNEL).setSmallIcon(R.drawable.ic_stat_nezzel)
            .setContentTitle(localized.getString(feature.first)).setContentText(localized.getString(feature.second))
            .setStyle(NotificationCompat.BigTextStyle().bigText(localized.getString(feature.second))).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, ID, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        // Reserve before posting: a killed worker must not resend the same reminder on its retry.
        if (!prefs.edit().putInt("sent", before.sent + 1).putLong("last-tip", time).putInt("next-feature", (index + 1) % FEATURES.size).commit()) return
        try { manager.notify(ID, notification) } catch (_: SecurityException) { /* Permission may change after the check. */ }
    }
    companion object {
        const val WORK = "tips"
        const val CHANNEL = "tips"
        const val ID = 71204
        const val OPEN = "dev.ahmed.savlino.OPEN_TIP"
        val FEATURES = listOf(R.string.tip_links_title to R.string.tip_links_body,
            R.string.tip_playlist_title to R.string.tip_playlist_body, R.string.tip_mp3_title to R.string.tip_mp3_body,
            R.string.tip_statuses_title to R.string.tip_statuses_body, R.string.tip_widget_title to R.string.tip_widget_body,
            R.string.tip_pause_title to R.string.tip_pause_body)
    }
}

class TipsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NezzelApplication
        app.tips.check(app.downloads.hasQueuedOrRunning())
        return Result.success()
    }
}
