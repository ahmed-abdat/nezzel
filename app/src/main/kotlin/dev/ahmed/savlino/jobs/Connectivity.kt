package dev.ahmed.savlino.jobs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The default network has three distinct meanings; none promises that a particular host is reachable. */
data class ConnectivityState(
    /** Runner: only absence of a default network stops a transfer; missing INTERNET lets the engine try. */
    val online: Boolean = true,
    /** Notifications: immediate INTERNET-capability absence, preserving their existing snapshot copy. */
    val offline: Boolean = false,
    /** Rows/options: INTERNET absence sustained for one second; recovery is immediate. Initially offline stays offline. */
    val offlineSettled: Boolean = false,
    /** Mobile-data note: unknown capabilities or no default network mean false. */
    val metered: Boolean = false,
    /** Playlist recovery: only a VALIDATED default network resumes batches automatically. */
    val validated: Boolean = false,
)

/** One state for UI collectors and a synchronous snapshot for workers/notifications. */
interface Connectivity {
    val state: StateFlow<ConnectivityState>
    val snapshot: ConnectivityState get() = state.value

    companion object {
        private val instances = WeakHashMap<Context, Connectivity>()
        /** One lazy, process-lifetime registration, shared across application, activities and localized contexts. */
        fun get(context: Context): Connectivity = synchronized(instances) {
            instances.getOrPut(context.applicationContext) {
                AndroidConnectivity(context.getSystemService(ConnectivityManager::class.java))
            }
        }
    }
}

/** Callback events are authoritative: the manager can still name a lost default network inside onLost. */
private class AndroidConnectivity(private val manager: ConnectivityManager?) : Connectivity {
    private val handler = Handler(Looper.getMainLooper())
    private var current = manager?.activeNetwork
    private fun initial(): ConnectivityState {
        if (manager == null) return ConnectivityState()
        val capabilities = current?.let(manager::getNetworkCapabilities)
        val stranded = capabilities?.let(::vpnWithoutUnderlying) == true
        val offline = current == null || stranded || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
        return ConnectivityState(online = current != null && !stranded, offline = offline, offlineSettled = offline,
            metered = capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = !stranded && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }
    private val updates = MutableStateFlow(initial())
    override val state = updates.asStateFlow()
    private val settle = Runnable { synchronized(this) {
        if (updates.value.offline) updates.value = updates.value.copy(offlineSettled = true)
    } }

    private fun publish(next: ConnectivityState) {
        val previous = updates.value
        // Capabilities/metering updates during the same outage must not postpone the settle deadline.
        if (next.offline != previous.offline) {
            handler.removeCallbacks(settle)
            if (next.offline) handler.postDelayed(settle, 1_000)
        }
        updates.value = next.copy(offlineSettled = next.offline && previous.offlineSettled)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(this@AndroidConnectivity) {
            if (current != network) {
                current = network
                // API 26+ immediately follows availability with capabilities; only those can clear UI offline.
                // Unknown is never metered, and is not validated yet.
                publish(updates.value.copy(online = true, metered = false, validated = false))
            }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(this@AndroidConnectivity) {
            if (current != null && current != network) return@synchronized
            current = network
            val stranded = vpnWithoutUnderlying(capabilities)
            publish(updates.value.copy(online = !stranded,
                offline = stranded || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = !stranded && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
        }
        override fun onLost(network: Network) = synchronized(this@AndroidConnectivity) {
            // A handover makes the replacement default first. Ignore a late loss/capability event for the old one.
            if (current != null && current != network) return@synchronized
            current = null
            publish(updates.value.copy(online = false, offline = true, metered = false, validated = false))
        }
    }

    init {
        val registered = try { manager?.registerDefaultNetworkCallback(callback); manager != null }
            catch (_: RuntimeException) { false }
        // Existing mobile-data behavior: a refused callback must never leave a stale metered warning visible.
        if (!registered) updates.value = updates.value.copy(metered = false)
    }
}

/** An always-on VPN stays this app's default in airplane mode, still claiming INTERNET and VALIDATED; on API 29+
 * it carries its underlying network's transports, so a VPN with none of them has nothing to send traffic over. */
private fun vpnWithoutUnderlying(capabilities: NetworkCapabilities): Boolean =
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && listOf(NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_WIFI_AWARE, NetworkCapabilities.TRANSPORT_LOWPAN, 8 /* USB, API 31 */,
        9 /* THREAD, API 34 */, 10 /* SATELLITE, API 35 */).none(capabilities::hasTransport)

/** A coordinator-observed drop wins before the UI settles; a stalled offline transfer waits too. */
internal fun DownloadProgress.waitingForNetwork(offline: Boolean): Boolean = !terminal && !paused &&
    ((offline || networkWait) && waiting || offline && phase == DownloadPhase.DOWNLOADING && stalled == true)
