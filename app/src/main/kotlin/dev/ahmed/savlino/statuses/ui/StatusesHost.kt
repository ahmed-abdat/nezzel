package dev.ahmed.savlino.statuses.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ahmed.savlino.statuses.StatusLibrary
import dev.ahmed.savlino.statuses.StatusSource

/**
 * Keeps the tab's state, and the copies it runs, through a rotation. The opened account, filter and hide-saved also
 * survive process death; the first load drops an account whose folder is gone.
 */
class StatusesHost(handle: SavedStateHandle) : ViewModel() {
    private val restored = handle.get<Bundle>(KEY)
    private var state: StatusesState? = null

    init {
        handle.setSavedStateProvider(KEY) {
            state?.let { bundleOf(ACCOUNT to it.account?.let { source -> "${source.accountIndex}/${source.packageName}" },
                FILTER to it.filter.name, HIDE_SAVED to it.hideSaved) } ?: Bundle()
        }
    }

    fun state(library: StatusLibrary?): StatusesState = state ?: run {
        val opened = restored?.getString(ACCOUNT)?.split('/', limit = 2)?.let { (index, pkg) ->
            index.toIntOrNull()?.let { StatusSource(pkg, it) }
        }
        StatusesState(library, viewModelScope, opened).apply {
            restored?.getString(FILTER)?.let { name -> StatusFilter.entries.firstOrNull { it.name == name } }?.let { filter = it }
            hideSaved = restored?.getBoolean(HIDE_SAVED) ?: false
        }.also { state = it }
    }

    private companion object {
        const val KEY = "statuses"
        const val ACCOUNT = "account"
        const val FILTER = "filter"
        const val HIDE_SAVED = "hideSaved"
    }
}

@Composable
fun rememberStatusesState(library: StatusLibrary?): StatusesState {
    val host = viewModel<StatusesHost>()
    return remember(host, library) { host.state(library) }
}
