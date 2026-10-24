package dev.ahmed.savlino.ui

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistViewModelTest {
    private val source = LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1)
    private fun item(n: Int, reason: PlaylistUnavailable? = null) = PlaylistItem("v${n.toString().padStart(10, '0')}",
        "https://youtube.com/watch?v=v${n.toString().padStart(10, '0')}", "Video $n", 60, unavailable = reason)

    @Test fun savedAndDisabledRowsStartUntickedAndCannotBeBulkSelected(): Unit = runBlocking {
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) =
                PlaylistResult.Success(PlaylistPage("List", listOf(item(1), item(2), item(3, PlaylistUnavailable.Live),
                    item(4, PlaylistUnavailable.Private), item(5, PlaylistUnavailable.Unavailable)), 5, null))
        }
        val vm = PlaylistViewModel(engine, this, { it.id == item(2).id }, { _, _, _ -> true })
        vm.open(source); yield()
        assertEquals(setOf(item(1).id), vm.selected)
        assertTrue(item(2).id in vm.savedBefore)
        vm.selectNone(); assertTrue(vm.selected.isEmpty())
        vm.selectAll(); assertEquals(setOf(item(1).id, item(2).id), vm.selected)
        vm.toggle(item(3).id); assertFalse(item(3).id in vm.selected)
        vm.close()
    }

    @Test fun capDisablesConfirmationAndOneTapConfirmsInListOrder(): Unit = runBlocking {
        var calls = 0
        var confirmed = emptyList<PlaylistItem>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) =
                PlaylistResult.Success(PlaylistPage("List", (1..201).map { item(it) }, 201, null))
        }
        val vm = PlaylistViewModel(engine, this, { false }, { _, preset, rows ->
            assertEquals(Preset.BALANCED, preset); calls++; confirmed = rows; true
        })
        vm.open(source); yield()
        assertFalse(vm.canConfirm)
        vm.confirm(); yield(); assertEquals(0, calls)
        vm.toggle(item(1).id); assertTrue(vm.canConfirm)
        vm.confirm(); vm.confirm(); yield()
        assertEquals(1, calls); assertEquals((2..201).map { item(it).id }, confirmed.map { it.id })
        vm.close()
    }

    @Test fun pagingKeepsUserChoicesAndDedupesWithoutSelectingSavedRows(): Unit = runBlocking {
        var calls = 0
        // A token is opaque to app code. Reflection only constructs the engine-owned continuation fixture.
        val token = PlaylistPageToken::class.java.declaredConstructors.first { it.parameterCount == 6 }.let {
            it.isAccessible = true
            it.newInstance(source.link.normalizedUrl, 100, null, emptySet<String>(), null, null) as PlaylistPageToken
        }
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?): PlaylistResult {
                calls++
                return if (page == null) PlaylistResult.Success(PlaylistPage("List", (1..100).map { item(it) }, 102, token))
                else {
                    assertSame(token, page)
                    PlaylistResult.Success(PlaylistPage("List", (100..102).map { item(it) }, 102, null))
                }
            }
        }
        val vm = PlaylistViewModel(engine, this, { it.id == item(102).id }, { _, _, _ -> true })
        vm.open(source); yield(); assertTrue(vm.hasMore)
        vm.selectNone(); vm.loadMore(); vm.loadMore(); yield()
        assertEquals(2, calls); assertEquals(102, vm.items.size)
        assertEquals(setOf(item(101).id), vm.selected)
        assertFalse(vm.hasMore); vm.close()
    }

    @Test fun failedSubmissionCanRetryWithoutReloadingTheFinishedList(): Unit = runBlocking {
        var listings = 0
        var submissions = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?): PlaylistResult {
                listings++
                return PlaylistResult.Success(PlaylistPage("List", listOf(item(1)), 1, null))
            }
        }
        val vm = PlaylistViewModel(engine, this, { false }, { _, preset, rows ->
            assertEquals(Preset.SMALL, preset)
            assertEquals(listOf(item(1).id), rows.map { it.id })
            ++submissions > 1
        })
        vm.open(source); yield(); vm.quality(Preset.SMALL)
        vm.confirm(); yield()
        assertTrue(vm.submitError); assertNull(vm.error)
        assertTrue(vm.visible); assertFalse(vm.hasMore); assertTrue(vm.canConfirm)
        vm.confirm(); yield()
        assertEquals(2, submissions); assertEquals(1, listings)
        assertFalse(vm.visible); assertFalse(vm.submitError)
    }

    @Test fun lateConfirmationCannotCloseANewerSheet(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var accepted = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) =
                PlaylistResult.Success(PlaylistPage("List", listOf(item(1)), 1, null))
        }
        val vm = PlaylistViewModel(engine, this, { false }, { _, _, _ ->
            withContext(NonCancellable) { gate.await() }; true
        }, onAccepted = { accepted++ })
        vm.open(source); yield(); vm.confirm(); yield()
        vm.close(); vm.open(source); yield(); gate.complete(Unit); yield()
        assertTrue(vm.visible); assertEquals(0, accepted); assertEquals(1, vm.items.size)
        vm.close()
    }

    @Test fun closingDuringAPageDiscardsItsLateResult(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?): PlaylistResult {
                withContext(NonCancellable) { gate.await() }
                return PlaylistResult.Success(PlaylistPage("Late", listOf(item(1)), 1, null))
            }
        }
        val vm = PlaylistViewModel(engine, this, { false }, { _, _, _ -> true })
        vm.open(source); yield(); vm.close(); gate.complete(Unit); yield()
        assertTrue(vm.items.isEmpty()); assertFalse(vm.visible)
    }
}
