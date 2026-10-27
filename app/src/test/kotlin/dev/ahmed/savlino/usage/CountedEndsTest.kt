package dev.ahmed.savlino.usage

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CountedEndsTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(CountedEnds.PREFS, Context.MODE_PRIVATE)

    @Test fun aCountedJobStaysCountedAfterTheProcessRestarts() {
        assertTrue(CountedEnds(prefs) { true }.first("job"))
        assertFalse(CountedEnds(prefs) { true }.first("job"))
        assertTrue(CountedEnds(prefs) { true }.first("other"))
    }

    @Test fun aJobThatStillExistsIsNeverEvictedEvenPastTheCap() {
        val jobs = (0..CountedEnds.MAX).map { "job-$it" }.toMutableSet()
        val ends = CountedEnds(prefs) { it in jobs }
        jobs.forEach { assertTrue(ends.first(it)) }
        assertEquals(CountedEnds.MAX + 1, prefs.all.size)
        assertFalse("The oldest job can still be retried: no second end", ends.first("job-0"))
        jobs.clear(); jobs += "last"
        assertTrue(CountedEnds(prefs) { it in jobs }.first("last"))
        assertEquals("A new process drops the gone jobs", setOf("last"), prefs.all.keys)
    }

    @Test fun existenceIsCheckedOncePerProcessNotOnEveryEnd() {
        var checks = 0
        val ends = CountedEnds(prefs) { checks++; true }
        repeat(20) { assertTrue(ends.first("job-$it")) }
        assertEquals("Only the first end prunes (nothing stored yet), later ones stay under the cap", 0, checks)
        val restarted = CountedEnds(prefs) { checks++; true }
        assertTrue(restarted.first("next"))
        assertEquals(20, checks)
        assertTrue(restarted.first("after"))
        assertEquals(20, checks)
    }
}
