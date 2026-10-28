package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress

/** A restart (epoch >= 2: staging wiped, transfer starts over) before its first byte sample: no percent, no old numbers. Epoch 1 is a first start. */
internal val DownloadProgress.restarting: Boolean get() = restartEpoch > 1 && !hasPartial && !paused && !terminal &&
    bytes == null && phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)

/** Offline queued work waits for connectivity, including preparation before its first byte. A drop the coordinator already
 * saw counts at once, so the row never shows "Queued" while the offline signal settles. A transfer that stalls while
 * offline waits for the connection too, not for data. */
internal fun DownloadProgress.waitingForNetwork(offline: Boolean): Boolean = !terminal && !paused &&
    ((offline || networkWait) && waiting || offline && phase == DownloadPhase.DOWNLOADING && stalled == true)

/** Queued or preparing again with a kept partial: the row keeps the last real percent and size until bytes move. */
internal val DownloadProgress.keepsSample: Boolean get() = !terminal && !paused && hasPartial && bytes != null &&
    (waiting || phase == DownloadPhase.PREPARING)

/** Pause is offered only while bytes move; the coordinator refuses it from joining onward, so the button leaves then too. */
internal val DownloadProgress.canPause: Boolean get() = !terminal && !awaitingSchedule && !paused && !waiting && !restarting &&
    phase == DownloadPhase.DOWNLOADING

/** Paused, offline-waiting and resuming rows keep the last real fraction frozen; the live-progress gate (`visiblePercent`) stays strict. */
internal fun DownloadProgress.frozenFraction(offline: Boolean): Float? =
    if (paused || waitingForNetwork(offline) || keepsSample) percent?.let { it / 100f } else null
