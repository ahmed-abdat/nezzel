package dev.ahmed.savlino

import android.os.Build
import dev.ahmed.savlino.update.*
import dev.ahmed.savlino.storage.SaveDestinations
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.ahmed.savlino.downloads.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.jobs.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsScreen
import dev.ahmed.savlino.ui.OptionsState
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText
import dev.ahmed.savlino.ui.theme.rememberReducedMotion

/** Intake, revision-bound options, and journal-backed progress. */
@Composable
fun NezzelApp(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean,
    suggestions: Boolean, wallpaperRejected: Boolean, onPaste: () -> Unit,
    onDismissSuggestion: () -> Unit, onAcceptSuggestion: () -> Unit,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onDismissOptions: () -> Unit = links::dismissOptions,
    onAllowNotifications: () -> Unit = {},
    onAutomaticOptions: (Boolean) -> Unit = {},
    onHowItWorks: () -> Unit = {},
    notificationRationaleShowing: Boolean = false,
) {
    var updateBack by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Home") }
    SideEffect { if (links.destination in setOf("Home", "Settings", "Downloads")) updateBack = links.destination }
    BackHandler(links.destination != "Home") { links.navigate(if (links.destination == "AppUpdate") updateBack else "Home") }
    val reduced = rememberReducedMotion()
    val snackbar = remember { SnackbarHostState() }
    val library = rememberLibraryState(snackbar)
    val jobs by (links.downloads?.progress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<DownloadProgress>()) }).collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updates = (context.applicationContext as? NezzelApplication)?.appUpdates?.takeIf { it.available }
    val updateState by (updates?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AppUpdateState()) }).collectAsState()
    LaunchedEffect(updates, language) {
        updates?.refreshWhatsNew()
        if (updates?.consumeUpdated() == true) snackbar.showSnackbar(context.getString(R.string.update_done, NezzelFormat.isolate(BuildConfig.VERSION_NAME)))
    }
    val destinations = remember(context) { SaveDestinations(context) }
    val removeFailed: (String) -> Unit = { id -> scope.launch { links.downloads?.removeFailed(id) } }
    val retry: (String) -> Unit = { id ->
        scope.launch { if (links.downloads?.retry(id) != true) snackbar.showSnackbar(context.getString(R.string.download_retry_hint)) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(links.downloads, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { links.multipleLinkEvents.collect { count ->
                val formattedCount = NezzelFormat.count(count, language)
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.multiple_links, count, formattedCount), duration = SnackbarDuration.Short)
            } }
            launch { links.downloads?.cancelled?.collect {
                snackbar.showSnackbar(context.getString(R.string.download_cancelled))
            } }
            launch { links.imageEvents.collect { event ->
                snackbar.currentSnackbarData?.dismiss()
                if (event is ImageSaveState.BatchFinished) {
                    val message = dev.ahmed.savlino.ui.imageBatchMessage(context.resources, event, language)
                    snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
                    return@collect
                }
                if (event !is ImageSaveState.Saved) {
                    snackbar.showSnackbar(context.getString(if (event is ImageSaveState.Failed && event.storage == dev.ahmed.savlino.storage.StorageError.FolderUnavailable) R.string.save_folder_error else R.string.image_save_failed))
                    return@collect
                }
                val action = snackbar.showSnackbar(
                    context.getString(R.string.saved_snackbar, NezzelFormat.isolate(destinations.publishedFolderName(event.uri))),
                    context.getString(R.string.open_file), duration = SnackbarDuration.Long)
                if (action == SnackbarResult.ActionPerformed) {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(event.uri, "image/*")
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: android.content.ActivityNotFoundException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                    catch (_: SecurityException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                }
            } }
            links.downloads?.completed?.collect { job ->
                launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val action = snackbar.showSnackbar(
                        context.getString(R.string.saved_snackbar, NezzelFormat.isolate(destinations.publishedFolderName(job.publishedUri))),
                        context.getString(R.string.open_file), duration = SnackbarDuration.Long)
                    if(action == SnackbarResult.ActionPerformed) {
                        try { context.startActivity(DownloadNotifications.openIntent(job)) }
                        catch (_: android.content.ActivityNotFoundException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                        catch (_: SecurityException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                    }
                }
            }
        }
    }
    // Removing the old top-bar action must not give the URL editor implicit initial focus.
    // Keep a neutral focus target until the user chooses an input or navigation destination.
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
    Surface(Modifier.fillMaxSize().focusable(), color = MaterialTheme.colorScheme.surface) {
        val showOptions = !links.hasPendingShare && links.optionsState != OptionsState.Hidden
        // Onboarding replaces this composition in MainActivity. Other modals take priority;
        // keep the pending record intact until its own dismiss callback runs.
        val whatsNew = updateState.whatsNew
        if (updates != null && whatsNew != null && links.destination == "Home" &&
            links.optionsState == OptionsState.Hidden && !links.moreFormats && !links.hasPendingShare &&
            !links.hasPendingNotificationChoice && !notificationRationaleShowing) {
            WhatsNewSheet(whatsNew, language, updates::dismissWhatsNew)
        }
        if (links.destination == "AppUpdate" && updates != null) {
            Box(Modifier.fillMaxSize()) {
                AppUpdateRoute(updates, language) { links.navigate(updateBack) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        } else if (links.moreFormats && !links.hasPendingShare) {
            OptionsScreen(links, language, onDismissOptions) { SnackbarHost(snackbar) }
        } else {
            NavigationScaffold(links.destination, jobs, language, links::navigate,
                snackbarHost = { if (!showOptions) SnackbarHost(snackbar) },
                updateAvailable = updateState.badge(BuildConfig.VERSION_CODE.toLong())) {
                // Tabs are peers: fade-through, no travel. The old screen is gone before the new one settles.
                AnimatedContent(links.destination, contentAlignment = Alignment.TopStart, transitionSpec = {
                    if (reduced) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(Motion.FADE_IN, 70, Motion.EaseOut)) +
                        scaleIn(tween(Motion.TAB - 30, 70, Motion.EaseOut), initialScale = .98f)) togetherWith
                        fadeOut(tween(70, easing = Motion.EaseOut))
                }, label = "destination") { route ->
                    when (route) {
                        "Settings" -> SettingsScreen(links, language, theme, wallpaper, suggestions, wallpaperRejected,
                            onLanguage, onTheme, onWallpaper, onSuggestions, onAutomaticOptions, onHowItWorks)
                        "Downloads" -> DownloadsScreen(jobs, language,
                            { id -> links.cancelDownload(id) },
                            removeFailed, library.open, library.share, library.openWith,
                            library.showFiles, library.delete, { links.navigate("Home"); onPaste() },
                            retry, libraryLoad = library.load, onLibraryRetry = library.onRetry,
                            onShareSelected = library.shareSelected, onDeleteSelected = library.deleteSelected)
                        else -> HomeScreen(links, jobs, library, language, onPaste, onDismissSuggestion, onAcceptSuggestion, onAllowNotifications,
                            onRemove = removeFailed, onRetry = retry)
                    }
                }
            }
            if (showOptions) OptionsScreen(links, language, onDismissOptions) { SnackbarHost(snackbar) }
        }
    }
    }
}

