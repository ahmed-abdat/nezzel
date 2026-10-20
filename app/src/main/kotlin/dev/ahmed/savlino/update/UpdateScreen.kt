package dev.ahmed.savlino.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.ui.UpdateIllustration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat

internal fun openUpdateReleasePage(context: Context, version: String? = null) {
    val page = if (version == null) "latest" else "tag/v$version"
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ahmed-abdat/nezzel/releases/$page"))) }
    catch (_: ActivityNotFoundException) {}
}

@Composable
internal fun UpdateCard(release: Release, language: String, open: () -> Unit, dismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("app-update-card"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Refresh, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_notification_title, NezzelFormat.isolate(release.version)),
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(release.notes(language).firstOrNull() ?: stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = open, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
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
        state.lastChecked?.let { checked ->
            val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
            Text(stringResource(R.string.settings_updates_last_checked, NezzelFormat.firstStrong(NezzelFormat.shortDateTime(checked, language))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.check == UpdateCheck.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.check == UpdateCheck.LATEST || state.check == UpdateCheck.FAILED)
            Text(stringResource(if (state.check == UpdateCheck.LATEST) R.string.update_latest else R.string.update_check_failed),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // The nav dot follows any cached offer (automatic or restored), so Settings must expose it whatever the manual check did;
        // a manual check also reveals a skipped version
        if (state.check == UpdateCheck.AVAILABLE || state.badge(BuildConfig.VERSION_CODE.toLong())) state.release?.let { release ->
            TextButton(onClick = open, modifier = Modifier.testTag("settings_update_offer").heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_notification_title, NezzelFormat.isolate(release.version)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateScreen(state: AppUpdateState, language: String, canInstall: Boolean,
    back: () -> Unit, download: () -> Unit, cancel: () -> Unit, skip: (Long) -> Unit, install: () -> Unit, permission: () -> Unit) {
    val context = LocalContext.current
    val release = if (state.stage == UpdateStage.IDLE) state.release else state.transfer ?: state.release
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val colors = MaterialTheme.colorScheme
    val heroTop = colors.primaryContainer.copy(alpha = 0.45f).compositeOver(colors.background)
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.update_screen_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = if (release == null) colors.surface else heroTop),
            navigationIcon = {
                IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.back))
                }
            })
    }) { insets ->
        if (release == null) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { Text(stringResource(R.string.update_latest)) }
            return@Scaffold
        }
        val size = NezzelFormat.isolate(NezzelFormat.size(release.size, language))
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val scroll = rememberScrollState()
            LaunchedEffect(state.stage) { if (state.stage != UpdateStage.IDLE) scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.fillMaxWidth().height(if (compact) 112.dp else 200.dp)
                        .background(Brush.verticalGradient(listOf(heroTop, colors.background))), contentAlignment = Alignment.Center) {
                        UpdateIllustration(compact, Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.update_card_version, NezzelFormat.isolate(release.version)),
                                Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.update_meta, NezzelFormat.isolate(BuildConfig.VERSION_NAME), size),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        val notes = release.notes(language)
                        if (notes.isNotEmpty()) Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                                notes.take(6).forEach { NoteBullet(it) }
                                TextButton(onClick = { openUpdateReleasePage(context, release.version) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.update_full_notes))
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UpdateTrustRow(Icons.Default.Lock, stringResource(R.string.update_trust))
                            UpdateTrustRow(Icons.Default.CheckCircle, stringResource(R.string.update_keeps_data))
                        }
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state.stage) {
                        UpdateStage.DOWNLOADING -> {
                            val progress = (state.bytes.toDouble() / release.size).toFloat().coerceIn(0f, 1f)
                            UpdateProgressButton(stringResource(R.string.update_downloading,
                                NezzelFormat.isolate(NezzelFormat.percent((progress * 100).toInt(), language))), progress)
                            Text(stringResource(R.string.update_progress, NezzelFormat.isolate(NezzelFormat.size(state.bytes, language)), size),
                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                        }
                        UpdateStage.VERIFYING, UpdateStage.INSTALLING -> UpdateProgressButton(
                            stringResource(if (state.stage == UpdateStage.VERIFYING) R.string.update_verifying else R.string.update_installing))
                        UpdateStage.READY -> {
                            if (!canInstall) Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.update_permission_title), style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.fillMaxWidth().semantics { heading() })
                                    Text(stringResource(R.string.update_permission_body), Modifier.fillMaxWidth())
                                }
                            }
                            Button(onClick = if (canInstall) install else permission, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(stringResource(if (canInstall) R.string.update_install else R.string.update_permission_action))
                            }
                        }
                        UpdateStage.ERROR -> {
                            val message = when (state.failure) {
                                UpdateFailure.SPACE -> stringResource(R.string.update_failed_space, NezzelFormat.isolate(NezzelFormat.size(requiredUpdateSpace(release.size), language)))
                                UpdateFailure.VERIFY -> stringResource(R.string.update_failed_verify)
                                UpdateFailure.INSTALL -> stringResource(R.string.update_not_installed)
                                else -> stringResource(R.string.update_failed_network)
                            }
                            Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
                            Button(onClick = download, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.try_again)) }
                            if (state.failure == UpdateFailure.INSTALL || state.failure == UpdateFailure.VERIFY) {
                                val label = stringResource(R.string.update_open_release)
                                TextButton(onClick = { openUpdateReleasePage(context) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }) { Text(label) }
                            }
                        }
                        UpdateStage.IDLE -> {
                            Button(onClick = download, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.update_download, size)) }
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = back, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.update_not_now), textAlign = TextAlign.Center, maxLines = 2)
                                }
                                TextButton(onClick = { skip(release.code) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.update_skip), textAlign = TextAlign.Center, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateTrustRow(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A non-clickable action slot with progress that grows from the layout's start edge. */
@Composable
internal fun UpdateProgressButton(label: String, progress: Float? = null) {
    val animated by animateFloatAsState(progress ?: 1f, tween(200), label = "update_progress")
    Surface(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {
        progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
        liveRegion = LiveRegionMode.Polite
        contentDescription = label
    }, shape = ButtonDefaults.shape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(animated)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (progress == null) 0.6f else 1f)))
            }
            Text(label, Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
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
