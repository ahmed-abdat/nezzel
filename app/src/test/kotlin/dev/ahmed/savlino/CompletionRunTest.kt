package dev.ahmed.savlino

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Saved" snackbar's run: the same steps NezzelApp takes around its suspending share-target lookup. */
class CompletionRunTest {
    @Test fun aNewerFileFinishingDuringTheOlderLookupSpeaksForBoth() = runTest {
        val run = CompletionRun()
        val spoken = mutableListOf<Pair<String, Int>>()
        val lookups = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred<Unit>())
        fun finish(id: String) {
            run.finished(id)
            launch {
                if (!run.isLast(id)) return@launch
                lookups.getValue(id).await()
                spoken += id to (run.take(id) ?: return@launch)
            }
        }
        finish("a")
        testScheduler.runCurrent()
        finish("b")
        testScheduler.runCurrent()
        // Reversed: the newer lookup ends first, the older one after it.
        lookups.getValue("b").complete(Unit); testScheduler.runCurrent()
        lookups.getValue("a").complete(Unit); testScheduler.runCurrent()
        assertEquals(listOf("b" to 2), spoken)
    }

    @Test fun aProblemStartsANewRun() {
        val run = CompletionRun()
        run.finished("a"); run.problem(); run.finished("b")
        assertEquals(1, run.take("b"))
        assertEquals(null, run.take("a"))
    }
}
