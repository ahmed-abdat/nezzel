package dev.ahmed.savlino.statuses

import android.net.Uri
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.mixed
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusCountsTest {
    @Test fun eachFileCountsOnceWhenItsCopyEnds() = runTest {
        val items = mixed()
        val fake = FakeStatusLibrary(ready(items))
        val answers = listOf(StatusSaveResult.Saved(Uri.parse("content://media/1")), StatusSaveResult.Failed, StatusSaveResult.Gone,
            StatusSaveResult.NoSpace, StatusSaveResult.Cancelled)
        fake.results = { answers[items.indexOf(it)] }
        val counts = mutableListOf<String>()
        val library = fake.counted(counts::add)
        library.saveOne(items[0])
        assertEquals(listOf(UsageKeys.STATUSES_SAVED), counts)
        items.subList(1, 5).forEach { library.saveOne(it) }
        assertEquals(listOf(UsageKeys.STATUSES_SAVED) + List(3) { UsageKeys.STATUSES_SAVE_FAILED }, counts)
        library.readyShown()
        assertEquals(UsageKeys.STATUSES_READY, counts.last())
    }

    @Test fun aCancelledCopyCountsNothing() = runTest {
        val items = mixed()
        val fake = FakeStatusLibrary(ready(items)).apply { hold = CompletableDeferred() }
        val counts = mutableListOf<String>()
        val copy = async { fake.counted(counts::add).saveOne(items[0]) }
        yield()
        copy.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList<String>(), counts)
    }
}
