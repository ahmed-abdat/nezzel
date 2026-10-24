package dev.ahmed.savlino.downloads

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.jobs.NezzelApplication

/** True while the heat guard holds downloads to one at a time; waiting rows then say why instead of "Queued". */
val LocalHot = staticCompositionLocalOf { false }

/** The heat guard's own rule ([dev.ahmed.savlino.jobs.TransferSlots]): severe heat runs one download when more were chosen. */
fun isHeatLimited(context: Context): Boolean {
    val chosen = (context.applicationContext as? NezzelApplication)?.preferences?.parallelDownloads ?: return false
    return chosen > 1 && thermalStatus(context) >= PowerManager.THERMAL_STATUS_SEVERE
}

private fun thermalStatus(context: Context): Int =
    try { context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
    catch (_: RuntimeException) { PowerManager.THERMAL_STATUS_NONE }

@Composable
fun rememberHot(): Boolean {
    val context = LocalContext.current
    val chosen = (context.applicationContext as? NezzelApplication)?.preferences?.parallelDownloadsFlow?.collectAsState()?.value ?: 1
    var status by remember { mutableIntStateOf(thermalStatus(context)) }
    DisposableEffect(context) {
        val power = context.getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { status = it }
        // A missing thermal service must not break the Downloads tab; the label then stays "Queued".
        val registered = try { power?.addThermalStatusListener(context.mainExecutor, listener); power != null }
            catch (_: RuntimeException) { false }
        onDispose { if (registered) try { power?.removeThermalStatusListener(listener) } catch (_: RuntimeException) { } }
    }
    return chosen > 1 && status >= PowerManager.THERMAL_STATUS_SEVERE
}
