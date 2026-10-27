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
        assertTrue(CountedEnds(prefs, { true }).first("job"))
        assertFalse(CountedEnds(prefs, { true }).first("job"))
        assertTrue(CountedEnds(prefs, { true }).first("other"))
    }

    @Test fun idsLeaveWhenTheirJobIsGoneAndNeverPassTheCap() {
        val jobs = (0 until CountedEnds.MAX).map { "job-$it" }.toMutableSet()
        var clock = 0L
        val ends = CountedEnds(prefs, { it in jobs }, { clock++ })
        jobs.forEach { assertTrue(ends.first(it)) }
        jobs += "new"
        assertTrue(ends.first("new"))
        assertEquals(CountedEnds.MAX, prefs.all.size)
        assertFalse("The oldest id makes room", prefs.contains("job-0"))
        jobs.clear(); jobs += "last"
        assertTrue(ends.first("last"))
        assertEquals(setOf("last"), prefs.all.keys)
    }
}
