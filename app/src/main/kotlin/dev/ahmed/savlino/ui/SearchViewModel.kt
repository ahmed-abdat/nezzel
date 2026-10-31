package dev.ahmed.savlino.ui

import androidx.compose.runtime.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import java.util.UUID

/**
 * YouTube search from Home's field, retained by LinkViewModel. Memory only: the words, the selected filter and the
 * next-page token never enter SavedStateHandle, logs or usage counts. A result opens the ordinary link flow;
 * nothing downloads from here.
 */
class SearchViewModel internal constructor(private val engine: EngineAdapter, private val scope: CoroutineScope) {
    var visible by mutableStateOf(false); private set
    /** The search field's text. */
    var query by mutableStateOf(""); private set
    var filter by mutableStateOf(SearchFilter.VIDEOS); private set
    var results by mutableStateOf<List<SearchResult>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<DownloadError?>(null); private set
    var hasMore by mutableStateOf(false); private set
    /** True once a search answered with no rows at all. */
    val empty get() = !loading && error == null && searched != null && results.isEmpty()
    private var searched: String? = null
    private var nextPage: SearchPageToken? = null
    private var generation = 0
    private var request: Job? = null
    private var operation: String? = null

    fun open(words: String) {
        visible = true
        query = words.take(YouTubeSearch.MAX_QUERY)
        filter = SearchFilter.VIDEOS
        searched = null
        search()
    }
    fun edit(value: String) { query = value.take(YouTubeSearch.MAX_QUERY) }
    /** A fresh search for the field's words; the same words while their list is shown keep it. */
    fun search() {
        val words = YouTubeSearch.query(query)
        if (words == null) {
            stop()
            searched = null; nextPage = null; results = emptyList(); hasMore = false
            return
        }
        if (words == searched && error == null && (loading || results.isNotEmpty())) return
        stop()
        searched = words; nextPage = null; results = emptyList(); hasMore = false
        load()
    }
    /** Keeps the words, cancels the running request and reads page 1 of [next]. */
    fun choose(next: SearchFilter) {
        if (next == filter) return
        filter = next
        stop()
        nextPage = null; results = emptyList(); hasMore = false
        if (searched != null) load()
    }
    fun loadMore() { if (hasMore) load() }
    /** Retry the page that failed (the first one, or the next one under the list). */
    fun retry() { if (error != null) load() }
    fun close() {
        stop()
        visible = false; query = ""; filter = SearchFilter.VIDEOS; searched = null
        results = emptyList(); hasMore = false; nextPage = null
    }
    private fun stop() {
        generation++; request?.cancel(); request = null
        operation?.let(engine::cancel); operation = null
        loading = false; error = null
    }
    private fun load() {
        val words = searched ?: return
        if (loading) return
        if (results.size >= RESULT_CAP) { hasMore = false; nextPage = null; return }
        val epoch = generation
        val selected = filter
        val requested = nextPage
        val id = UUID.randomUUID().toString()
        operation = id; loading = true; error = null
        request = scope.launch {
            var token = requested
            var activeId = id
            var outcome = fetch(words, activeId, selected, token)
            if (epoch != generation) return@launch
            if (shouldFallback(selected, outcome, token)) {
                token = SearchPageToken.fallback(1)
                nextPage = token
                activeId = UUID.randomUUID().toString()
                operation = activeId
                outcome = fetch(words, activeId, selected, token)
                if (epoch != generation) return@launch
            }
            when (outcome) {
                is SearchOutcome.Failure -> error = outcome.error
                is SearchOutcome.Success -> apply(outcome.page)
            }
            if (epoch != generation) return@launch
            loading = false
            if (operation == activeId) operation = null
        }
    }
    private suspend fun fetch(words: String, id: String, selected: SearchFilter, token: SearchPageToken?): SearchOutcome =
        try { engine.searchYouTube(words, id, selected, token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SearchOutcome.Failure(DownloadError.Failed) }
    /** Videos only, and only when the first NewPipe page failed or none of its rows survived the id check. */
    private fun shouldFallback(selected: SearchFilter, outcome: SearchOutcome, token: SearchPageToken?): Boolean {
        if (selected != SearchFilter.VIDEOS || token != null || results.isNotEmpty()) return false
        return when (outcome) {
            is SearchOutcome.Failure -> outcome.error != DownloadError.Cancelled
            is SearchOutcome.Success -> outcome.page.unusable
        }
    }
    private fun apply(page: SearchPage) {
        val known = results.mapTo(HashSet()) { it.id }
        val room = RESULT_CAP - results.size
        val added = if (room <= 0) emptyList() else page.results.filter { known.add(it.id) }.take(room)
        results = results + added
        val more = page.next != null && added.isNotEmpty() && results.size < RESULT_CAP
        nextPage = if (more) page.next else null
        hasMore = more
    }

    companion object {
        /** Endless scroll stops here so one search cannot grow without a bound. */
        internal const val RESULT_CAP = 300
        /** Home's text as search words, or null when it is (or looks like) a link and keeps the link flow. */
        fun wordsOf(text: String): String? {
            if (LinkInput.parse(text) != null || "://" in text || text.contains("www.", ignoreCase = true)) return null
            val words = YouTubeSearch.query(text) ?: return null
            // One token with a dot ("site.com/x") is a mistyped link, not words.
            return words.takeUnless { ' ' !in it && LOOKS_LIKE_HOST.containsMatchIn(it) }
        }
        private val LOOKS_LIKE_HOST = Regex("[A-Za-z0-9-]\\.[A-Za-z]{2,}")
    }
}
