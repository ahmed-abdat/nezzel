package dev.ahmed.savlino.ui

import androidx.compose.runtime.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import java.util.UUID

/**
 * YouTube search from Home's field, retained by LinkViewModel. Memory only: the words never enter SavedStateHandle,
 * logs or usage counts. A result opens the ordinary options sheet; nothing downloads from here.
 */
class SearchViewModel internal constructor(private val engine: EngineAdapter, private val scope: CoroutineScope) {
    var visible by mutableStateOf(false); private set
    /** The search field's text. */
    var query by mutableStateOf(""); private set
    var results by mutableStateOf<List<SearchResult>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<DownloadError?>(null); private set
    var hasMore by mutableStateOf(false); private set
    /** True once a search answered with no rows at all. */
    val empty get() = !loading && error == null && searched != null && results.isEmpty()
    private var searched: String? = null
    private var next = 1
    private var generation = 0
    private var request: Job? = null
    private var operation: String? = null

    fun open(words: String) {
        visible = true
        query = words.take(YouTubeSearch.MAX_QUERY)
        search()
    }
    fun edit(value: String) { query = value.take(YouTubeSearch.MAX_QUERY) }
    /** A fresh search for the field's words; the same words while their list is shown keep it. */
    fun search() {
        val words = YouTubeSearch.query(query)
        if (words == null) {
            stop()
            searched = null; next = 1; results = emptyList(); hasMore = false
            return
        }
        if (words == searched && error == null && (loading || results.isNotEmpty())) return
        stop()
        searched = words; next = 1; results = emptyList(); hasMore = false
        load()
    }
    fun loadMore() { if (hasMore) load() }
    /** Retry the page that failed (the first one, or the next one under the list). */
    fun retry() { if (error != null) load() }
    fun close() {
        stop()
        visible = false; query = ""; searched = null; results = emptyList(); hasMore = false; next = 1
    }
    private fun stop() {
        generation++; request?.cancel(); request = null
        operation?.let(engine::cancel); operation = null
        loading = false; error = null
    }
    private fun load() {
        val words = searched ?: return
        if (loading) return
        val epoch = generation
        val start = next
        val id = UUID.randomUUID().toString()
        operation = id; loading = true; error = null
        request = scope.launch {
            val outcome = try { engine.searchYouTube(words, id, start) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SearchOutcome.Failure(DownloadError.Failed) }
            if (epoch != generation) return@launch
            when (outcome) {
                is SearchOutcome.Failure -> error = outcome.error
                is SearchOutcome.Success -> {
                    val known = results.mapTo(HashSet()) { it.id }
                    results = results + outcome.page.results.filter { known.add(it.id) }
                    hasMore = outcome.page.hasMore
                    next = start + YouTubeSearch.PAGE_SIZE
                }
            }
            loading = false; operation = null
        }
    }

    companion object {
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
