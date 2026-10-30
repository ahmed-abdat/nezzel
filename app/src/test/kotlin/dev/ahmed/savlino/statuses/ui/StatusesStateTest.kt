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
import dev.ahmed.savlino.statuses.counted
import dev.ahmed.savlino.usage.UsageKeys
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

    @Test fun oneAccountSkipsTheCardsAndSeveralOpenOnCards() = runTest {
        assertFalse(loaded(FakeStatusLibrary(ready())).showAccountList)
        val state = loaded(FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 10).take(5))))
        assertTrue(state.showAccountList)
        assertNull(state.selectedAccount)
        assertEquals(listOf(MAIN, SECOND), state.accounts.map { it.source })
        assertEquals(listOf(9, 5), state.accounts.map { it.total })
        assertEquals(listOf(3, 3), state.accounts.map { it.newCount })
        assertEquals(PREVIEW_COUNT, state.accounts[0].preview.size)
        assertTrue(state.accounts[1].preview.all { it.source == SECOND })
        state.open(SECOND)
        assertFalse(state.showAccountList)
        assertEquals(5, state.visible.size)
        assertTrue(state.visible.all { it.source == SECOND })
    }

    @Test fun backToCardsDropsTheSelectionAndAVanishedAccountFallsBack() = runTest {
        val library = FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 10)))
        val state = loaded(library)
        state.open(SECOND)
        state.filter = StatusFilter.VIDEOS
        state.toggle(state.visible.first())
        state.closeAccount()
        assertTrue(state.showAccountList)
        assertTrue(state.selection.isEmpty())
        state.open(MAIN)
        assertEquals(StatusFilter.ALL, state.filter)
        // The opened account's statuses all expired: the cards come back.
        library.access = ready(mixed(SECOND, offset = 10) + mixed(FakeStatusLibrary.THIRD, offset = 20))
        state.refresh(); advanceUntilIdle()
        assertTrue(state.showAccountList)
    }

    @Test fun pullDownRescansAndShowsStatusesWhatsAppAddedMeanwhile() = runTest {
        val library = FakeStatusLibrary(ready(mixed(MAIN)))
        val state = loaded(library)
        val before = state.visible.size
        library.access = ready(mixed(MAIN) + mixed(MAIN, offset = 10))
        state.pull()
        assertTrue(state.pulling)
        advanceUntilIdle()
        assertFalse(state.pulling)
        assertEquals(2, library.loads)
        assertTrue(state.visible.size > before)
    }

    @Test fun lostAccessClosesTheAccountSoARegrantOpensOnTheCards() = runTest {
        val library = FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 10)))
        val state = loaded(library)
        state.open(SECOND)
        library.access = StatusAccess.NeedsGrant(listOf(FakeStatusLibrary.APP))
        state.refresh(); advanceUntilIdle()
        assertNull(state.account)
        library.access = ready(mixed(MAIN) + mixed(SECOND, offset = 10))
        state.refresh(); advanceUntilIdle()
        assertTrue(state.showAccountList)
    }

    @Test fun aRestoredAccountStaysOpenWhileItsFolderExists() = runTest {
        val library = FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 10)))
        val state = StatusesState(library, this, SECOND).also { it.refresh(); advanceUntilIdle() }
        assertEquals(SECOND, state.selectedAccount)
        assertTrue(state.visible.all { it.source == SECOND })
        val gone = StatusesState(FakeStatusLibrary(ready(mixed(MAIN) + mixed(FakeStatusLibrary.THIRD, offset = 20))), this, SECOND)
            .also { it.refresh(); advanceUntilIdle() }
        assertNull(gone.account)
        assertTrue(gone.showAccountList)
    }

    @Test fun aPartlyFailedBatchKeepsTheFailedPickedAndSaysBothCounts() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        library.results = { if (it == items[1]) StatusSaveResult.Gone else StatusSaveResult.Saved(android.net.Uri.parse("content://media/1")) }
        val state = loaded(library)
        state.toggle(items[0]); state.toggle(items[1]); state.toggle(items[2])
        assertTrue(state.selecting)
        state.preview = items[0]
        state.save(state.selectedItems)
        advanceUntilIdle()
        assertEquals(StatusEvent.Batch(saved = 2, notSaved = 1), state.events.first())
        assertEquals(listOf(items[0], items[1], items[2]), library.saved)
        assertEquals("the one that didn't save stays picked", setOf(items[1].uri), state.selection)
        assertTrue(state.selecting)
        assertFalse(state.saving)
        assertEquals("the preview stays open", items[0].uri, state.preview?.uri)
        assertTrue("the reload shows the saved mark", (state.access as StatusAccess.Ready).items.first { it.uri == items[0].uri }.saved)
    }

    @Test fun oneSavedFileCanBeOpenedAndAFullBatchClosesSelectMode() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        state.save(listOf(items[0])); advanceUntilIdle()
        assertTrue(state.events.first() is StatusEvent.Saved)
        state.toggle(items[1]); state.toggle(items[4])
        state.save(state.selectedItems); advanceUntilIdle()
        assertEquals(StatusEvent.Batch(saved = 2, notSaved = 0), state.events.first())
        assertFalse(state.selecting)
    }

    @Test fun savedAndQueuedStatusesAreNeverCopiedTwice() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items)).apply { hold = kotlinx.coroutines.CompletableDeferred() }
        val state = loaded(library)
        // items[3] is already saved; items[0] is tapped three times while the first copy waits.
        state.save(listOf(items[0], items[3]))
        state.save(listOf(items[0]))
        state.save(listOf(items[0], items[0], items[4]))
        advanceUntilIdle()
        assertEquals(setOf(items[0].uri, items[4].uri), state.pending)
        assertEquals(0 to 2, state.progress)
        library.hold!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(items[0], items[4]), library.saved)
        state.save(listOf(items[0])); advanceUntilIdle()
        assertEquals("a saved status is skipped", 2, library.saved.size)
    }

    @Test fun copiesRunOneAtATime() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        var running = 0; var most = 0
        library.gate = { running++; most = maxOf(most, running); kotlinx.coroutines.yield(); running-- }
        val state = loaded(library)
        state.save(listOf(items[0], items[1])); state.save(listOf(items[2])); state.save(listOf(items[4]))
        advanceUntilIdle()
        assertEquals(1, most)
        assertEquals(listOf(items[0], items[1], items[2], items[4]), library.saved)
    }

    @Test fun aStatusWithoutALastingMarkStillShowsSavedThisSession() = runTest {
        val items = mixed().mapIndexed { i, it -> if (i == 0) it.copy(sizeBytes = null) else it }
        val library = FakeStatusLibrary(ready(items)).apply { marks = false }
        val state = loaded(library)
        state.preview = items[0]
        state.save(listOf(items[0])); advanceUntilIdle()
        state.refresh(); advanceUntilIdle()
        assertTrue(state.visible.first { it.uri == items[0].uri }.saved)
        assertTrue(state.previewItems.first { it.uri == items[0].uri }.saved)
        state.save(listOf(items[0])); advanceUntilIdle()
        assertEquals(1, library.saved.size)
    }

    @Test fun aCopyDeletedAfterSavingShowsUnsavedOnTheNextRescan() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        state.openPreview(items[0])
        state.save(listOf(items[0])); advanceUntilIdle()
        assertTrue(state.visible.first { it.uri == items[0].uri }.saved)
        // The saved copy was deleted from Downloads: the library's next load no longer marks it.
        library.access = ready(items)
        state.refresh(); advanceUntilIdle()
        assertFalse(state.visible.first { it.uri == items[0].uri }.saved)
        assertFalse(state.previewItems.first { it.uri == items[0].uri }.saved)
        state.save(listOf(items[0])); advanceUntilIdle()
        assertEquals("it can be saved again", 2, library.saved.size)
    }

    @Test fun aRescanThatStartedBeforeASaveFinishedKeepsItSaved() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        val second = kotlinx.coroutines.CompletableDeferred<Unit>()
        library.gate = { if (it.uri == items[1].uri) second.await() }
        val load = kotlinx.coroutines.CompletableDeferred<Unit>().also { library.loadGate = it }
        state.refresh(); advanceUntilIdle()
        state.save(listOf(items[0], items[1])); advanceUntilIdle()
        load.complete(Unit); advanceUntilIdle()
        assertTrue("the older scan can't know about the save", state.visible.first { it.uri == items[0].uri }.saved)
        second.complete(Unit); advanceUntilIdle()
        assertTrue(state.visible.filter { it.uri in setOf(items[0].uri, items[1].uri) }.all { it.saved })
    }

    @Test fun lostAccessKeepsTheMarksOfThisVisit() = runTest {
        val items = mixed().mapIndexed { i, it -> if (i == 0) it.copy(lastModifiedMillis = null) else it }
        val library = FakeStatusLibrary(ready(items)).apply { marks = false }
        val state = loaded(library)
        state.save(listOf(items[0])); advanceUntilIdle()
        library.access = StatusAccess.NeedsGrant(listOf(FakeStatusLibrary.APP))
        state.refresh(); advanceUntilIdle()
        library.access = ready(items)
        state.refresh(); advanceUntilIdle()
        assertTrue(state.visible.first { it.uri == items[0].uri }.saved)
    }

    @Test fun selectAllTakesOnlyWhatIsShownAndAFilterChangeDropsTheRest() = runTest {
        val items = mixed(MAIN) + mixed(SECOND, offset = 9)
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        state.open(MAIN)
        state.enterSelect()
        assertTrue(state.selecting)
        assertTrue(state.selection.isEmpty())
        state.selectAll()
        // This account, not saved: 6 of its 9.
        assertEquals(items.take(9).filter { !it.saved }.map { it.uri }.toSet(), state.selection)
        assertTrue(state.allSelected)
        state.filter = StatusFilter.PHOTOS
        assertEquals(listOf(items[1], items[6]).map { it.uri }.toSet(), state.selection)
        state.save(state.selectedItems); advanceUntilIdle()
        assertEquals(listOf(items[1], items[6]), library.saved)
        state.clearSelection()
        assertTrue(state.selection.isEmpty())
    }

    @Test fun savedOrSavingStatusesCanNotBePicked() = runTest {
        val items = mixed()
        val state = loaded(FakeStatusLibrary(ready(items)))
        state.toggle(items[3])
        assertTrue(state.selection.isEmpty())
    }

    @Test fun leavingTheTabKeepsConfirmedCopiesBusyUntilTheirResult() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val stop = kotlinx.coroutines.CompletableDeferred<Unit>()
        library.gate = { if (it == items[1]) stop.await() }
        val counts = mutableListOf<String>()
        val state = StatusesState(library.counted(counts::add), this).also { it.refresh(); advanceUntilIdle() }
        state.setActive(true)
        counts.clear()
        state.toggle(items[0]); state.toggle(items[1]); state.toggle(items[2])
        state.save(state.selectedItems); advanceUntilIdle()
        assertEquals("the first file counts the moment it is saved", listOf(UsageKeys.STATUSES_SAVED), counts)
        state.setActive(false); advanceUntilIdle()
        assertTrue(state.saving)
        assertTrue(dev.ahmed.savlino.statuses.StatusSaver.busy)
        assertFalse("leaving closes select mode", state.selecting)
        assertTrue(state.selection.isEmpty())
        assertEquals(listOf(items[0]), library.saved)
        stop.complete(Unit); advanceUntilIdle()
        assertEquals(items.take(3), library.saved)
        assertFalse(state.saving)
        assertFalse(dev.ahmed.savlino.statuses.StatusSaver.busy)
        assertEquals(List(3) { UsageKeys.STATUSES_SAVED }, counts)
        assertEquals(StatusEvent.Batch(3, 0), state.events.first())
    }

    @Test fun readyCountsOncePerVisitWhenSomethingIsLeftToSave() = runTest {
        val items = mixed()
        val library = FakeStatusLibrary(ready(items))
        val state = loaded(library)
        assertEquals("not shown yet", 0, library.readyShown)
        state.setActive(true)
        assertEquals(1, library.readyShown)
        state.refresh(); advanceUntilIdle()
        state.setActive(true)
        assertEquals("a rescan or a second shown signal is the same visit", 1, library.readyShown)
        state.setActive(false); state.setActive(true); advanceUntilIdle()
        assertEquals(2, library.readyShown)
        library.access = ready(items.map { it.copy(saved = true) })
        state.refresh(); advanceUntilIdle()
        state.setActive(false); state.setActive(true)
        assertEquals("nothing left to save", 2, library.readyShown)
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

    @Test fun aRestoredHostReopensTheAccountFilterAndHideSaved() = runTest {
        val handle = androidx.lifecycle.SavedStateHandle(mapOf("statuses" to androidx.core.os.bundleOf(
            "account" to "2/${SECOND.packageName}", "filter" to "VIDEOS", "hideSaved" to true)))
        val state = StatusesHost(handle).state(null)
        assertEquals(SECOND, state.account)
        assertEquals(StatusFilter.VIDEOS, state.filter)
        assertTrue(state.hideSaved)
    }

    @Test fun partialBatchSaysStorageIsFull() = runTest {
        val items = mixed().take(3)
        val library = FakeStatusLibrary(ready(items)).apply {
            results = { if (it == items[2]) StatusSaveResult.NoSpace else StatusSaveResult.Saved(android.net.Uri.parse("content://media/1")) }
        }
        val state = loaded(library)
        state.save(items); advanceUntilIdle()
        assertEquals(StatusEvent.Batch(saved = 2, notSaved = 1, noSpace = true), state.events.first())
    }
}
