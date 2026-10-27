package dev.ahmed.savlino.usage

import android.content.SharedPreferences

/**
 * Jobs whose end was already counted, so a retried, resumed or replayed job never counts a second end. Kept on the phone
 * only (never sent) and across process death. A key leaves only once its job is gone (a job that can still be retried
 * keeps it), checked once per process and again whenever the set doubles past [MAX].
 */
class CountedEnds(private val prefs: SharedPreferences, private val exists: (String) -> Boolean) {
    private var pruneAt = 0

    /** True only the first time for [key]. Writes synchronously: never call it on the main thread. */
    @Synchronized fun first(key: String): Boolean {
        if (prefs.contains(key)) return false
        val edit = prefs.edit()
        val keys = prefs.all.keys
        if (keys.size >= pruneAt) {
            val gone = keys.filterNot(exists)
            gone.forEach { edit.remove(it) }
            pruneAt = maxOf(MAX, (keys.size - gone.size) * 2)
        }
        // commit, not apply: death before an async flush would let a Retry count a second end.
        edit.putBoolean(key, true).commit()
        return true
    }

    companion object {
        const val PREFS = "usage_counted_ends"
        const val MAX = 500
    }
}