/** A short window trades bottom chrome for a start rail; nested screens keep their vertical insets. */
@Composable
internal fun NavigationScaffold(route: String, jobs: List<DownloadProgress>, language: String,
                                onNavigate: (String) -> Unit, snackbarHost: @Composable () -> Unit = {},
                                updateAvailable: Boolean = false, content: @Composable () -> Unit) {
    val rail = LocalConfiguration.current.screenHeightDp < 480
    // Protect both physical edges (cutout or three-button navigation, including reverse landscape).
    // Consume once for the row so nested Scaffolds do not apply these horizontal insets again.
    val sideInsets = if (rail) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier
    Row(Modifier.fillMaxSize().then(sideInsets)) {
        if (rail) DownloadNavigation(route, jobs, language, updateAvailable, onNavigate)
        Scaffold(modifier = Modifier.weight(1f).fillMaxHeight(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { if (!rail) DownloadNavigation(route, jobs, language, updateAvailable, onNavigate) },
            snackbarHost = snackbarHost) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(links: LinkViewModel, jobs: List<DownloadProgress>, library: LibraryState, language: String, onPaste: () -> Unit,
                       onDismiss: () -> Unit, onAccept: () -> Unit, onAllowNotifications: () -> Unit,
                       onRemove: (String) -> Unit, onRetry: (String) -> Unit) {
    // The copied link is offered inline only while nothing else is in front of the person.
    val copiedHost = links.suggestionHost.takeIf {
        links.hasSuggestion && links.destination == "Home" && links.optionsState == dev.ahmed.savlino.ui.OptionsState.Hidden &&
            !links.hasPendingNotificationChoice
    }
    Scaffold(snackbarHost = {
        if (links.invalidShare) {
            Snackbar(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, action = {
                TextButton(onClick = links::clearShareMessage) { Text(stringResource(R.string.dismiss)) }
            }) { Text(stringResource(R.string.invalid_link)) }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            // Short content leaves the privacy line and studio credit at the bottom; long content scrolls them along.
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xl), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                // Title and subtitle are one section child, so the pair sits 4dp apart and 24dp above the field.
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(stringResource(R.string.app_name), Modifier.heightIn(min = 48.dp).wrapContentHeight().semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    // First run: nothing saved or running yet, so say what this place is for.
                    if (jobs.isEmpty() && library.media.isEmpty() && links.text.isEmpty())
                        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val updater = (LocalContext.current.applicationContext as? NezzelApplication)?.appUpdates
                if (updater?.available == true) {
                    val update by updater.state.collectAsState()
                    if (update.card(BuildConfig.VERSION_CODE.toLong())) update.release?.let { release ->
                        UpdateCard(release, language, { links.navigate("AppUpdate") }, updater::dismiss)
                    }
                }
                links.downloads?.let { downloads ->
                    val coordinatorError by downloads.error.collectAsState()
                    if (coordinatorError != null) {
                        Text(stringResource(R.string.download_coordinator_error), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (links.hasPendingShare) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(stringResource(R.string.pending_share), style = MaterialTheme.typography.bodyLarge)
                            links.pendingShareHost?.let { host ->
                                Text(stringResource(R.string.pending_share_from, NezzelFormat.isolate(host)),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                FilledTonalButton(onClick = links::acceptShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.replace_link)) }
                                TextButton(onClick = links::dismissShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dismiss)) }
                            }
                        }
                    }
                }
                links.nextLinkHost?.let { host ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(Space.l).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(stringResource(R.string.next_link, NezzelFormat.count(links.nextLinkNumber, language),
                                NezzelFormat.count(links.linkQueueTotal, language)),
                                style = MaterialTheme.typography.titleSmall)
                            Text(NezzelFormat.isolate(host), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                FilledTonalButton(onClick = links::openNextLink, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.open_file))
                                }
                                TextButton(onClick = links::skipRemainingLinks, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.skip_remaining_links))
                                }
                            }
                        }
                    }
                }
                HomeLinkCard(links, copiedHost, onAccept, onDismiss, onPaste)
                DownloadStrip(jobs, language, links::cancelDownload, onRemove, onRetry) { links.navigate("Downloads") }
                if(runningCount(jobs) > 0 && !androidx.core.app.NotificationManagerCompat.from(LocalContext.current).areNotificationsEnabled()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onAllowNotifications, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.allow_notifications)) }
                    }
                }
                LibraryLoadStatus(library.load, library.onRetry)
                RecentFiles(library.media, language, library.open) { links.navigate("Downloads") }
                if (links.engineUnavailable) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(Space.l).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(stringResource(R.string.unavailable_title), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.unavailable_body), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Column(Modifier.padding(top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                dev.ahmed.savlino.ui.MadeByHululsoft()
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean, suggestions: Boolean, rejected: Boolean,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onAutomaticOptions: (Boolean) -> Unit,
    onHowItWorks: () -> Unit,
) {
    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings)) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            // A heading sits 8dp over its own control and 24dp under the group before it.
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Choices(listOf("ar" to stringResource(R.string.language_ar), "en" to stringResource(R.string.language_en)), language, onLanguage)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Choices(listOf(ThemePref.System to stringResource(R.string.theme_system), ThemePref.Light to stringResource(R.string.theme_light),
                    ThemePref.Dark to stringResource(R.string.theme_dark)), theme, onTheme)
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchSetting(stringResource(R.string.match_wallpaper),
                        stringResource(if (rejected) R.string.wallpaper_rejected else R.string.wallpaper_help), wallpaper, onWallpaper)
                }
            }
            // Switch rows stack like list items: no gap, each 72dp.
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(R.string.link_handling), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                SwitchSetting(stringResource(R.string.copied_suggestions), stringResource(R.string.copied_help), suggestions, onSuggestions)
                SwitchSetting(stringResource(R.string.automatic_options), stringResource(R.string.automatic_options_help), links.automaticOptions, onAutomaticOptions)
            }
            dev.ahmed.savlino.ui.SaveFolderSetting()
            links.downloads?.let { downloads ->
                val engine by downloads.maintenance.state.collectAsState()
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(stringResource(R.string.download_tools), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.engine_version, NezzelFormat.isolate(engine.version ?: stringResource(R.string.engine_version_unknown))),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = links::updateEngine,
                            enabled = engine.status != EngineUpdateStatus.UPDATING,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.engine_update_now)) }
                    }
                    if (engine.status == EngineUpdateStatus.UPDATING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val builtIn = engine.usingBuiltIn && engine.status in setOf(EngineUpdateStatus.IDLE, EngineUpdateStatus.FAILED)
                    Text(stringResource(if (builtIn) R.string.engine_update_rolled_back else when (engine.status) {
                        EngineUpdateStatus.IDLE -> R.string.engine_update_help
                        EngineUpdateStatus.UPDATING -> R.string.engine_updating
                        EngineUpdateStatus.SUCCEEDED -> R.string.engine_updated
                        EngineUpdateStatus.FAILED -> R.string.engine_update_failed
                        EngineUpdateStatus.BUSY -> R.string.engine_update_busy
                    }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            (LocalContext.current.applicationContext as? NezzelApplication)?.appUpdates?.let { updates ->
                UpdateSettings(updates) { links.navigate("AppUpdate") }
            }
            dev.ahmed.savlino.ui.AboutSection(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, onHowItWorks, language = language)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Include the selected checkmark and button padding when deciding whether every label fits.
        val requiredWidth = with(density) {
            options.maxOf { (_, label) -> measurer.measure(label, labelStyle, maxLines = 1).size.width.toDp() + 64.dp } * options.size
        }
        if (isLargeText() || requiredWidth > maxWidth) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                options.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value == selected,
                        role = Role.RadioButton, onClick = { onSelect(value) }), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        RadioButton(value == selected, onClick = null); Text(label, Modifier.weight(1f))
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = value == selected, onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size), modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                }
            }
        }
    }
}

/**
 * A two-line list row on the screen's own 16dp gutter (a ListItem would add a second 16dp inset).
 * The whole row is one Switch target, so tapping the description toggles too; the switch itself only draws.
 */
@Composable
internal fun SwitchSetting(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}
