package dev.ahmed.savlino.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat

@Composable
internal fun UpdateCard(release: Release, open: () -> Unit, dismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("app-update-card"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Refresh, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.update_card_version, NezzelFormat.isolate(release.version)), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(stringResource(R.string.update_action))
            }
            IconButton(onClick = dismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.update_not_now))
            }
        }
    }
}

@Composable
internal fun UpdateSettings(updates: AppUpdates, open: () -> Unit) {
    if (!updates.available) return
    val state by updates.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_updates_heading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(state.automatic, role = Role.Switch, onValueChange = updates::setAutomatic),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_updates_auto))
                Text(stringResource(R.string.settings_updates_auto_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.automatic, onCheckedChange = null)
        }
        TextButton(onClick = updates::checkNow, enabled = state.check != UpdateCheck.CHECKING,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_updates_check_now)) }
        if (state.check == UpdateCheck.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (state.check) {
            UpdateCheck.AVAILABLE -> state.release?.let { release ->
                TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_notification_title, NezzelFormat.isolate(release.version)))
                }
            }
            UpdateCheck.LATEST, UpdateCheck.FAILED -> Text(stringResource(if (state.check == UpdateCheck.LATEST) R.string.update_latest else R.string.update_check_failed),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateScreen(state: AppUpdateState, language: String, canInstall: Boolean,
    back: () -> Unit, download: () -> Unit, cancel: () -> Unit, skip: (Long) -> Unit, install: () -> Unit, permission: () -> Unit) {
    val release = if (state.stage == UpdateStage.IDLE) state.release else state.transfer ?: state.release
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.update_screen_title)) }, navigationIcon = {
        IconButton(onClick = back, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { insets ->
        if (release == null) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { Text(stringResource(R.string.update_latest)) }
            return@Scaffold
        }
        val size = NezzelFormat.isolate(NezzelFormat.size(release.size, language))
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
        val scroll = rememberScrollState()
        // Progress, the permission explainer and errors sit below the notes: keep them in view.
        LaunchedEffect(state.stage) { if (state.stage != UpdateStage.IDLE) scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.nezzel_splash_mark), null, Modifier.size(56.dp))
                Column {
                    Text(stringResource(R.string.update_card_version, NezzelFormat.isolate(release.version)), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.update_current, NezzelFormat.isolate(BuildConfig.VERSION_NAME)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(size, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val notes = release.notes(language)
            if (notes.isNotEmpty()) {
                Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                notes.forEach { note -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("•"); Text(note) } }
            }
            Text(stringResource(R.string.update_keeps_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.stage) {
                UpdateStage.DOWNLOADING -> {
                    LinearProgressIndicator(progress = { (state.bytes.toDouble() / release.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_progress, NezzelFormat.isolate(NezzelFormat.size(state.bytes, language)), size))
                    TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                }
                UpdateStage.VERIFYING, UpdateStage.INSTALLING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.stage == UpdateStage.VERIFYING) R.string.update_verifying else R.string.update_installing),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                UpdateStage.READY -> {
                    if (!canInstall) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.update_permission_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.update_permission_body))
                            Button(onClick = permission, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_permission_action)) }
                        }
                    } else Button(onClick = install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
                }
                UpdateStage.ERROR -> {
                    val message = when (state.failure) {
                        UpdateFailure.SPACE -> stringResource(R.string.update_failed_space, NezzelFormat.isolate(NezzelFormat.size(release.size + AppUpdates.MARGIN, language)))
                        UpdateFailure.VERIFY -> stringResource(R.string.update_failed_verify)
                        UpdateFailure.INSTALL -> stringResource(R.string.update_not_installed)
                        else -> stringResource(R.string.update_failed_network)
                    }
                    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                    Button(onClick = download, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.try_again)) }
                }
                UpdateStage.IDLE -> {
                    Button(onClick = download, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_download, size)) }
                    TextButton(onClick = { skip(release.code) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_skip)) }
                }
            }
            }
        }
        }
    }
}

@Composable
internal fun AppUpdateRoute(updates: AppUpdates, language: String, back: () -> Unit) {
    val state by updates.state.collectAsState()
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(updates, lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            updates.state.collect { pending ->
                if (pending.confirmation != null) {
                    val confirmation = updates.takeConfirmation() ?: return@collect
                    try { context.startActivity(confirmation) }
                    catch (_: Exception) { updates.fail(UpdateFailure.INSTALL) }
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canInstall = context.packageManager.canRequestPackageInstalls() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    UpdateScreen(state, language, canInstall, back, updates::download, updates::cancel, { updates.skip(it); back() }, updates::install, {
        try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) { updates.fail(UpdateFailure.INSTALL) }
    })
}
