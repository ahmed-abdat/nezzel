package dev.ahmed.savlino.update

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.ahmed.savlino.MainActivity
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.NezzelFormat

internal class UpdateNotifications(private val base: Context) {
    private val manager get() = base.getSystemService(NotificationManager::class.java)
    fun cancel() = manager.cancel(ID)
    fun post(release: Release): Boolean {
        if (!UpdatePolicy.enabled(base.packageName) ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(base, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled()) return false
        val context = AppLocale.localized(base)
        return try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val intent = Intent(base, MainActivity::class.java).setAction(OPEN)
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_nezzel)
                .setContentTitle(context.getString(R.string.update_notification_title, NezzelFormat.isolate(release.version)))
                .setContentText(context.getString(R.string.update_notification_text)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(base, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            true
        } catch (_: SecurityException) { false }
    }
    companion object { const val ID = 71203; const val CHANNEL = "app-updates"; const val OPEN = "dev.ahmed.savlino.OPEN_APP_UPDATE" }
}
