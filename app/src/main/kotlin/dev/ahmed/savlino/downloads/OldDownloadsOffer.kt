package dev.ahmed.savlino.downloads

import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.storage.OldDownloads
import dev.ahmed.savlino.storage.OldDownloadsPick
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [card]: the Downloads offer; [canGrant]: the Settings row (no grant yet). */
class OldDownloadsOffer(val card: Boolean, val canGrant: Boolean, val show: () -> Unit, val dismiss: () -> Unit)

/** One per screen; every instance follows the same prefs, so a grant from Settings hides the Downloads card too. */
@Composable
fun rememberOldDownloadsOffer(snackbar: SnackbarHostState): OldDownloadsOffer {
    val context = LocalContext.current
    val old = remember(context) {
        OldDownloads(context) { (context.applicationContext as? NezzelApplication)?.usage?.count(UsageKeys.OLD_DOWNLOADS_FOUND) }
    }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(old) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        old.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { old.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // A grant can be removed in system settings while Nezzel is in the background.
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { version++ } }
    val state by produceState(false to false, old, version) {
        value = withContext(Dispatchers.IO) { old.offerVisible() to (old.active() == null) }
    }
    lateinit var launch: () -> Unit
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            val pick = withContext(Dispatchers.IO) { old.onPicked(result.data?.data) }
            // A cancel stays silent: on Android 11+ a missing folder also comes back as a cancel.
            if (pick == OldDownloadsPick.WRONG_FOLDER) {
                val answer = snackbar.showSnackbar(context.getString(R.string.old_downloads_wrong_folder),
                    context.getString(R.string.try_again), withDismissAction = true, duration = SnackbarDuration.Long)
                if (answer == SnackbarResult.ActionPerformed) launch()
            }
        }
    }
    launch = { try { picker.launch(old.grantIntent()) } catch (_: ActivityNotFoundException) { } }
    return OldDownloadsOffer(state.first, state.second, launch) { old.dismiss() }
}

/** One quiet tonal block above the library; flat, no divider. */
@Composable
fun OldDownloadsCard(onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("old_downloads_card"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        @Composable fun Glyph() = Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(24.dp))
        @Composable fun Message(m: Modifier) = Text(stringResource(R.string.old_downloads_offer), m, style = MaterialTheme.typography.bodyLarge)
        @Composable fun Show() = Button(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp).testTag("old_downloads_show")) {
            Text(stringResource(R.string.old_downloads_show))
        }
        @Composable fun Close() = IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("old_downloads_dismiss")) {
            Icon(Icons.Default.Close, stringResource(R.string.dismiss), Modifier.size(20.dp))
        }
        // Large text: the words get the full width and the button drops under them.
        if (isLargeText()) Column(Modifier.padding(start = Space.rowH, end = Space.xs, top = Space.xs, bottom = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Glyph(); Spacer(Modifier.weight(1f)); Close() }
            Message(Modifier.padding(end = Space.m, bottom = Space.s))
            Show()
        } else Row(Modifier.padding(start = Space.rowH, end = Space.xs, top = Space.s, bottom = Space.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
            Glyph()
            Message(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) { Show(); Close() }
        }
    }
}
