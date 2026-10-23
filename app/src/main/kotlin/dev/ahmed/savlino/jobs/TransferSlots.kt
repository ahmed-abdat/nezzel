package dev.ahmed.savlino.jobs

import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Limits admissions only: changing the limit never interrupts an admitted writer. */
internal class TransferSlots(private val limit: StateFlow<Int>,
    private val thermalStatus: StateFlow<Int> = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)) {
    private class Request(val id: String, val createdAt: Long, val order: Long) {
        val admitted = CompletableDeferred<Boolean>()
    }
    private val lock = Any()
    private val waiting = mutableListOf<Request>()
    private val running = mutableSetOf<Request>()
    private var sequence = 0L

    suspend fun <T> withSlot(id: String, createdAt: Long, eligible: () -> Boolean = { true },
        block: suspend () -> T): T? = coroutineScope {
        val request = synchronized(lock) {
            Request(id, createdAt, sequence++).also { waiting += it; admit() }
        }
        val changes = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(limit, thermalStatus) { _, _ -> Unit }.collect { synchronized(lock) { admit() } }
        }
        try {
            // Outside the slot monitor: expiry callbacks run while the journal holds its own lock.
            // Registration first closes the race where expiry happened just before we joined the queue.
            if (!eligible()) return@coroutineScope null
            if (!request.admitted.await()) return@coroutineScope null
            currentCoroutineContext().ensureActive()
            if (!eligible()) return@coroutineScope null
            block()
        } finally {
            withContext(NonCancellable) {
                changes.cancelAndJoin()
                synchronized(lock) { waiting.remove(request); running.remove(request); admit() }
            }
        }
    }

    fun removeWaiting(id: String) = synchronized(lock) {
        waiting.filter { it.id == id }.forEach { waiting.remove(it); it.admitted.complete(false) }
    }

    private fun admit() {
        val count = if (thermalStatus.value >= PowerManager.THERMAL_STATUS_SEVERE) 1 else limit.value.coerceIn(1, 3)
        while (running.size < count) {
            val request = waiting.filter { candidate -> running.none { it.id == candidate.id } }
                .minWithOrNull(compareBy<Request> { it.createdAt }.thenBy { it.order }) ?: break
            waiting.remove(request)
            running += request
            request.admitted.complete(true)
        }
    }
}
