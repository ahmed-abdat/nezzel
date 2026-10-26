package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.ProbeFacts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SavedProbeExecutorTest {
    @Test fun oneHungReaderDoesNotPreventAnotherCheckAndSaturationStaysBounded() = runBlocking {
        val executor = SavedProbeExecutor.newExecutor()
        val checks = SavedProbeExecutor(executor)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val one = checks.submit(100) { entered.countDown(); release.await(); ProbeFacts(1, 1) }
            // A second worker remains usable when the first reader ignores cancellation.
            assertEquals(ProbeFacts(1, 1), withTimeout(1000) { checks.submit(1000) { ProbeFacts(1, 1) }.await() })
            val two = checks.submit(100) { entered.countDown(); release.await(); ProbeFacts(1, 1) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val waiting = List(4) { checks.submit(100) { error("expired queued probe must not run") } }
            val rejected = checks.submit(1000) { error("saturated executor must reject this probe") }
            assertNull(rejected.await())
            assertNull(one.await()); assertNull(two.await())
            waiting.forEach { assertNull(it.await()) }
            assertEquals(2, executor.largestPoolSize)
            assertTrue(executor.queue.isEmpty())
            release.countDown()
            assertNull(one.await()) // no late verdict resurrected after deadline
        } finally {
            release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
