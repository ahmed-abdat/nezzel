package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.ProbeFacts
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded process pool. Platform readers can ignore interruption; never replace a stuck thread. */
internal class SavedProbeExecutor(private val executor: ThreadPoolExecutor = shared) {
    class Ticket internal constructor(private val executor: ThreadPoolExecutor, timeoutMs: Long,
        probe: () -> ProbeFacts?) {
        private val started = System.nanoTime()
        private val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(1, CHECK_TIMEOUT_MS))
        private val result = CompletableDeferred<ProbeFacts?>()
        private val task = Runnable {
            if (remaining() <= 0 || result.isCompleted) return@Runnable
            val facts = try { probe() } catch (_: Throwable) { null }
            result.complete(facts.takeIf { remaining() > 0 })
        }
        init {
            try { executor.execute(task) } catch (_: RejectedExecutionException) { result.complete(null) }
        }
        private fun remaining() = budget - (System.nanoTime() - started)
        suspend fun await(): ProbeFacts? {
            val left = remaining()
            if (left <= 0) { result.complete(null); executor.remove(task); return null }
            val facts = withTimeoutOrNull((left / 1_000_000).coerceAtLeast(1)) { result.await() }
            if (!result.isCompleted) result.complete(null)
            executor.remove(task)
            return facts.takeIf { remaining() > 0 }
        }
    }
    fun submit(timeoutMs: Long, probe: () -> ProbeFacts?) = Ticket(executor, timeoutMs, probe)

    companion object {
        internal fun newExecutor() = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "nezzel-check").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
        private val shared = newExecutor()
    }
}
