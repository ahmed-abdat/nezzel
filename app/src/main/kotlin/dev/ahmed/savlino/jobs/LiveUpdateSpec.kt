package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.locale.NezzelFormat

/** What the promoted notification shows: a real percent with its chip text, or a moving bar with no number. */
data class LiveSpec(val percent: Int?, val indeterminate: Boolean, val chip: String?)

/** All Live Update branching, free of Android types; null keeps today's notification. */
object LiveUpdateSpec {
    fun from(progress: DownloadProgress, isLeader: Boolean, canPost: Boolean, language: String): LiveSpec? {
        if (!canPost || !isLeader || !progress.running) return null
        // Bare percent for the tiny chip: no isolate marks, Latin digits as everywhere in the app.
        return progress.visiblePercent?.let { LiveSpec(it, false, NezzelFormat.percent(it, language)) }
            ?: LiveSpec(null, true, null)
    }
}
