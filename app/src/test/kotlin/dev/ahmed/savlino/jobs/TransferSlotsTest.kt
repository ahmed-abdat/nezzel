package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TransferSlotsTest {
    @Test fun waitersUseCreatedAtAndLimitChangesDoNotInterruptRunningJobs() = runBlocking {
        val limit = MutableStateFlow(1)
        val slots = TransferSlots(limit)
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) {
            slots.withSlot("first", 0) { first.complete(Unit); release.await() }
        }
        first.await()
        val newer = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("newer", 20) { order += "newer" } }
        val older = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("older", 10) { order += "older" } }
        limit.value = 2
        withTimeout(5000) { older.join(); newer.join() }
        assertEquals(listOf("older", "newer"), order)
        limit.value = 1
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("last", 30) { order += "last" } }
        yield(); assertFalse(waiting.isCompleted); assertFalse(running.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { running.join(); waiting.join() }
        assertEquals(listOf("older", "newer", "last"), order)
    }

    @Test fun loweringLimitWaitsForAllExcessWritersToExit() = runBlocking {
        val limit = MutableStateFlow(2); val slots = TransferSlots(limit)
        val releaseOne = CompletableDeferred<Unit>(); val releaseTwo = CompletableDeferred<Unit>()
        val one = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("one", 0) { releaseOne.await() } }
        val two = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("two", 1) { releaseTwo.await() } }
        limit.value = 1
        var entered = false
        val three = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("three", 2) { entered = true } }
        releaseOne.complete(Unit); one.join()
        assertFalse(entered); assertFalse(two.isCompleted)
        releaseTwo.complete(Unit)
        withTimeout(5000) { two.join(); three.join() }
        assertTrue(entered)
    }

    @Test fun cancellationAndExpiryRemoveWaitingRequests() = runBlocking {
        val slots = TransferSlots(MutableStateFlow(1))
        val release = CompletableDeferred<Unit>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("running", 0) { release.await() } }
        var entered = false
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("cancelled", 1) { entered = true } }
        val expired = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("expired", 2) { entered = true } }
        cancelled.cancelAndJoin()
        slots.removeWaiting("expired")
        withTimeout(5000) { expired.join() }
        assertFalse(entered); assertFalse(running.isCompleted)
        release.complete(Unit); running.join()
        slots.withSlot("next", 3) { entered = true }
        assertTrue(entered)
    }

    @Test fun staleRowsAreRecheckedAfterAdmissionAndBeforeWaiting() = runBlocking {
        val slots = TransferSlots(MutableStateFlow(1))
        val release = CompletableDeferred<Unit>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("running", 0) { release.await() } }
        var enqueued = true; var entered = false
        val stale = launch(start = CoroutineStart.UNDISPATCHED) {
            slots.withSlot("stale", 1, eligible = { enqueued }) { entered = true }
        }
        enqueued = false
        val alreadyExpired = launch(start = CoroutineStart.UNDISPATCHED) {
            slots.withSlot("expired", 2, eligible = { false }) { entered = true }
        }
        withTimeout(5000) { alreadyExpired.join() }
        assertFalse(running.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { running.join(); stale.join() }
        assertFalse(entered)
        slots.withSlot("next", 3) { entered = true }
        assertTrue(entered)
    }

    @Test fun expiryRacingAdmissionAndCancellationNeverLeaksAPermit() = runBlocking {
        val slots = TransferSlots(MutableStateFlow(1))
        repeat(25) { attempt ->
            val release = CompletableDeferred<Unit>(); val queued = CompletableDeferred<Unit>()
            val enqueued = java.util.concurrent.atomic.AtomicBoolean(true)
            var entered = false
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                slots.withSlot("running-$attempt", 0) { release.await() }
            }
            val waiter = launch(Dispatchers.Default) {
                slots.withSlot("expired-$attempt", 1, eligible = { queued.complete(Unit); enqueued.get() }) { entered = true }
            }
            withTimeout(5000) { queued.await() }
            enqueued.set(false)
            coroutineScope {
                launch(Dispatchers.Default) { slots.removeWaiting("expired-$attempt") }
                launch(Dispatchers.Default) { waiter.cancel() }
                launch(Dispatchers.Default) { release.complete(Unit) }
            }
            withTimeout(5000) { running.join(); waiter.join() }
            assertFalse(entered)
            withTimeout(5000) { slots.withSlot("next-$attempt", 2) { entered = true } }
            assertTrue(entered)
        }
    }

    @Test fun overlappingAttemptsOfTheSameJobWaitWithoutBlockingOtherJobs() = runBlocking {
        val slots = TransferSlots(MutableStateFlow(2))
        val release = CompletableDeferred<Unit>(); var duplicateEntered = false; var otherEntered = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("same", 0) { release.await() } }
        val duplicate = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("same", 0) { duplicateEntered = true } }
        slots.withSlot("other", 1) { otherEntered = true }
        assertTrue(otherEntered); assertFalse(duplicateEntered)
        release.complete(Unit)
        withTimeout(5000) { first.join(); duplicate.join() }
        assertTrue(duplicateEntered)
    }

    @Test fun severeHeatRestrictsNewAdmissionsUntilCooling() = runBlocking {
        val heat = MutableStateFlow(4)
        val slots = TransferSlots(MutableStateFlow(3), heat)
        val release = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("first", 0) { release.await() } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { slots.withSlot("second", 1) { secondEntered.complete(Unit) } }
        yield(); assertFalse(secondEntered.isCompleted)
        heat.value = 0
        withTimeout(5000) { secondEntered.await(); second.join() }
        release.complete(Unit); first.join()
    }
    @Test fun concurrentSpaceChecksReservePublicationBudgetAtomicallyAndReleaseOnExit() = runBlocking {
        val ledger = TransferSpaceLedger()
        val space = SpaceBudget(File("stage"), File("public"), FreeSpace { 1536L * 1024 * 1024 })
        val format = Format("single", "mp4", true, true, size = Size.Exact(512L * 1024 * 1024))
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val results = (1..2).map { n -> async(Dispatchers.Default) {
            ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
            try { ledger.reserve("job-$n", space, format); true }
            catch (failure: StorageException) { assertEquals(StorageError.InsufficientSpace, failure.error); false }
        } }
        check(ready.await(5, TimeUnit.SECONDS)); start.countDown()
        assertEquals(listOf(false, true), results.awaitAll().sorted())
        // Removing both is safe even for the rejected job; there must be no leaked budget.
        ledger.release("job-1"); ledger.release("job-2")
        ledger.reserve("next", space, format)
        // Recovery starts a new empty ledger, independent of the dead process's reservations.
        TransferSpaceLedger().reserve("recovered", space, format)
    }

    @Test fun spaceLedgerCountsOthersByWhatTheyStillNeedNowAndAgainAfterARestart() {
        val mib = 1024L * 1024
        val staged = mutableMapOf("a" to 0L)
        val ledger = TransferSpaceLedger { staged[it] ?: 0L }
        val unknown = Format("any", "mp4", true, true, size = Size.Unknown)
        var free = 2560 * mib
        val space = SpaceBudget(File("stage"), File("public"), FreeSpace { free })
        ledger.reserve("a", space, unknown)
        // A wrote 900 MiB: free space already dropped by it, so A's share is only what is left of its 1 GiB.
        staged["a"] = 900 * mib; free -= 900 * mib
        ledger.reserve("b", space, unknown)
        ledger.release("b")
        // A restart clears A's staging: its whole budget counts again.
        staged["a"] = 0; free += 900 * mib - 1100 * mib
        try { ledger.reserve("b", space, unknown); fail("a restarted job's full budget was not counted") }
        catch (failure: StorageException) { assertEquals(StorageError.InsufficientSpace, failure.error) }
    }

    @Test fun spaceLedgerKeepsMergeAndPublicationBytesWhenResuming() {
        val ledger = TransferSpaceLedger()
        val format = Format("merge", "mp4", true, true, size = Size.Exact(100),
            requiresMerge = true, audioFormatId = "audio")
        val space = SpaceBudget(File("stage"), File("custom-publication"), FreeSpace { 719 }, marginBytes = 0)
        ledger.reserve("one", space, format, stagedBytes = 40) // 60 + 200 + 100 = 360.
        try { ledger.reserve("two", space, format, stagedBytes = 40); fail("two 360-byte budgets admitted into 719") }
        catch (failure: StorageException) { assertEquals(StorageError.InsufficientSpace, failure.error) }
        ledger.release("one")
        ledger.reserve("two", space, format, stagedBytes = 40)
    }

}
