package dev.ahmed.savlino.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat

@Composable
internal fun UpdateCard(release: Release, language: String, open: () -> Unit, dismiss: () -> Unit) {
    // A flat row on the page: the filled Update button is the only emphasis, so there is no box around it.
    Row(Modifier.fillMaxWidth().testTag("app-update-card"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Refresh, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_notification_title),
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

/**
 * Top of Settings: the app name and mark and the update state in one compact row, with the
 * manual check at its end. The automatic check switch lives under Advanced ([UpdateAutoSetting]); a missing
 * updater still shows the identity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSettings(updates: AppUpdates?, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val language = LocalConfiguration.current.locales[0].language
    val state = updates?.takeIf { it.available }?.state?.collectAsState()?.value
    // The nav dot follows any cached offer (automatic or restored), so Settings must expose it whatever the manual check did;
    // a manual check also reveals a skipped version
    val offer = state?.release?.takeIf { state.check == UpdateCheck.AVAILABLE || state.badge(BuildConfig.VERSION_CODE.toLong()) }
    // "Latest" only when no newer release is known (a skipped one is still newer).
    val latest = state != null && (state.check == UpdateCheck.LATEST ||
        (state.lastChecked != null && (state.release?.code ?: 0L) <= BuildConfig.VERSION_CODE.toLong()))
    val checking = state?.check == UpdateCheck.CHECKING
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        dev.ahmed.savlino.ui.NezzelMark(Modifier.size(48.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                state == null -> Unit
                offer != null -> Text(stringResource(R.string.update_notification_title),
                    style = MaterialTheme.typography.bodyMedium, color = colors.primary, fontWeight = FontWeight.Medium)
                state.check == UpdateCheck.FAILED ->
                    Text(stringResource(R.string.update_check_failed), style = MaterialTheme.typography.bodySmall, color = colors.error)
                else -> {
                    if (latest) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = colors.primary)
                        Text(stringResource(R.string.update_latest), style = MaterialTheme.typography.bodyMedium)
                    }
                    // The date and time never wrap apart (no stray "PM" or "م" on its own line).
                    state.lastChecked?.let { checked ->
                        Text(stringResource(R.string.settings_updates_last_checked) + " " +
                            NezzelFormat.firstStrong(NezzelFormat.checkedAt(checked, language).replace(' ', '\u00A0')),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (offer != null) {
            Button(onClick = open, modifier = Modifier.testTag("settings_update_offer").heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.update_action)) }
        } else if (updates != null && state != null) {
            val label = stringResource(R.string.settings_updates_check_now)
            // A spinner in place of the icon while checking; the label stays for TalkBack.
            FilledTonalIconButton(onClick = updates::checkNow, enabled = !checking,
                modifier = Modifier.semantics { contentDescription = label }) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
            }
        }
    }
}

/** Automatic check opt-out, under Settings → Advanced. */
@Composable
internal fun UpdateAutoSetting(updates: AppUpdates) {
    val state by updates.state.collectAsState()
    dev.ahmed.savlino.ui.SwitchSetting(stringResource(R.string.settings_updates_auto), stringResource(R.string.settings_updates_auto_body),
        state.automatic, dev.ahmed.savlino.R.drawable.ic_ms_update, updates::setAutomatic)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateScreen(state: AppUpdateState, language: String, canInstall: Boolean,
    back: () -> Unit, download: () -> Unit, cancel: () -> Unit, skip: (Long) -> Unit, install: () -> Unit, permission: () -> Unit) {
    val release = if (state.stage == UpdateStage.IDLE) state.release else state.transfer ?: state.release
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // The hero runs under a clear top bar; once the page scrolls, the bar turns solid so its title stays readable
    val bar by animateColorAsState(if (release == null || scroll.value > 0) colors.surface else Color.Transparent, label = "update_bar")
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.update_screen_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = bar),
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
        // The action slot stays pinned above the navigation bar; only in short landscape windows does it scroll with the page.
        val actions: @Composable ColumnScope.() -> Unit = {
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
                    if (!canInstall) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Info, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val direction = LocalLayoutDirection.current
        Column(Modifier.fillMaxSize().padding(start = insets.calculateStartPadding(direction),
            end = insets.calculateEndPadding(direction), bottom = insets.calculateBottomPadding())) {
            LaunchedEffect(state.stage) { if (compact && state.stage != UpdateStage.IDLE) scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(bottom = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val top = insets.calculateTopPadding()
                    UpdateHero(compact, top, Modifier.fillMaxWidth().height(top + if (compact) 112.dp else 248.dp))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.update_card_version),
                                Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            // The version is a quiet detail under the title, never in it.
                            Text(stringResource(R.string.update_meta, NezzelFormat.isolate(release.version), size),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        val notes = release.notes(language)
                        if (notes.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                                notes.forEach { NoteBullet(it) }

                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UpdateTrustRow(Icons.Default.Lock, stringResource(R.string.update_trust))
                            UpdateTrustRow(Icons.Default.CheckCircle, stringResource(R.string.update_keeps_data))
                        }
                    }
                }
                if (compact) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            if (!compact) Column(Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
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
    var awaitingInstallPermission by remember { mutableStateOf(false) }
    LaunchedEffect(canInstall, awaitingInstallPermission, state.stage) {
        if (canInstall && awaitingInstallPermission && state.stage == UpdateStage.READY) {
            awaitingInstallPermission = false
            updates.install()
        }
    }
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
        awaitingInstallPermission = true
        try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) { awaitingInstallPermission = false; updates.fail(UpdateFailure.INSTALL) }
    })
}
