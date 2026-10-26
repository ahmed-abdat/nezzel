package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Size
import java.util.ArrayDeque
import kotlin.math.ceil

/** Terminal accounting, independent of display ticks. The first counter is a baseline, including resume bytes. */
internal class AttemptTransferSummary(private val clock: () -> Long) {
    private var firstTime: Long? = null
    private var lastTime: Long? = null
    private var firstBytes = 0L
    private var lastBytes = 0L

    @Synchronized fun observe(bytes: Long) {
        val now = clock()
        if (bytes < 0) { reset(); return }
        if (firstTime == null || bytes < lastBytes || lastTime?.let { now < it } == true) {
            firstTime = now; firstBytes = bytes
        }
        lastTime = now; lastBytes = bytes
    }

    @Synchronized fun bytesPerSecond(): Double? {
        val duration = elapsed(lastTime ?: return null, firstTime ?: return null) ?: return null
        val received = lastBytes - firstBytes
        return if (duration >= 2000 && received > 0)
            (received.toDouble() / duration * 1000).takeIf { it.isFinite() && it > 0 } else null
    }

    @Synchronized fun reset() { firstTime = null; lastTime = null; firstBytes = 0; lastBytes = 0 }
}

/** In-memory measurements for one execute attempt. Counts are cumulative across all transfer streams. */
internal class TransferMetrics(private val clock: () -> Long) {
    data class Snapshot(val bytesPerSecond: Double? = null,
        val remainingTransferSeconds: Long? = null, val stalled: Boolean? = null)
    private data class Sample(val time: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()
    private var last: Sample? = null
    private var lastIncrease: Long? = null
    private var total: Size = Size.Unknown
    private var value = Snapshot()

    fun observe(bytes: Long, total: Size): Snapshot {
        if (bytes < 0) {
            clear()
            value = Snapshot(stalled = false)
            return value
        }
        return measure(clock(), bytes, total)
    }

    fun tick(): Snapshot = last?.let { measure(clock(), it.bytes, total) } ?: value

    fun clear() {
        samples.clear()
        last = null
        lastIncrease = null
        total = Size.Unknown
        value = Snapshot()
    }

    private fun measure(now: Long, bytes: Long, total: Size): Snapshot {
        val previous = last
        // Clock resolution can make a real counter reset share the previous sample's timestamp.
        if (previous != null && bytes < previous.bytes) return start(now, bytes, total)
        if (previous != null && now <= previous.time) return value
        if (previous == null || elapsed(now, previous.time) == null) {
            return start(now, bytes, total)
        }
        val idle = lastIncrease?.let { elapsed(now, it) }
        if (value.stalled == true || idle == null || idle >= 5000) {
            // A returning byte begins a fresh useful window, never revives the pre-stall average.
            if (bytes > previous.bytes) return start(now, bytes, total)
            samples.clear()
            last = Sample(now, bytes)
            this.total = total
            value = Snapshot(stalled = true)
            return value
        }
        if (bytes > previous.bytes) lastIncrease = now
        val current = Sample(now, bytes)
        last = current
        this.total = total
        samples.addLast(current)
        // At millisecond resolution there can be at most8001 distinct points in this window.
        while (samples.isNotEmpty() && (elapsed(now, samples.first.time) ?: Long.MAX_VALUE) > 8000) {
            samples.removeFirst()
        }
        val first = samples.peekFirst() ?: return Snapshot(stalled = false).also { value = it }
        val duration = elapsed(now, first.time) ?: 0
        val received = bytes - first.bytes
        val rate = if (duration >= 2000 && received > 0) {
            (received.toDouble() / duration * 1000).takeIf { it.isFinite() && it > 0 }
                ?.coerceAtMost(Long.MAX_VALUE.toDouble())
        } else null
        val exact = (total as? Size.Exact)?.bytes
        val remaining = if (rate != null && exact != null && exact > bytes) {
            // Subtract before conversion: adjacent huge Long counters must still have one byte remaining.
            val seconds = ceil((exact - bytes).toDouble() / rate)
            if (!seconds.isFinite() || seconds >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE
            else seconds.toLong().coerceAtLeast(1)
        } else null
        value = Snapshot(rate, remaining, false)
        return value
    }

    private fun start(now: Long, bytes: Long, total: Size): Snapshot {
        samples.clear()
        last = Sample(now, bytes).also { samples.addLast(it) }
        lastIncrease = now
        this.total = total
        value = Snapshot(stalled = false)
        return value
    }
}

/** Null on backward/overflowing elapsed time; no wall-clock correction can create throughput. */
private fun elapsed(now: Long, before: Long): Long? =
    try { Math.subtractExact(now, before).takeIf { it >= 0 } } catch (_: ArithmeticException) { null }
