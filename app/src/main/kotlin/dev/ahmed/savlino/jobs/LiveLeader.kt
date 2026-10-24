package dev.ahmed.savlino.jobs

/** The one job the widget and the Live Update both show: the newest running one, so both always name the same job. */
object LiveLeader {
    fun pick(jobs: List<DownloadProgress>): DownloadProgress? = jobs.lastOrNull { it.running }
}
