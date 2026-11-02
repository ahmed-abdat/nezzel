package dev.ahmed.savlino.downloads

import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.ui.nav.rememberTabResource
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.storage.OldDownloads
import dev.ahmed.savlino.storage.OldDownloadsPick
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [card]: the Downloads offer; [canGrant]: the Settings row (fresh install, no grant yet; upgrades never see it). */
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
    // Restore the known card before the deferred provider check, so returning never inserts it mid-scroll.
    var lastCard by rememberSaveable { mutableStateOf(false) }
    var lastCanGrant by rememberSaveable { mutableStateOf(false) }
    val state by rememberTabResource(lastCard to lastCanGrant, old, version) {
        withContext(Dispatchers.IO) { old.offerVisible() to (old.eligible && old.active() == null) }
    }
    SideEffect { lastCard = state.first; lastCanGrant = state.second }
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

/** Same anatomy as the Home update card (round glyph, title, hint, close); the whole card opens the folder picker. */
@Composable
fun OldDownloadsCard(onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onShow, modifier = modifier.fillMaxWidth().testTag("old_downloads_card"), shape = RoundedCornerShape(24.dp),
        color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(colors.secondary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_ms_history), null, Modifier.size(24.dp), tint = colors.onSecondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.old_downloads_offer), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.old_downloads_show), style = MaterialTheme.typography.bodyMedium,
                    color = colors.primary, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("old_downloads_dismiss")) {
                Icon(Icons.Default.Close, stringResource(R.string.dismiss), Modifier.size(20.dp))
            }
        }
    }
}
