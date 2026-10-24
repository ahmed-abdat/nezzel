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
import dev.ahmed.savlino.statuses.StatusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class StatusFilter { ALL, PHOTOS, VIDEOS }

/** One-shot messages for the screen's snackbar or picker; never carries names or URIs into logs. */
sealed interface StatusEvent {
    data class Saved(val uri: Uri, val isVideo: Boolean) : StatusEvent
    data class Gone(val count: Int) : StatusEvent
    data object NoSpace : StatusEvent
    data object Failed : StatusEvent
    /** The picked folder held no statuses: open the picker again. */
    data object ReopenPicker : StatusEvent
}

/**
 * Screen state over a [StatusLibrary]. A null library (not wired yet) never loads and shows the explainer only.
 * Filter, account and hide-saved apply to what is shown; selection holds URIs so a reload keeps it where items survive.
 */
class StatusesState(private val library: StatusLibrary?, private val scope: CoroutineScope) {
    /** Null until the first load ends (or forever without a library). */
    var access by mutableStateOf<StatusAccess?>(null); private set
    var wrongFolder by mutableStateOf(false); private set
    var filter by mutableStateOf(StatusFilter.ALL)
    var account by mutableStateOf<StatusSource?>(null)
    var hideSaved by mutableStateOf(false)
    var selection by mutableStateOf<Set<Uri>>(emptySet()); private set
    var preview by mutableStateOf<StatusItem?>(null)
    var saving by mutableStateOf(false); private set
    private val channel = Channel<StatusEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val available get() = library != null
    private var loading: Job? = null

    private val ready get() = access as? StatusAccess.Ready
    /** With several account folders one is always chosen (the first until the person picks); there is no mixed view. */
    val selectedAccount: StatusSource? get() = ready?.sources?.takeIf { it.size > 1 }?.let { sources -> account?.takeIf { it in sources } ?: sources.first() }
    private val accountItems get() = ready?.items.orEmpty().let { items ->
        val chosen = selectedAccount
        if (chosen == null) items else items.filter { it.source == chosen }
    }
    val showAccounts get() = (ready?.sources?.size ?: 0) > 1
    /** Filters only when both kinds exist; otherwise every chip but All would be empty. */
    val showFilters get() = accountItems.let { items -> items.any { it.isVideo } && items.any { !it.isVideo } }
    val showHideSaved get() = accountItems.any { it.saved }
    val visible: List<StatusItem> get() = accountItems
        .filter { !showFilters || when (filter) { StatusFilter.ALL -> true; StatusFilter.PHOTOS -> !it.isVideo; StatusFilter.VIDEOS -> it.isVideo } }
        .filter { !hideSaved || !showHideSaved || !it.saved }
    val newCount get() = accountItems.count { it.isNew }
    val selecting get() = selection.isNotEmpty()
    val selectedItems get() = ready?.items.orEmpty().filter { it.uri in selection }

    fun refresh() {
        val library = library ?: return
        loading?.cancel()
        loading = scope.launch {
            val loaded = library.load()
            access = loaded
            // Items can vanish at any time (statuses expire after about a day): drop selections that went with them.
            val items = (loaded as? StatusAccess.Ready)?.items.orEmpty()
            val present = items.mapTo(HashSet()) { it.uri }
            selection = selection.filterTo(HashSet()) { it in present }
            preview = preview?.let { shown -> items.firstOrNull { it.uri == shown.uri } }
        }
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

    fun toggle(item: StatusItem) {
        selection = if (item.uri in selection) selection - item.uri else selection + item.uri
    }
    fun clearSelection() { selection = emptySet() }

    fun save(items: List<StatusItem>) {
        val library = library ?: return
        if (saving || items.isEmpty()) return
        saving = true
        scope.launch {
            try {
                val results = library.save(items)
                val saved = items.zip(results).filter { it.second is StatusSaveResult.Saved }
                saved.lastOrNull()?.let { (item, result) -> channel.send(StatusEvent.Saved((result as StatusSaveResult.Saved).uri, item.isVideo)) }
                results.count { it is StatusSaveResult.Gone }.takeIf { it > 0 }?.let { channel.send(StatusEvent.Gone(it)) }
                if (results.any { it is StatusSaveResult.NoSpace }) channel.send(StatusEvent.NoSpace)
                else if (results.any { it is StatusSaveResult.Failed }) channel.send(StatusEvent.Failed)
                clearSelection()
                preview = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channel.send(StatusEvent.Failed) }
            finally { saving = false }
            refresh()
        }
    }
}
