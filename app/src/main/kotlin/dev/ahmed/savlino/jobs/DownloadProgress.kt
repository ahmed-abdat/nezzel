package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size

enum class DownloadPhase { PREPARING, DOWNLOADING, JOINING, CONVERTING, SAVING, COMPLETED, FAILED, CANCELLED }
data class DownloadProgress(val jobId: String, val filename: String, val phase: DownloadPhase,
    val bytes: Long? = null, val total: Size = Size.Unknown, val error: DownloadError? = null,
    val publishedUri: android.net.Uri? = null, val mimeType: String = "video/mp4", val qualityHeight: Int? = null, val awaitingSchedule: Boolean = false, val waiting: Boolean = false, val retryable: Boolean = false,
    val bytesPerSecond: Double? = null, val remainingTransferSeconds: Long? = null, val stalled: Boolean? = null,
    val fragmentIndex: Long? = null, val fragmentCount: Long? = null,
    val paused: Boolean = false, val hasPartial: Boolean = false, val restartEpoch: Long = 0) {
    /** Byte estimates never supply a percentage; completed fragments can supply their own real fraction. */
    val percent: Int? get() {
        val fragments = fragmentCount?.takeIf { it > 0 }
        if (fragments != null && fragmentIndex != null && fragmentIndex in 0..fragments)
            return fractionPercent(fragmentIndex, fragments)
        val size = (total as? Size.Exact)?.bytes?.takeIf { it > 0 } ?: return null
        return bytes?.takeIf { it in 0..size }?.let { fractionPercent(it, size) }
    }
    /** Shared ring/text eligibility; stalled and queued work must not claim active measured progress. */
    val visiblePercent: Int? get() = percent?.takeIf { phase == DownloadPhase.DOWNLOADING && !waiting && stalled != true }
    /** A running byte denominator is usable only while the measured count fits it. */
    val exactTransferTotal: Long? get() = (total as? Size.Exact)?.bytes?.takeIf { size ->
        phase == DownloadPhase.DOWNLOADING && size > 0 && bytes?.let { it in 0..size } == true
    }
    private fun fractionPercent(done: Long, total: Long) =
        if (done == total) 100 else minOf(99, (done.toDouble() / total * 100).toInt())
    val terminal get() = phase in setOf(DownloadPhase.COMPLETED, DownloadPhase.FAILED, DownloadPhase.CANCELLED)
}

