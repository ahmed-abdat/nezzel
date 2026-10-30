package dev.ahmed.savlino.statuses.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusGrantResult
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.statuses.StatusLibrary
import dev.ahmed.savlino.statuses.StatusSaveResult
import dev.ahmed.savlino.statuses.StatusSaver
import dev.ahmed.savlino.statuses.StatusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StatusFilter { ALL, PHOTOS, VIDEOS }

/** One account card: [preview] holds at most [PREVIEW_COUNT] items, newest first. */
data class AccountSummary(val source: StatusSource, val total: Int, val newCount: Int, val preview: List<StatusItem>)

const val PREVIEW_COUNT = 4

/** One-shot messages for the screen's snackbar or picker; never carries names or URIs into logs. */
sealed interface StatusEvent {
    /** Exactly one file saved and nothing failed: the snackbar can open it. */
    data class Saved(val uri: Uri, val isVideo: Boolean) : StatusEvent
    /** Several saved, or some saved and some not; [notSaved] is 0 when all made it. */
    /** [noSpace]: at least one copy stopped because storage is full, so the message says why. */
    data class Batch(val saved: Int, val notSaved: Int, val noSpace: Boolean = false) : StatusEvent
    data class Gone(val count: Int) : StatusEvent
    data object NoSpace : StatusEvent
    data object Failed : StatusEvent
    /** The picked folder held no statuses: open the picker again. */
    data object ReopenPicker : StatusEvent
}

/**
 * Screen state over a [StatusLibrary]. A null library (not wired yet) never loads and shows the explainer only.
 * Filter, account and hide-saved apply to what is shown; selection holds URIs and never reaches past what is shown.
 * Copies run one at a time in [scope]; leaving the tab cancels them (committed files stay), a rotation does not.
 */
class StatusesState(private val library: StatusLibrary?, private val scope: CoroutineScope, opened: StatusSource? = null) {
    /** Null until the first load ends (or forever without a library). */
    var access by mutableStateOf<StatusAccess?>(null); private set
    var wrongFolder by mutableStateOf(false); private set
    private var filterState by mutableStateOf(StatusFilter.ALL)
    var filter: StatusFilter get() = filterState
        set(value) { filterState = value; keepSelectionShown() }
    var account by mutableStateOf(opened); private set
    private var hideSavedState by mutableStateOf(false)
    var hideSaved: Boolean get() = hideSavedState
        set(value) { hideSavedState = value; keepSelectionShown() }
    var selection by mutableStateOf<Set<Uri>>(emptySet()); private set
    /** Select mode is visible on its own: it stays on with nothing selected, so Select all is one tap away. */
    var selecting by mutableStateOf(false); private set
    var preview by mutableStateOf<StatusItem?>(null)
    // The viewer's pages, fixed when it opens: saving with Hide saved on must not pull the open page away.
    private var previewOrder by mutableStateOf<List<Uri>>(emptyList())
    /** Queued or copying now; a tile in here shows Saving. */
    var pending by mutableStateOf<Set<Uri>>(emptySet()); private set
    /**
     * Saved since this state was made, with the save count at that moment: covers files whose unknown size/time can't
     * carry a lasting mark. A rescan that starts after the save answers for a marked file, so a deleted copy shows unsaved.
     */
    private var savedNow by mutableStateOf<Map<Uri, Int>>(emptyMap())
    private var savesDone = 0
    /** Files finished and in total for the copies running now; both reset when the queue empties. */
    var progress by mutableStateOf(0 to 0); private set
    val saving get() = pending.isNotEmpty()
    private val channel = Channel<StatusEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val available get() = library != null
    private var loading: Job? = null
    private val copies = Mutex()
    private val saves = mutableSetOf<Job>()
    private var run = Run()

