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
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.storage.SaveDestination
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh() } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = !busy, role = Role.Button) {
            try { picker.launch((destinations.destination as? SaveDestination.Tree)?.uri) }
            catch (_: Exception) { failed = true }
        }.padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                Text(stringResource(R.string.change_save_folder), style = MaterialTheme.typography.bodyLarge)
                Text(NezzelFormat.isolate(name), style = MaterialTheme.typography.bodyMedium)
                if (!available) Text(stringResource(R.string.save_folder_unavailable), color = MaterialTheme.colorScheme.error)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        if (custom) TextButton(onClick = { choose(null) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.save_folder_default))
        }
        if (failed) Text(stringResource(R.string.save_folder_error), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
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
