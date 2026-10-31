package dev.ahmed.savlino.ui

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SearchViewModelTest {
    private fun result(n: Int) = SearchResult("a" + n.toString().padStart(10, '0'), "Video $n", "Channel", 60)

    @Test fun pagesAppendThenAFailedPageRetriesTheSameTokenAndANewSearchStartsOver(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, SearchPageToken?>>()
        val second = SearchPageToken.continuation()
        var failNext = false
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, filter: SearchFilter, page: SearchPageToken?): SearchOutcome {
                calls += query to page
                if (failNext) { failNext = false; return SearchOutcome.Failure(DownloadError.Network) }
                if (query == "nothing") return SearchOutcome.Success(SearchPage(emptyList(), null))
                val rows = if (page == null) (1..20).map(::result) else listOf(result(1)) + (21..39).map(::result)
                return SearchOutcome.Success(SearchPage(rows, if (page == null) second else null))
            }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("  cats   videos ")
        assertTrue(vm.visible); assertTrue(vm.loading); assertEquals(SearchFilter.VIDEOS, vm.filter)
        yield()
        assertEquals(20, vm.results.size); assertTrue(vm.hasMore); assertFalse(vm.empty)
        failNext = true
        vm.loadMore(); yield()
        assertEquals(DownloadError.Network, vm.error); assertEquals(20, vm.results.size)
        vm.retry(); yield()
        assertNull(vm.error); assertEquals(39, vm.results.size); assertFalse(vm.hasMore)
        assertEquals(listOf("cats videos" to null, "cats videos" to second, "cats videos" to second), calls)
        vm.search(); yield()
        assertEquals(3, calls.size)
        vm.edit("nothing"); vm.search(); yield()
        assertTrue(vm.results.isEmpty()); assertTrue(vm.empty)
        assertEquals("nothing" to null, calls.last())
        vm.close()
        assertFalse(vm.visible); assertEquals("", vm.query); assertTrue(vm.results.isEmpty())
        assertEquals(SearchFilter.VIDEOS, vm.filter)
    }

    @Test fun blankSearchClearsResultsAndIgnoresTheOldRequestsLateAnswer(): Unit = runBlocking {
        lateinit var answer: kotlin.coroutines.Continuation<SearchOutcome>
        val cancelled = mutableListOf<String>()
        var operation: String? = null
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, filter: SearchFilter, page: SearchPageToken?): SearchOutcome {
                if (page == null) return SearchOutcome.Success(SearchPage(listOf(result(1)), SearchPageToken.continuation()))
                operation = operationId
                return suspendCoroutine { answer = it }
            }
            override fun cancel(operationId: String) { cancelled += operationId }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("cats"); yield()
        assertEquals(listOf(result(1).id), vm.results.map { it.id }); assertTrue(vm.hasMore)
        vm.loadMore(); yield()
        try {
            assertTrue(vm.loading)
            vm.edit("   "); vm.search()
            assertTrue(vm.visible); assertTrue(vm.results.isEmpty()); assertFalse(vm.loading)
            assertFalse(vm.hasMore); assertFalse(vm.empty); assertNull(vm.error)
            assertEquals(listOf(operation), cancelled)
        } finally {
            answer.resume(SearchOutcome.Success(SearchPage(listOf(result(2)), SearchPageToken.continuation(3))))
        }
        yield()
        assertTrue(vm.results.isEmpty()); assertFalse(vm.loading)
        assertFalse(vm.hasMore); assertFalse(vm.empty); assertNull(vm.error)
    }

    @Test fun filterChangeCancelsTheRunningRequestAndRestartsAtPageOne(): Unit = runBlocking {
        lateinit var answer: kotlin.coroutines.Continuation<SearchOutcome>
        val cancelled = mutableListOf<String>()
        var operation: String? = null
        val calls = mutableListOf<Pair<String, SearchFilter>>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, filter: SearchFilter, page: SearchPageToken?): SearchOutcome {
                calls += query to filter
                assertNull(page)
                if (filter == SearchFilter.VIDEOS) {
                    operation = operationId
                    return suspendCoroutine { answer = it }
                }
                return SearchOutcome.Success(SearchPage(listOf(result(1)), null))
            }
            override fun cancel(operationId: String) { cancelled += operationId }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("cats videos")
        yield()
        try {
            assertTrue(vm.loading)
            vm.choose(SearchFilter.MUSIC)
            yield()
            assertEquals(listOf(operation), cancelled)
            assertEquals(SearchFilter.MUSIC, vm.filter)
            assertEquals("cats videos" to SearchFilter.MUSIC, calls.last())
            assertEquals(listOf(result(1).id), vm.results.map { it.id })
            assertFalse(vm.loading)
        } finally {
            runCatching { answer.resume(SearchOutcome.Success(SearchPage(listOf(result(2)), SearchPageToken.continuation()))) }
        }
        yield()
        assertEquals(listOf(result(1).id), vm.results.map { it.id })
        assertEquals(SearchFilter.MUSIC, vm.filter)
    }

    @Test fun videosFirstPageFailureOrUnusablePageFallsBackAndPlaylistsDoNot(): Unit = runBlocking {
        val calls = mutableListOf<Pair<SearchFilter, SearchPageToken?>>()
        var unusable = false
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, filter: SearchFilter, page: SearchPageToken?): SearchOutcome {
                calls += filter to page
                assertEquals("cats", query)
                if (filter == SearchFilter.PLAYLISTS) return SearchOutcome.Failure(DownloadError.Network)
                if (page == null) return if (unusable) SearchOutcome.Success(SearchPage(emptyList(), null, unusable = true))
                    else SearchOutcome.Failure(DownloadError.Network)
                assertTrue(page.fallback)
                return SearchOutcome.Success(SearchPage(listOf(result(1)), null))
            }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("cats"); yield()
        assertEquals(2, calls.size)
        assertNull(calls[0].second); assertTrue(calls[1].second!!.fallback)
        assertEquals(listOf(result(1).id), vm.results.map { it.id }); assertNull(vm.error)
        vm.choose(SearchFilter.PLAYLISTS); yield()
        assertEquals(3, calls.size)
        assertEquals(SearchFilter.PLAYLISTS, calls[2].first); assertNull(calls[2].second)
        assertEquals(DownloadError.Network, vm.error); assertTrue(vm.results.isEmpty())
        yield()
        assertEquals(3, calls.size)

        unusable = true
        val again = SearchViewModel(engine, this)
        again.open("cats"); yield()
        assertEquals(5, calls.size)
        assertTrue(calls[4].second!!.fallback)
        assertEquals(listOf(result(1).id), again.results.map { it.id })
    }

    @Test fun resultsStopAtThreeHundred(): Unit = runBlocking {
        val calls = mutableListOf<SearchPageToken?>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, filter: SearchFilter, page: SearchPageToken?): SearchOutcome {
                calls += page
                val base = calls.size * 100
                return SearchOutcome.Success(SearchPage(((base - 99)..base).map(::result), SearchPageToken.continuation(calls.size + 1)))
            }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("cats"); yield()
        assertEquals(100, vm.results.size); assertTrue(vm.hasMore)
        vm.loadMore(); yield()
        assertEquals(200, vm.results.size)
        vm.loadMore(); yield()
        assertEquals(SearchViewModel.RESULT_CAP, vm.results.size); assertFalse(vm.hasMore)
        vm.loadMore(); yield()
        assertEquals(3, calls.size)
    }

    @Test fun onlyWordsBecomeASearch() {
        assertEquals("lofi beats", SearchViewModel.wordsOf(" lofi  beats "))
        assertEquals("أغاني عربية", SearchViewModel.wordsOf("أغاني عربية"))
        assertNull(SearchViewModel.wordsOf("https://www.youtube.com/watch?v=abcdefghij1"))
        assertNull(SearchViewModel.wordsOf("youtube.com/watch?v=abcdefghij1"))
        assertNull(SearchViewModel.wordsOf("example.com"))
        assertNull(SearchViewModel.wordsOf("   "))
        assertEquals("node.js tutorial", SearchViewModel.wordsOf("node.js tutorial"))
    }
}