    private val ready get() = access as? StatusAccess.Ready
    /**
     * With several account folders the tab opens on one card per account and the grid shows only the opened one;
     * there is no mixed view. A single account skips the cards. An opened account that vanished falls back to the cards.
     */
    val selectedAccount: StatusSource? get() = ready?.sources?.takeIf { it.size > 1 }?.let { sources -> account?.takeIf { it in sources } }
    val showAccountList get() = (ready?.sources?.size ?: 0) > 1 && selectedAccount == null
    /** One card per non-empty account folder, in folder order; [preview] is the newest few, as the grid orders them. */
    val accounts: List<AccountSummary> get() = ready?.let { ready ->
        ready.sources.map { source ->
            val items = ready.items.filter { it.source == source }
            AccountSummary(source, items.size, items.count { it.isNew }, items.take(PREVIEW_COUNT))
        }
    }.orEmpty()
    private val accountItems get() = ready?.items.orEmpty().map(::marked).let { items ->
        val chosen = selectedAccount
        if (chosen == null) items else items.filter { it.source == chosen }
    }
    /** Filters only when both kinds exist; otherwise every chip but All would be empty. */
    val showFilters get() = accountItems.let { items -> items.any { it.isVideo } && items.any { !it.isVideo } }
    val showHideSaved get() = accountItems.any { it.saved }
    val visible: List<StatusItem> get() = accountItems
        .filter { !showFilters || when (filter) { StatusFilter.ALL -> true; StatusFilter.PHOTOS -> !it.isVideo; StatusFilter.VIDEOS -> it.isVideo } }
        .filter { !hideSaved || !showHideSaved || !it.saved }
    /** What the viewer swipes through, with live saved marks; statuses that expired drop out. */
    val previewItems: List<StatusItem> get() {
        val byUri = ready?.items.orEmpty().associateBy { it.uri }
        return previewOrder.ifEmpty { visible.map { it.uri } }.mapNotNull { byUri[it]?.let(::marked) }
    }
    fun openPreview(item: StatusItem) { previewOrder = visible.map { it.uri }; preview = item }
    fun closePreview() { preview = null; previewOrder = emptyList() }
    val newCount get() = accountItems.count { it.isNew }
    /** What Select all picks and Save N copies: shown, not saved, not already on its way. */
    private val selectable get() = visible.filter { !it.saved && it.uri !in pending }
    val canSelect get() = !showAccountList && selectable.isNotEmpty()
    val allSelected get() = selectable.let { items -> items.isNotEmpty() && items.all { it.uri in selection } }
    val selectedItems get() = selectable.filter { it.uri in selection }

    private fun marked(item: StatusItem) = if (item.uri in savedNow && !item.saved) item.copy(saved = true, isNew = false) else item

    private var loadedAt = Long.MIN_VALUE

    fun stale(now: Long = android.os.SystemClock.elapsedRealtime()) = loadedAt == Long.MIN_VALUE || now - loadedAt > STALE_MS

    /** True only while a pull-down rescan runs; the automatic rescans stay quiet. */
    var pulling by mutableStateOf(false); private set

    /** Pull down on the tab: WhatsApp may add statuses while Nezzel stays open, and nothing tells the app. */
    fun pull() {
        if (library == null) return
        pulling = true
        refresh()
    }

    fun refresh() {
        val library = library ?: return
        loading?.cancel()
        loading = scope.launch {
            val savesBefore = savesDone
            val loaded = library.load()
            loadedAt = android.os.SystemClock.elapsedRealtime()
            access = loaded
            pulling = false
            // Lost access or a vanished folder closes the account, so a later grant never reopens it untapped
            if (loaded !is StatusAccess.Ready || account !in loaded.sources) account = null
            // Items can vanish at any time (statuses expire after about a day): drop selections that went with them.
            keepSelectionShown()
            val items = (loaded as? StatusAccess.Ready)?.items.orEmpty()
            if (loaded is StatusAccess.Ready) {
                val byUri = items.associateBy { it.uri }
                savedNow = savedNow.filter { (uri, at) ->
                    val item = byUri[uri] ?: return@filter false
                    at > savesBefore || item.saved || item.sizeBytes == null || item.lastModifiedMillis == null
                }
            }
            preview = preview?.let { shown -> items.firstOrNull { it.uri == shown.uri } }
            countReady()
        }
    }

    // A visit is one stretch of the tab being shown. It lives here, not in the composition, so a rotation is the same visit.
    private var active = false
    private var visit = 0
    private var readyVisit = 0

    /** The tab became shown or hidden. Hiding it ends the visit: unfinished copies stop and select mode closes. */
    fun setActive(shown: Boolean) {
        if (shown == active) return
        active = shown
        if (shown) { visit++; countReady() } else { cancelSaves(); exitSelect() }
    }

    /** Once per visit, when the tab shows at least one status that isn't saved. */
    private fun countReady() {
        val library = library ?: return
        if (!active || readyVisit == visit || ready?.items.orEmpty().none { !marked(it).saved }) return
        readyVisit = visit
        library.readyShown()
    }

