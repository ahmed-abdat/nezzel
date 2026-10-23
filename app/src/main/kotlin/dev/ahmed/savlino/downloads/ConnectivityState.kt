package dev.ahmed.savlino.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** True while the device has no network that can carry traffic; rows use it to tell "queued" from "waiting for network". */
val LocalOffline = staticCompositionLocalOf { false }

fun isOffline(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return true) ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Offline settles after a second so a Wi-Fi/mobile handover never flashes "Waiting for network"; back online is immediate. */
@Composable
fun rememberOffline(): Boolean {
    val context = LocalContext.current
    var raw by remember { mutableStateOf(isOffline(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { raw = false }
            override fun onLost(network: Network) { raw = isOffline(context) }
        }
        val registered = try { manager?.registerDefaultNetworkCallback(callback); manager != null }
            catch (_: RuntimeException) { false }
        onDispose { if (registered) try { manager?.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { } }
    }
    var settled by remember { mutableStateOf(raw) }
    LaunchedEffect(raw) { if (raw) delay(1000); settled = raw }
    return settled
}

/** True only when the default network says it is metered; unknown capabilities count as not metered, so nothing nags. */
fun isMetered(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/** Follows the default network while composed (a Wi-Fi/mobile switch updates it); the callback goes when the caller leaves. */
@Composable
fun rememberMetered(): Boolean {
    val context = LocalContext.current
    var metered by remember { mutableStateOf(isMetered(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            override fun onLost(network: Network) { metered = isMetered(context) }
        }
        val registered = try { manager?.registerDefaultNetworkCallback(callback); manager != null }
            catch (_: RuntimeException) { false }
        onDispose { if (registered) try { manager?.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { } }
    }
    return metered
}
