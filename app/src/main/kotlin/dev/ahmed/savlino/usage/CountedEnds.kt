package dev.ahmed.savlino.usage

import android.content.SharedPreferences

/**
 * Jobs whose end was already counted, so a retried, resumed or replayed job never counts a second end. Kept on the phone
 * only (never sent) and across process death; an id leaves once its job row is gone, and at most [MAX] stay.
 */
class CountedEnds(private val prefs: SharedPreferences, private val jobExists: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    /** True only the first time for [jobId]. */
    @Synchronized fun first(jobId: String): Boolean {
        if (prefs.contains(jobId)) return false
        val edit = prefs.edit()
        val kept = prefs.all.entries.filter { (id, _) -> jobExists(id).also { if (!it) edit.remove(id) } }
        kept.sortedBy { it.value as? Long ?: 0L }.take((kept.size - MAX + 1).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.putLong(jobId, now()).apply()
        return true
    }

    companion object {
        const val PREFS = "usage_counted_ends"
        const val MAX = 500
    }
}