    fun picked(tree: Uri?) {
        val library = library ?: return
        scope.launch {
            when (library.onPicked(tree)) {
                StatusGrantResult.Granted -> { wrongFolder = false; refresh() }
                StatusGrantResult.WrongFolder -> { wrongFolder = true; channel.send(StatusEvent.ReopenPicker) }
                StatusGrantResult.Cancelled -> Unit
            }
        }
    }

    fun open(source: StatusSource) {
        account = source
        filterState = StatusFilter.ALL
    }
    /** Back from one account to the cards; a selection never crosses accounts. */
    fun closeAccount() {
        account = null
        exitSelect()
    }

    fun enterSelect() { selecting = true }
    fun exitSelect() { selecting = false; selection = emptySet() }
    /** Saved or saving statuses can't be picked: there is nothing left to do with them. */
    fun toggle(item: StatusItem) {
        if (item.uri !in selection && (marked(item).saved || item.uri in pending)) return
        selecting = true
        selection = if (item.uri in selection) selection - item.uri else selection + item.uri
    }
    fun selectAll() { selecting = true; selection = selectable.mapTo(HashSet()) { it.uri } }
    fun clearSelection() { selection = emptySet() }

    private fun keepSelectionShown() {
        if (selection.isEmpty()) return
        val shown = visible.mapTo(HashSet()) { it.uri }
        selection = selection.filterTo(HashSet()) { it in shown }
    }

    /**
     * Queues [items] for copying, one file at a time. Saved ones and ones already queued are skipped, so a repeated tap
     * never copies twice. Each finished file updates its tile and leaves the selection; files that didn't save stay
     * selected. The preview stays open. One message sums up the run when the queue empties.
     */
    fun save(items: List<StatusItem>) {
        val library = library ?: return
        val fresh = items.filter { !marked(it).saved && it.uri !in pending }.distinctBy { it.uri }
        if (fresh.isEmpty()) return
        pending = pending + fresh.map { it.uri }
        progress = progress.first to progress.second + fresh.size
        val reservation = StatusSaver.reserveSave()
        val job = scope.launch {
            copies.withLock {
                for (item in fresh) {
                    val result = try { library.saveOne(item) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { StatusSaveResult.Failed }
                    run.add(item, result)
                    if (result is StatusSaveResult.Saved) { savedNow = savedNow + (item.uri to ++savesDone); selection = selection - item.uri }
                    pending = pending - item.uri
                    progress = progress.first + 1 to progress.second
                }
                if (pending.isEmpty()) finishRun()
            }
        }
        saves += job
        job.invokeOnCompletion { reservation.close(); saves -= job }
    }

    private suspend fun finishRun() {
        val done = run
        run = Run()
        progress = 0 to 0
        // All picked files made it: select mode is done. Files that didn't save stay picked for another try.
        if (selecting && selection.isEmpty()) exitSelect()
        done.event()?.let { channel.send(it) }
        refresh()
    }

    private fun cancelSaves() {
        if (pending.isEmpty()) return
        saves.toList().forEach { it.cancel() }
        pending = emptySet()
        progress = 0 to 0
        run = Run()
        refresh()
    }

    /** What one run of copies did, for its closing message. */
    private class Run {
        private var lastSaved: Pair<Uri, Boolean>? = null
        private var saved = 0
        private var gone = 0
        private var noSpace = 0
        private var failed = 0
        fun add(item: StatusItem, result: StatusSaveResult) {
            when (result) {
                is StatusSaveResult.Saved -> { saved++; lastSaved = result.uri to item.isVideo }
                StatusSaveResult.Gone -> gone++
                StatusSaveResult.NoSpace -> noSpace++
                StatusSaveResult.Failed -> failed++
                StatusSaveResult.Cancelled -> Unit
            }
        }
        fun event(): StatusEvent? {
            val notSaved = gone + noSpace + failed
            return when {
                saved == 1 && notSaved == 0 -> lastSaved?.let { (uri, video) -> StatusEvent.Saved(uri, video) }
                saved > 0 -> StatusEvent.Batch(saved, notSaved, noSpace > 0)
                noSpace > 0 -> StatusEvent.NoSpace
                failed > 0 -> StatusEvent.Failed
                gone > 0 -> StatusEvent.Gone(gone)
                else -> null
            }
        }
    }
}

/** A quick return to the tab within this window shows the last scan instead of rescanning. */
private const val STALE_MS = 60_000L
