package dev.ahmed.savlino.jobs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * The network as a running download sees it. The runner has no WorkManager constraint (running work can't drop one),
 * so it watches the network itself and stops only the transfer; a lost network never stops a convert or a save.
 */
interface NetworkWatch {
    fun online(): Boolean
    /** [lost] runs on a callback thread when no network can carry traffic; close() unregisters. */
    fun watch(lost: () -> Unit): AutoCloseable

    class Android(private val context: Context) : NetworkWatch {
        private val manager get() = context.getSystemService(ConnectivityManager::class.java)
        // Only "no default network" (airplane mode, out of range) counts as offline. A network without
        // internet still lets the engine try: its own transport errors decide, a stop here could loop.
        override fun online(): Boolean = manager?.let { it.activeNetwork != null } ?: true
        override fun watch(lost: () -> Unit): AutoCloseable {
            val manager = manager ?: return AutoCloseable { }
            val callback = object : ConnectivityManager.NetworkCallback() {
                // A Wi-Fi/mobile handover loses one network while the next is already default. During onLost the
                // manager can still name the lost network as active (airplane mode), so that counts as offline too.
                override fun onLost(network: Network) { if (manager.activeNetwork.let { it == null || it == network }) lost() }
            }
            val registered = try { manager.registerDefaultNetworkCallback(callback); true } catch (_: RuntimeException) { false }
            return AutoCloseable { if (registered) try { manager.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { } }
        }
    }
}

/** First link of each download chain: carries the CONNECTED constraint, so the runner after it never needs one. */
class NetworkGateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = Result.success()
}
