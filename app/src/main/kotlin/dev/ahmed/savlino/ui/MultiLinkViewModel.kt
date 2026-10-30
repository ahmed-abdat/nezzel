package dev.ahmed.savlino.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import dev.ahmed.savlino.domain.*
import kotlinx.coroutines.*

/** Retained by LinkViewModel. Links stay in memory only (never SavedStateHandle); no network before the one Download tap. */
class MultiLinkViewModel internal constructor(
    private val scope: CoroutineScope,
    private val submit: suspend (String, Preset, List<ValidatedLink>) -> Boolean,
    private val onAccepted: () -> Unit = {},
    private val preferredPreset: () -> Preset = { Preset.BALANCED },
    private val rememberPreset: (Preset) -> Unit = {},
) : ViewModel() {
    var links by mutableStateOf<List<ValidatedLink>>(emptyList()); private set
    var truncated by mutableStateOf(false); private set
    var preset by mutableStateOf(Preset.BALANCED); private set
    var submitting by mutableStateOf(false); private set
    var submitError by mutableStateOf(false); private set
    val visible get() = links.isNotEmpty()
    val canConfirm get() = visible && !submitting
    private var generation = 0
    private var request: Job? = null

    fun open(found: MultiLinks.Found) {
        close()
        links = found.links; truncated = found.truncated; preset = preferredPreset()
    }
    fun close() {
        generation++; request?.cancel(); request = null
        links = emptyList(); truncated = false; preset = Preset.BALANCED; submitting = false; submitError = false
    }
    fun remove(position: Int) {
        if (submitting || position !in links.indices) return
        links = links.filterIndexed { index, _ -> index != position }
    }
    fun quality(preset: Preset) {
        if (!submitting && preset in listOf(Preset.BEST, Preset.BALANCED, Preset.SMALL, Preset.AUDIO_M4A, Preset.AUDIO_MP3)) this.preset = preset
    }
    /** [title] is the sheet's "N links" in the current language, stored as the batch title. */
    fun confirm(title: String) {
        if (!canConfirm) return
        val chosen = links
        val chosenPreset = preset
        if (!chosenPreset.audio) rememberPreset(chosenPreset)
        val epoch = ++generation
        submitting = true; submitError = false
        request = scope.launch {
            val accepted = try { submit(title, chosenPreset, chosen) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (epoch != generation) return@launch
            submitting = false
            if (accepted) { close(); onAccepted() } else submitError = true
        }
    }
}
