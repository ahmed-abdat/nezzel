package dev.ahmed.savlino.jobs

import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel

/** Replaces raw-event enqueueing. Only adjacent transfer samples are replaceable, never phase barriers. */
internal class ProgressDelivery(private val clock: () -> Long,
    private val publish: (DownloadProgress) -> Unit) {
    // This channel carries wake-ups only, never progress/control events.
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val pending = ArrayDeque<DownloadProgress>()
    private var latest: DownloadProgress? = null
    private var published: DownloadProgress? = null
    private var publishedAt: Long? = null
    private var reported: DownloadProgress? = null
    private var reportedAt: Long? = null
    private var closed = false

    val pendingTransferSamples: Int
        @Synchronized get() = pending.count { it.phase == DownloadPhase.DOWNLOADING }

    @Synchronized fun offer(event: DownloadProgress) {
        if (closed || event.restartEpoch < (latest?.restartEpoch ?: 0)) return
        if (event.restartEpoch > (latest?.restartEpoch ?: 0)) {
            pending.clear()
            latest = null; published = null; publishedAt = null; reported = null; reportedAt = null
        }
        val phaseChanged = latest?.phase != event.phase
        if (phaseChanged) latest?.takeIf { it.phase == DownloadPhase.DOWNLOADING }?.let(::publishNow)
        latest = event
        if (event.phase == DownloadPhase.DOWNLOADING && pending.peekLast()?.phase == DownloadPhase.DOWNLOADING) {
            pending.removeLast()
        }
        pending.addLast(event)
        if (phaseChanged || event.phase != DownloadPhase.DOWNLOADING || due(clock(), publishedAt, 250)) {
            publishNow(event)
        }
        if (event.terminal) closed = true
        changed.trySend(Unit)
    }

    /** Timer and engine share the same admission lock, pending sample and display budget. */
    @Synchronized fun refresh(transform: (DownloadProgress) -> DownloadProgress) {
        if (!closed) latest?.takeIf { it.phase == DownloadPhase.DOWNLOADING }?.let { offer(transform(it)) }
    }

    @Synchronized fun close() {
        if (closed) return
        // Cancellation/failure can end directly from transfer; clear both display and pending flush.
        latest?.takeIf { it.phase == DownloadPhase.DOWNLOADING }?.let { sample ->
            val cleared = sample.copy(bytesPerSecond = null, remainingTransferSeconds = null, stalled = null)
            if (cleared != sample) offer(cleared)
        }
        latest?.let(::publishNow)
        closed = true
        changed.trySend(Unit)
    }

    suspend fun consume(report: suspend (DownloadProgress) -> Unit) {
        while (true) {
            var done = false
            val event = synchronized(this) {
                val head = pending.peekFirst()
                when {
                    head == null -> { done = closed; null }
                    // A phase barrier or close flushes the last transfer sample before proceeding.
                    head.phase != DownloadPhase.DOWNLOADING || reported?.phase != head.phase ||
                        pending.size > 1 || closed || due(clock(), reportedAt, 250) -> pending.removeFirst()
                    else -> null
                }
            }
            if (event != null) {
                report(event) // Never suspend under the admission lock.
                synchronized(this) { reported = event; reportedAt = clock() }
            } else if (done) return
            else changed.receive()
        }
    }

    private fun publishNow(event: DownloadProgress) {
        if (event == published) return
        publish(event)
        published = event
        publishedAt = clock()
    }
}

/** The WorkManager/notification sink has a separate1s budget and one latest transfer sample. */
internal class ProgressPersistence(private val clock: () -> Long,
    private val write: suspend (DownloadProgress) -> Unit) {
    private var last: DownloadProgress? = null
    private var writtenAt: Long? = null
    private var pending: DownloadProgress? = null

    suspend fun report(event: DownloadProgress) {
        val epoch = maxOf(last?.restartEpoch ?: 0, pending?.restartEpoch ?: 0)
        if (event.restartEpoch < epoch) return
        if (event.restartEpoch > epoch) { last = null; pending = null; writtenAt = null }
        if (event.phase == DownloadPhase.DOWNLOADING) {
            if (event == last && pending == null) return
            if (last?.phase == event.phase && !due(clock(), writtenAt, 1000)) {
                pending = event
                return
            }
            pending = null
        } else {
            pending?.let { flush(it) }
            pending = null
        }
        flush(event)
    }

    private suspend fun flush(event: DownloadProgress) {
        write(event)
        last = event
        writtenAt = clock()
    }
}

private fun due(now: Long, before: Long?, interval: Long): Boolean {
    if (before == null) return true
    return try { Math.subtractExact(now, before) >= interval } catch (_: ArithmeticException) { false }
}
