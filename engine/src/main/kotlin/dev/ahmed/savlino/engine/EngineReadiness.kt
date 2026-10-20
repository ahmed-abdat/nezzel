package dev.ahmed.savlino.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Native readiness deadline, started after app maintenance admission and inherited by nested engine calls. */
object EngineReadiness {
    private class Budget(val deadline: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Budget>
    }
    suspend fun <T> within(waitMs: Long, block: suspend () -> T): T {
        if (currentCoroutineContext()[Budget] != null) return block()
        return withContext(Budget(System.nanoTime() + waitMs * 1_000_000)) { block() }
    }
    /** Only gate acquisition uses this deadline; admitted metadata/transfer work has its own limits. */
    suspend fun remainingMillis(limitMs: Long): Long = currentCoroutineContext()[Budget]?.let {
        val nanos = it.deadline - System.nanoTime()
        if (nanos <= 0) 0 else minOf(limitMs, (nanos + 999_999) / 1_000_000)
    } ?: limitMs
}
