package dev.ahmed.savlino.entry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The once-only notification ask, shared by the app and the share sheet. The flag stays in the file
 * `MainActivity.getPreferences()` always wrote, so a person who already answered is never asked again.
 */
object NotificationAsk {
    private const val FILE = "MainActivity"
    private const val ASKED = "notificationAsked"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun granted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun asked(context: Context) = prefs(context).getBoolean(ASKED, false)
    fun shouldAsk(context: Context) = !granted(context) && !asked(context)
    fun markAsked(context: Context) { prefs(context).edit().putBoolean(ASKED, true).apply() }
}
