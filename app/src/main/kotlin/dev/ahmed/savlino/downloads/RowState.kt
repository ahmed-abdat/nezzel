package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress

/** A new restart epoch (staging wiped, transfer starts over) before its first byte sample: no percent, no old numbers. */
internal val DownloadProgress.restarting: Boolean get() = restartEpoch > 0 && !hasPartial && !paused && !terminal &&
    bytes == null && phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)

/** Queued with bytes on disk while the device is offline. Online, such a row is just queued. */
internal fun DownloadProgress.waitingForNetwork(offline: Boolean): Boolean = offline && !terminal && !paused && waiting && hasPartial

/** Pause is offered only while bytes move; the coordinator refuses it from joining onward, so the button leaves then too. */
internal val DownloadProgress.canPause: Boolean get() = !terminal && !awaitingSchedule && !paused && !waiting && !restarting &&
    phase == DownloadPhase.DOWNLOADING

/** Paused and offline-waiting rows keep the last real fraction frozen; the live-progress gate (`visiblePercent`) stays strict. */
internal fun DownloadProgress.frozenFraction(offline: Boolean): Float? =
    if (paused || waitingForNetwork(offline)) percent?.let { it / 100f } else null
