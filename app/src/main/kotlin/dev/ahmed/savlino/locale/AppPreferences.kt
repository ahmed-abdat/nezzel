package dev.ahmed.savlino.locale

import android.content.Context
import dev.ahmed.savlino.domain.Preset
import org.json.JSONArray

enum class ThemePref { System, Light, Dark }

fun ThemePref.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemePref.System -> systemDark
    ThemePref.Light -> false
    ThemePref.Dark -> true
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("nezzel", Context.MODE_PRIVATE)
    val onboardingSeen: Boolean get() = prefs.getBoolean("onboarding_seen", false)
    fun markOnboardingSeen(): Boolean = prefs.edit().putBoolean("onboarding_seen", true).commit()
    /** History marks an upgrade permanently, even if the user later clears every job. */
    fun needsOnboarding(hasJobs: () -> Boolean): Boolean {
        if (onboardingSeen) return false
        if (!hasJobs()) return true
        markOnboardingSeen()
        return false
    }
    // Only these two cosmetic keys may follow a user to another installation.
    private val portable = context.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE)
    init {
        val missing = listOf("language", "theme").filter { !portable.contains(it) && prefs.contains(it) }
        if (missing.isNotEmpty()) portable.edit().apply {
            missing.forEach { key -> putString(key, prefs.getString(key, null)) }
        }.apply()
    }
    var lastPreset: Preset?
        get() = Preset.entries.find { it.name == prefs.getString("lastPreset", null) && it != Preset.AUDIO_MP3 }
        set(value) { prefs.edit().putString("lastPreset", value?.name).apply() }
    val storedLanguage: String? get() = portable.getString("language", null)?.takeIf { it == "ar" || it == "en" }
    fun language(system: String, platformChoice: String?): String =
        platformChoice?.takeIf { it == "ar" || it == "en" } ?: storedLanguage ?: phoneLanguage(system)
    fun chooseLanguage(language: String) {
        require(language == "ar" || language == "en")
        portable.edit().putString("language", language).apply()
    }
    var theme: ThemePref
        get() = ThemePref.entries.firstOrNull { it.name == portable.getString("theme", null) } ?: ThemePref.System
        set(value) { portable.edit().putString("theme", value.name).apply() }
    var matchWallpaper: Boolean
        get() = prefs.getBoolean("wallpaper", false)
        set(value) { prefs.edit().putBoolean("wallpaper", value).apply() }
    var suggestionsEnabled: Boolean
        get() = prefs.getBoolean("suggestions", true)
        set(value) { prefs.edit().putBoolean("suggestions", value).apply() }
    var automaticOptions: Boolean
        get() = prefs.getBoolean("automatic_options", true)
        set(value) { prefs.edit().putBoolean("automatic_options", value).apply() }
    var dismissedHashes: Set<String>
        get() {
            return try {
                val stored = prefs.getString("dismissed_ordered", null)
                val hashes = if (stored != null) {
                    val array = JSONArray(stored)
                    (0 until array.length()).map { array.get(it) as? String ?: return emptySet() }
                } else {
                    // Legacy StringSet has no chronology; preserve a deterministic bounded subset.
                    prefs.getStringSet("dismissed", emptySet()).orEmpty().sorted()
                }
                hashes.takeLast(50).toCollection(linkedSetOf())
            } catch (_: org.json.JSONException) { emptySet() }
            catch (_: ClassCastException) { emptySet() }
        }
        set(value) {
            storeDismissedHashes(value)
        }
    /** Automatic inspection waits for synchronous hash durability before any network work. */
    fun storeDismissedHashes(value: Set<String>): Boolean =
        prefs.edit().putString("dismissed_ordered", JSONArray(value.toList().takeLast(50)).toString())
            .remove("dismissed").commit()
    companion object { fun phoneLanguage(system: String) = if (system == "ar") "ar" else "en" }
}
