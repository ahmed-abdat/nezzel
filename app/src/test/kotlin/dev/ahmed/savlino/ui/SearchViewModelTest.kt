package dev.ahmed.savlino.ui

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SearchViewModelTest {
    private fun result(n: Int) = SearchResult("abcdefghi%02d".format(n), "Video $n", "Channel", 60)

    @Test fun pagesAppendThenAFailedPageRetriesTheSamePageAndANewSearchStartsOver(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        var failNext = false
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, start: Int): SearchOutcome {
                calls += query to start
                if (failNext) { failNext = false; return SearchOutcome.Failure(DownloadError.Network) }
                return if (query == "nothing") SearchOutcome.Success(SearchPage(emptyList(), false))
                else SearchOutcome.Success(SearchPage((start until start + 20).map(::result), start == 1))
            }
        }
        val vm = SearchViewModel(engine, this)
        vm.open("  cats   videos ")
        assertTrue(vm.visible); assertTrue(vm.loading)
        yield()
        assertEquals(20, vm.results.size); assertTrue(vm.hasMore); assertFalse(vm.empty)
        failNext = true
        vm.loadMore(); yield()
        assertEquals(DownloadError.Network, vm.error); assertEquals(20, vm.results.size)
        vm.retry(); yield()
        assertNull(vm.error); assertEquals(40, vm.results.size); assertFalse(vm.hasMore)
        assertEquals(listOf("cats videos" to 1, "cats videos" to 21, "cats videos" to 21), calls)
        vm.search(); yield() // Same words with their list shown: no new request.
        assertEquals(3, calls.size)
        vm.edit("nothing"); vm.search(); yield()
        assertTrue(vm.results.isEmpty()); assertTrue(vm.empty)
        assertEquals("nothing" to 1, calls.last())
        vm.close()
        assertFalse(vm.visible); assertEquals("", vm.query); assertTrue(vm.results.isEmpty())
    }

    @Test fun blankSearchClearsResultsAndIgnoresTheOldRequestsLateAnswer(): Unit = runBlocking {
        lateinit var answer: kotlin.coroutines.Continuation<SearchOutcome>
        val cancelled = mutableListOf<String>()
        var operation: String? = null
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun searchYouTube(query: String, operationId: String, start: Int): SearchOutcome {
                if (start == 1) return SearchOutcome.Success(SearchPage(listOf(result(1)), true))
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
            answer.resume(SearchOutcome.Success(SearchPage(listOf(result(2)), true)))
        }
        yield()
        assertTrue(vm.results.isEmpty()); assertFalse(vm.loading)
        assertFalse(vm.hasMore); assertFalse(vm.empty); assertNull(vm.error)
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
