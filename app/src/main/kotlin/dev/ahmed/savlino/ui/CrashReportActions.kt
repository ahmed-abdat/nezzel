package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.diagnostics.CrashReports
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private reports leave the app only after this explicit action and the system chooser. */
@Composable
internal fun CrashReportActions() {
    val context = LocalContext.current
    val store = remember(context) { CrashReports.store(context) }
    val scope = rememberCoroutineScope()
    var available by remember(store) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        available = reportIo { store.hasReports() } == true
    }
    if (!available) return
    val shareLabel = stringResource(R.string.crash_share)
    CrashActionRow(shareLabel, R.drawable.ic_ms_bug_report, enabled = !busy) {
        busy = true
        scope.launch {
            try {
                val text = reportIo { store.latestReports() }
                if (text != null) {
                    val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text), shareLabel)
                    try { context.startActivity(chooser) }
                    catch (_: ActivityNotFoundException) { }
                    catch (_: SecurityException) { }
                }
                available = reportIo { store.hasReports() } == true
            } finally { busy = false }
        }
    }
    CrashActionRow(stringResource(R.string.crash_delete), R.drawable.ic_ms_delete, enabled = !busy) {
        busy = true
        scope.launch {
            try {
                reportIo { store.deleteReports() }
                available = reportIo { store.hasReports() } == true
            } finally { busy = false }
        }
    }
}

@Composable
private fun CrashActionRow(label: String, icon: Int, enabled: Boolean, onClick: () -> Unit) =
    SettingsNavRow(label, icon, enabled = enabled, chevron = false, onClick = onClick)

private suspend fun <T> reportIo(block: () -> T): T? = withContext(Dispatchers.IO) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null } // Private diagnostics must not create another crash or raw log output.
}
