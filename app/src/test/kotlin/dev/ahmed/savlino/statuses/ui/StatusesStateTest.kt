package dev.ahmed.savlino.statuses.ui

import dev.ahmed.savlino.statuses.FakeStatusLibrary
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.MAIN
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.SECOND
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.item
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.mixed
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusGrantResult
import dev.ahmed.savlino.statuses.StatusSaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusesStateTest {
    private fun TestScope.loaded(library: FakeStatusLibrary) = StatusesState(library, this).also { it.refresh(); advanceUntilIdle() }

    @Test fun withoutLibraryNothingLoads() = runTest {
        val state = StatusesState(null, this)
        state.refresh(); advanceUntilIdle()
        assertNull(state.access)
        assertFalse(state.available)
    }

    @Test fun filtersShowOnlyWhenBothKindsExistAndFilterTheGrid() = runTest {
        val photos = loaded(FakeStatusLibrary(ready(listOf(item(1, false), item(2, false)))))
        assertFalse(photos.showFilters)
        photos.filter = StatusFilter.VIDEOS
        assertEquals("a filter that is not shown never hides items", 2, photos.visible.size)

        val state = loaded(FakeStatusLibrary(ready()))
        assertTrue(state.showFilters)
        state.filter = StatusFilter.VIDEOS
        assertTrue(state.visible.all { it.isVideo })
        state.filter = StatusFilter.PHOTOS
        assertTrue(state.visible.none { it.isVideo })
        state.filter = StatusFilter.ALL
        state.hideSaved = true
        assertEquals(6, state.visible.size)
        assertEquals(3, state.newCount)
    }

    @Test fun oneAccountHasNoChipsAndSeveralDefaultToTheFirst() = runTest {
        assertFalse(loaded(FakeStatusLibrary(ready())).showAccounts)
        val state = loaded(FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 10))))
        assertTrue(state.showAccounts)
        assertEquals(MAIN, state.selectedAccount)
        assertTrue(state.visible.all { it.source == MAIN })
        state.account = SECOND
        assertEquals(9, state.visible.size)
        assertTrue(state.visible.all { it.source == SECOND })
    }

    @Test fun savingReportsSavedAndGoneThenReloadsMarks() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        library.results = { if (it == items[1]) StatusSaveResult.Gone else StatusSaveResult.Saved(android.net.Uri.parse("content://media/1")) }
        val state = loaded(library)
        state.toggle(items[0]); state.toggle(items[1])
        assertTrue(state.selecting)
        state.save(state.selectedItems)
        advanceUntilIdle()
        val events = state.events.take(2).toList()
        assertTrue(events[0] is StatusEvent.Saved)
        assertEquals(StatusEvent.Gone(1), events[1])
        assertEquals(listOf(items[0], items[1]), library.saved.single())
        assertFalse(state.selecting)
        assertFalse(state.saving)
        assertTrue("the reload shows the saved mark", (state.access as StatusAccess.Ready).items.first { it.uri == items[0].uri }.saved)
    }

    @Test fun noSpaceWinsOverFailed() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items)).apply { results = { if (it == items[0]) StatusSaveResult.Failed else StatusSaveResult.NoSpace } }
        val state = loaded(library)
        state.save(items.take(2)); advanceUntilIdle()
        assertEquals(StatusEvent.NoSpace, state.events.first())
    }

    @Test fun wrongFolderAsksToReopenAndGrantLoadsTheGrid() = runTest {
        val library = FakeStatusLibrary(grant = StatusGrantResult.WrongFolder)
        val state = loaded(library)
        assertTrue(state.access is StatusAccess.NeedsGrant)
        state.picked(android.net.Uri.parse("content://tree/wrong")); advanceUntilIdle()
        assertTrue(state.wrongFolder)
        assertEquals(StatusEvent.ReopenPicker, state.events.first())
        library.grant = StatusGrantResult.Granted
        library.afterGrant = ready()
        state.picked(android.net.Uri.parse("content://tree/right")); advanceUntilIdle()
        assertFalse(state.wrongFolder)
        assertTrue(state.access is StatusAccess.Ready)
    }

    @Test fun selectionsOfVanishedStatusesAreDroppedOnReload() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        state.toggle(items[0]); state.toggle(items[4])
        state.preview = items[4]
        library.access = ready(items.drop(1).filter { it != items[4] })
        state.refresh(); advanceUntilIdle()
        assertTrue(state.selection.isEmpty())
        assertNull(state.preview)
    }
}
