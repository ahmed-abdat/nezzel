package dev.ahmed.savlino.jobs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A delayed platform callback can leave the flow behind the worker's synchronous snapshot. */
internal class FakeConnectivity(initial: ConnectivityState = ConnectivityState()) : Connectivity {
    private val updates = MutableStateFlow(initial)
    override val state = updates.asStateFlow()
    override var snapshot = initial
        private set
    fun set(value: ConnectivityState, notify: Boolean = true) {
        snapshot = value
        if (notify) updates.value = value
    }
}
