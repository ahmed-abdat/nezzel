package dev.ahmed.savlino.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.ui.nav.LocalTabActive
import kotlinx.coroutines.flow.collectLatest
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.storage.SaveDestination
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a folder check stays good for a quick return to Settings. */
private const val FOLDER_STALE_MS = 60_000L

@Composable
internal fun SaveFolderSetting() {
    val context = LocalContext.current
    val destinations = remember(context) { SaveDestinations(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var custom by remember { mutableStateOf(destinations.destination is SaveDestination.Tree) }
    var name by remember { mutableStateOf(destinations.displayName) }
    var available by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    suspend fun refresh() {
        available = withContext(Dispatchers.IO) { destinations.available() }
        custom = destinations.destination is SaveDestination.Tree
        name = destinations.displayName
    }
    fun choose(uri: android.net.Uri?) {
        busy = true
        scope.launch {
            failed = try { withContext(Dispatchers.IO) { destinations.choose(uri) }; false } catch (_: Exception) { true }
            refresh()
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) choose(uri)
    }
    val active = LocalTabActive.current
    val checkedAt = remember { longArrayOf(Long.MIN_VALUE) }
    LaunchedEffect(lifecycle, active) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Every resume re-checks the folder; a quick return to the tab keeps the last check unless it is a minute old.
            var resumed = true
            snapshotFlow { active.value }.collectLatest {
                val now = android.os.SystemClock.elapsedRealtime()
                if (it && (resumed || checkedAt[0] == Long.MIN_VALUE || now - checkedAt[0] > FOLDER_STALE_MS)) {
                    refresh()
                    checkedAt[0] = now
                    resumed = false
                }
            }
        }
    }
    // Emits rows straight into the Settings group: the folder, then a reset row only for a custom folder.
    SettingsNavRow(stringResource(R.string.change_save_folder), R.drawable.ic_ms_folder, body = NezzelFormat.isolate(name),
        note = when {
            // A failed pick is the actionable message, so it wins over the older "unavailable" state.
            failed -> stringResource(R.string.save_folder_error)
            !available -> stringResource(R.string.save_folder_unavailable)
            else -> null
        }, noteColor = MaterialTheme.colorScheme.error, liveNote = failed, enabled = !busy) {
        try { picker.launch((destinations.destination as? SaveDestination.Tree)?.uri) }
        catch (_: Exception) { failed = true }
    }
    if (custom) SettingsNavRow(stringResource(R.string.save_folder_default), null, enabled = !busy, chevron = false) { choose(null) }
}

/** Stored display name only: no provider I/O during composition. */
@Composable
internal fun saveFolderLabel(): String {
    val context = LocalContext.current
    val destinations = remember(context) { SaveDestinations(context) }
    var name by remember { mutableStateOf(destinations.displayName) }
    DisposableEffect(destinations) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> name = destinations.displayName }
        destinations.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { destinations.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return name
}
