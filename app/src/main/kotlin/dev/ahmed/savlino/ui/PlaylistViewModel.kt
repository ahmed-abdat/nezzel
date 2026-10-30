package dev.ahmed.savlino.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.*
import java.util.UUID

/** Retained by LinkViewModel, bound to its lifetime. Listing/selection never enters SavedStateHandle or the journal. */
class PlaylistViewModel internal constructor(
    private val engine: EngineAdapter,
    private val scope: CoroutineScope,
    private val wasSaved: suspend (PlaylistItem) -> Boolean,
    private val submit: suspend (String, Preset, List<PlaylistItem>) -> Boolean,
    private val onAccepted: () -> Unit = {},
    private val preferredPreset: () -> Preset = { Preset.BALANCED },
    private val rememberPreset: (Preset) -> Unit = {},
) : ViewModel() {
    var visible by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var items by mutableStateOf<List<PlaylistItem>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var savedBefore by mutableStateOf<Set<String>>(emptySet()); private set
    var preset by mutableStateOf(Preset.BALANCED); private set
    var loading by mutableStateOf(false); private set
    var submitting by mutableStateOf(false); private set
    var error by mutableStateOf<DownloadError?>(null); private set
    var submitError by mutableStateOf(false); private set
    var total by mutableStateOf<Long?>(null); private set
    private var next: PlaylistPageToken? = null
    var hasMore by mutableStateOf(false); private set
    private var source: LinkRevision? = null
    private var generation = 0
    private var request: Job? = null
    private var operation: String? = null
    val canConfirm get() = visible && !submitting && selected.size in 1..200

    fun open(source: LinkRevision) {
        close()
        this.source = source; preset = preferredPreset(); visible = true
        loadMore()
    }
    fun close() {
        generation++; request?.cancel(); request = null
        operation?.let(engine::cancel); operation = null
        source = null; next = null; hasMore = false; visible = false; loading = false; submitting = false
        items = emptyList(); selected = emptySet(); savedBefore = emptySet(); title = ""; total = null
        preset = Preset.BALANCED; error = null; submitError = false
    }
    fun loadMore() {
        val source = source ?: return
        if (loading || submitting || (items.isNotEmpty() && !hasMore)) return
        val epoch = generation
        val token = next
        val id = UUID.randomUUID().toString()
        operation = id; loading = true; error = null
        request = scope.launch {
            val result = try { engine.listPlaylist(source, id, token) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { PlaylistResult.Failure(DownloadError.Failed) }
            if (epoch != generation) return@launch
            when (result) {
                is PlaylistResult.Failure -> error = result.error
                is PlaylistResult.Success -> {
                    val page = result.page
                    val known = items.mapTo(HashSet()) { it.id }
                    val added = page.items.filter { known.add(it.id) }
                    val saved = added.filter {
                        try { wasSaved(it) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                    }.mapTo(HashSet()) { it.id }
                    if (epoch != generation) return@launch
                    items = items + added; savedBefore = savedBefore + saved
                    selected = selected + added.filter { it.selectable && it.id !in saved }.map { it.id }
                    if (page.title.isNotBlank()) title = page.title
                    total = page.totalCount; next = page.next; hasMore = next != null
                }
            }
            loading = false; operation = null
        }
    }
    fun toggle(id: String) {
        if (submitting || items.none { it.id == id && it.selectable }) return
        selected = if (id in selected) selected - id else selected + id
    }
    fun selectAll() { if (!submitting) selected = items.filter { it.selectable }.mapTo(LinkedHashSet()) { it.id } }
    fun selectNone() { if (!submitting) selected = emptySet() }
    fun quality(preset: Preset) {
        if (!submitting && preset in listOf(Preset.BEST, Preset.BALANCED, Preset.SMALL, Preset.AUDIO_M4A, Preset.AUDIO_MP3)) this.preset = preset
    }
    fun confirm() {
        if (!canConfirm) return
        val rows = items.filter { it.id in selected }
        val chosenTitle = title
        val chosenPreset = preset
        if (!chosenPreset.audio) rememberPreset(chosenPreset)
        // A pending continuation must not mutate the frozen confirmation or keep fetching after it.
        generation++; request?.cancel(); operation?.let(engine::cancel); operation = null; loading = false
        val epoch = generation
        submitting = true; error = null; submitError = false
        // Freeze the selection before handing the single explicit confirmation to the durable coordinator.
        request = scope.launch {
            val accepted = try { submit(chosenTitle, chosenPreset, rows) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (epoch != generation) return@launch
            submitting = false
            if (accepted) { close(); onAccepted() } else submitError = true
        }
    }
}
