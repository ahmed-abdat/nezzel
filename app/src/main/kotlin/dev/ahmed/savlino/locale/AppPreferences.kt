package dev.ahmed.savlino.locale

import dev.ahmed.savlino.downloads.SavedSort
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // Portable user choices may follow a user to another installation.
    private val portable = context.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE)
    init {
        val missing = listOf("language", "theme").filter { !portable.contains(it) && prefs.contains(it) }
        if (missing.isNotEmpty()) portable.edit().apply {
            missing.forEach { key -> putString(key, prefs.getString(key, null)) }
        }.apply()
    }
    val parallelDownloads: Int get() = portable.getInt("parallel_downloads", 2).coerceIn(1, 3)
    private val mutableParallelDownloads = MutableStateFlow(parallelDownloads)
    val parallelDownloadsFlow = mutableParallelDownloads.asStateFlow()
    private val parallelListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "parallel_downloads" || key == null) mutableParallelDownloads.value = parallelDownloads
    }
    init { portable.registerOnSharedPreferenceChangeListener(parallelListener) }
    fun chooseParallelDownloads(n: Int) {
        val chosen = n.coerceIn(1, 3)
        portable.edit().putInt("parallel_downloads", chosen).apply()
        mutableParallelDownloads.value = chosen
    }
    var lastPreset: Preset?
        get() = Preset.entries.find { it.name == prefs.getString("lastPreset", null) && it != Preset.AUDIO_MP3 }
        set(value) { prefs.edit().putString("lastPreset", value?.name).apply {
            if (value != null) remove("lastHeight")
        }.apply() }
    /** A confirmed All qualities video pick replaces the older preset in the same write. */
    var lastHeight: Int?
        get() = prefs.getInt("lastHeight", 0).takeIf { it > 0 }
        set(value) { prefs.edit().apply {
            if (value != null && value > 0) { putInt("lastHeight", value); remove("lastPreset") }
            else remove("lastHeight")
        }.apply() }
    val storedLanguage: String? get() = portable.getString("language", null)?.takeIf { it == "ar" || it == "en" || it == "fr" }
    fun language(system: String, platformChoice: String?): String =
        platformChoice?.takeIf { it == "ar" || it == "en" || it == "fr" } ?: storedLanguage ?: phoneLanguage(system)
    fun chooseLanguage(language: String) {
        require(language == "ar" || language == "en" || language == "fr")
        portable.edit().putString("language", language).apply()
    }
    var savedSort: SavedSort
        get() = SavedSort.entries.firstOrNull { it.name == portable.getString("saved_sort", null) } ?: SavedSort.NEWEST
        set(value) { portable.edit().putString("saved_sort", value.name).apply() }
    /** Saved list shown as a grid for this filter (MediaFilter name): Images and Statuses default to grid, the rest to a list. */
    fun libraryGrid(filter: String): Boolean = portable.getBoolean("library_grid_$filter", filter == "IMAGE" || filter == "STATUSES")
    fun setLibraryGrid(filter: String, grid: Boolean) { portable.edit().putBoolean("library_grid_$filter", grid).apply() }
    var theme: ThemePref
        get() = ThemePref.entries.firstOrNull { it.name == portable.getString("theme", null) } ?: ThemePref.System
        set(value) { portable.edit().putString("theme", value.name).apply() }
    var matchWallpaper: Boolean
        get() = prefs.getBoolean("wallpaper", false)
        set(value) { prefs.edit().putBoolean("wallpaper", value).apply() }
    var suggestionsEnabled: Boolean
        get() = prefs.getBoolean("suggestions", true)
        set(value) { prefs.edit().putBoolean("suggestions", value).apply() }
    var appUpdates: Boolean
        get() = prefs.getBoolean("app_updates", true)
        set(value) { prefs.edit().putBoolean("app_updates", value).commit() }
    var tips: Boolean
        get() = prefs.getBoolean("tips", true)
        set(value) {
            if (value && !tips) tipsHistory.edit().putInt("ignored", 0).commit()
            prefs.edit().putBoolean("tips", value).commit()
        }
    private val tipsHistory = context.getSharedPreferences("tips", Context.MODE_PRIVATE)
    /** "Help improve Nezzel": the daily usage counts and failure/crash reports. */
    var helpImprove: Boolean
        get() = prefs.getBoolean("help_improve", true)
        set(value) { prefs.edit().putBoolean("help_improve", value).commit() }
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
    companion object { fun phoneLanguage(system: String) = when (system) { "ar" -> "ar"; "fr" -> "fr"; else -> "en" } }
}
