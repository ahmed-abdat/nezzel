package dev.ahmed.savlino.downloads

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/** Drain real provider work by completion signals, including deliberately parked recreation calls. */
internal class LibraryTestWork(private val compose: ComposeTestRule) : CoroutineDispatcher(), Closeable {
    private class Work {
        val settled = CountDownLatch(1)
        val completed = CountDownLatch(1)
        @Volatile var gate: CountDownLatch? = null
    }
    private val pending = ConcurrentLinkedQueue<Work>()
    private val current = ThreadLocal<Work>()
    private val threads = ConcurrentLinkedQueue<Thread>()
    // Parked provider calls must not occupy the worker needed by an independent URI retry.
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "library-test-io").also { threads.add(it) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val work = Work()
        pending.add(work)
        executor.execute {
            current.set(work)
            try { block.run() } finally {
                current.remove()
                work.completed.countDown()
                work.settled.countDown()
            }
        }
    }

    fun park(gate: CountDownLatch) {
        if (gate.count == 0L) return
        val work = checkNotNull(current.get()) { "Provider gate must run on LibraryTestWork" }
        work.gate = gate
        work.settled.countDown()
        gate.await()
    }

    private fun idleIo() {
        do {
            for (work in pending.toList()) {
                work.settled.await()
                // A parked call settles only as far as its explicit release signal allows.
                if (work.gate?.count == 0L) work.completed.await()
                if (work.completed.count == 0L) pending.remove(work)
            }
            // A completed runnable can enqueue more IO; drain that too before inspecting effects.
        } while (pending.any { (it.gate?.count ?: 0L) == 0L })
    }

    private fun turn() {
        idleIo()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        idleIo()
    }

    fun drain() { repeat(8) { turn() } }

    fun await(check: () -> Boolean) {
        repeat(64) {
            turn()
            if (check()) {
                // Commit the state that emitted the signal before the test delivers another result.
                // Otherwise consent can set batchWaiting false before Compose ever observes true.
                turn()
                if (check()) return
            }
        }
        fail("Library condition did not settle after 64 Compose/IO turns")
    }

    override fun close() {
        if (executor.isShutdown) return
        // Activities are destroyed before teardown; do not wait for a Compose root that is gone.
        repeat(8) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            idleIo()
        }
        idleIo()
        check(pending.none { it.completed.count != 0L }) { "Release all provider gates before closing" }
        executor.shutdown()
        threads.forEach { it.join() }
    }
}
