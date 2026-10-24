package dev.ahmed.savlino.usage

import android.content.Context
import android.os.Build
import dev.ahmed.savlino.locale.AppPreferences
import java.util.Locale

/** Once per ping: settings, Android version, install age, brand (never the model), screen class and app language. */
internal class PhoneSnapshot(private val context: Context, private val preferences: AppPreferences,
                             private val now: () -> Long = System::currentTimeMillis) : UsageSnapshot {
    override fun keys(): List<String> {
        val installed = try { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }
            catch (_: Exception) { null }
        fun onOff(value: Boolean) = if (value) "on" else "off"
        return listOfNotNull(
            "set.lang." + (preferences.storedLanguage ?: "auto"),
            "set.theme." + preferences.theme.name.lowercase(),
            "set.autoload." + onOff(preferences.automaticOptions),
            "set.parallel." + preferences.parallelDownloads,
            "set.wallpaper." + onOff(preferences.matchWallpaper),
            "lang." + preferences.language(Locale.getDefault().language, null),
            UsageKeys.SDK + Build.VERSION.SDK_INT,
            installed?.let { UsageKeys.age((now() - it).coerceAtLeast(0) / 86_400_000L) },
            UsageKeys.brand(Build.MANUFACTURER),
            UsageKeys.screen(context.resources.configuration.smallestScreenWidthDp),
        )
    }
}
