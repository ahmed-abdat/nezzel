package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The network as a running download sees it. The runner has no WorkManager constraint (running work can't drop one),
 * so it watches the network itself and stops only the transfer; a lost network never stops a convert or a save.
 */
interface NetworkWatch {
    fun online(): Boolean
    /** [lost] runs on a callback thread when no network can carry traffic; close() stops this observer. */
    fun watch(lost: () -> Unit): AutoCloseable

    class Android(context: Context) : NetworkWatch by Adapter(Connectivity.get(context))

    /** Runner view of the shared module; collecting never registers another platform callback. */
    class Adapter(private val connectivity: Connectivity) : NetworkWatch {
        override fun online(): Boolean = connectivity.snapshot.online
        override fun watch(lost: () -> Unit): AutoCloseable {
            // Undispatched collection is installed before watch returns; loss is handled on the emitting thread.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connectivity.state.map { it.online }.distinctUntilChanged().drop(1).collect { if (!it) lost() }
            }
            return AutoCloseable { scope.cancel() }
        }
    }
}

/** First link of each download chain: carries the CONNECTED constraint, so the runner after it never needs one. */
class NetworkGateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = Result.success()
}
