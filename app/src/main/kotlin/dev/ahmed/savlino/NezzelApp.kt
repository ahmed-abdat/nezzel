package dev.ahmed.savlino

import android.os.Build
import dev.ahmed.savlino.update.*
import dev.ahmed.savlino.storage.SaveDestinations
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.ahmed.savlino.ui.nav.*
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.PendingShareNote
import dev.ahmed.savlino.ui.NextLinkNote
import dev.ahmed.savlino.ui.EngineUnavailableNote
import dev.ahmed.savlino.ui.OptionsScreen
import dev.ahmed.savlino.ui.OptionsState
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.rememberReducedMotion

/** Intake, revision-bound options, and journal-backed progress. */
@Composable
fun NezzelApp(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean,
    suggestions: Boolean, onPaste: () -> Unit,
    onDismissSuggestion: () -> Unit, onAcceptSuggestion: () -> Unit,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onDismissOptions: () -> Unit = links::dismissOptions,
    onAllowNotifications: () -> Unit = {},
    onAutomaticOptions: (Boolean) -> Unit = {},
    onHowItWorks: () -> Unit = {},
    notificationRationaleShowing: Boolean = false,
    statusLibrary: dev.ahmed.savlino.statuses.StatusLibrary? = null,
    openSavedUri: String? = null, onSavedOpened: () -> Unit = {},
) {
    var updateBack by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Home") }
    SideEffect { if (links.destination in setOf("Home", "Settings", "Downloads", "Statuses")) updateBack = links.destination }
    BackHandler(links.destination != "Home") { links.navigate(if (links.destination == "AppUpdate") updateBack else "Home") }
    val reduced = rememberReducedMotion()
    val tabStateHolder = rememberSaveableStateHolder()
    val reselections = remember { TabReselections() }
    val onReselect = remember(reselections) { { destination: String -> reselections.reselect(destination) } }
    val onNavigate = remember(links) { { destination: String -> links.navigate(destination) } }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareLinks = remember(links.downloads) {
        links.downloads?.shareLinks()
    }
    val library = rememberLibraryState(snackbar, sweep = shareLinks?.let { it::sweep })
    val jobs by (links.downloads?.progress ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<DownloadProgress>()) }).collectAsState()
    val scope = rememberCoroutineScope()
    val sharer = dev.ahmed.savlino.sharing.rememberFileSharer { id -> scope.launch { snackbar.showSnackbar(context.getString(id)) } }
    // Both WhatsApp apps installed: the "Saved" snackbar's WhatsApp opens this choice for the file.
    var shareChoice by remember { mutableStateOf<Pair<DownloadProgress, List<dev.ahmed.savlino.sharing.ShareTarget>>?>(null) }
    // View files after a batch: Downloads opens on Saved even when it remembered Downloading.
    var showSaved by remember { mutableStateOf(false) }
    // The app scope outlives the file sheet, so a copy still lands after the sheet closes.
    val linkCopy = remember(shareLinks) {
        shareLinks?.let { share -> LinkCopy(share::link) { url -> scope.launch {
            copyHandled(url, links::markHandled) {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Nezzel", it))
            }
            // Android 13+ confirms a copy itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar(context.getString(R.string.link_copied))
        } } }
    }
    val warnings by (links.downloads?.warnings ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<dev.ahmed.savlino.jobs.CheckWarning>()) }).collectAsState()
    val tryAgain: (String) -> Unit = { id ->
        scope.launch { if (links.downloads?.tryAgain(id) == null) snackbar.showSnackbar(context.getString(R.string.download_retry_hint)) }
    }
    // Look for better quality: a video saved below BEST with a kept link; the tap goes Home and reads the link again.
    val betterQuality = remember(shareLinks) {
        shareLinks?.let { share -> dev.ahmed.savlino.downloads.BetterQualityLink(
            eligible = { item -> share.reopen(item)?.second?.let { it.format.hasVideo && it.preset != dev.ahmed.savlino.domain.Preset.BEST } == true },
            open = { item -> scope.launch {
                val reopen = share.reopen(item)?.second ?: return@launch
                links.navigate("Home")
                links.lookForBetter(reopen.link, reopen.format)
            } }) }
    }
    // Same scope: a clear started in Settings outlives the screen.
    val clearLinks = remember(links.downloads) {
        links.downloads?.let { downloads -> LinkHistoryClear(scope, downloads::clearShareLinks) { cleared ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(context.getString(if (cleared) R.string.clear_link_history_done else R.string.clear_link_history_failed))
        } }
    }
    val updates = (context.applicationContext as? NezzelApplication)?.appUpdates?.takeIf { it.available }
    val updateState by (updates?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AppUpdateState()) }).collectAsState()
    LaunchedEffect(updates, language) {
        updates?.refreshWhatsNew()
        if (updates?.consumeUpdated() == true) snackbar.showSnackbar(context.getString(R.string.update_done))
    }
    val destinations = remember(context) { SaveDestinations(context) }
    val removeFailed: (String) -> Unit = { id -> scope.launch { links.downloads?.removeFailed(id) } }
    val app = context.applicationContext as? NezzelApplication
    val pause: (String) -> Unit = { id -> scope.launch { app?.let { DownloadControls.pause(context, it, id) } } }
    val resume: (String) -> Unit = { id -> scope.launch { app?.let { DownloadControls.resume(context, it, id) } } }
    // Null until the first snapshot: a restored playlist sheet must not close on the not-yet-loaded empty list.
    val loadedPlaylists by remember(app) {
        app?.playlistBatches ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = null)
    val playlists = loadedPlaylists.orEmpty()
    var playlistBusy by remember { mutableStateOf(emptySet<String>()) }
    // A Home card of a playlist video opens Downloads at that playlist's sheet (Downloads finds the batch, then clears it).
    var playlistJob by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val playlistAction: (String, dev.ahmed.savlino.downloads.PlaylistBatchAction) -> Unit = { id, action ->
        if (id !in playlistBusy) {
            playlistBusy = playlistBusy + id
            scope.launch {
                try {
                    links.downloads?.let { coordinator ->
                        when (action) {
                            dev.ahmed.savlino.downloads.PlaylistBatchAction.PAUSE -> coordinator.pauseBatch(id)
                            dev.ahmed.savlino.downloads.PlaylistBatchAction.RESUME -> coordinator.resumeBatch(id)
                            dev.ahmed.savlino.downloads.PlaylistBatchAction.STOP -> coordinator.stopBatch(id)
                            dev.ahmed.savlino.downloads.PlaylistBatchAction.RETRY -> coordinator.retryBatch(id)
                        }
                    }
                } finally { playlistBusy = playlistBusy - id }
            }
        }
    }
    val playlistVideoRetry: (String, Int) -> Unit = { id, position ->
        if (id !in playlistBusy) {
            playlistBusy = playlistBusy + id
            scope.launch { try { links.downloads?.retryBatch(id, position) } finally { playlistBusy = playlistBusy - id } }
        }
    }
    val cancel: (String) -> Unit = { id -> links.cancelDownload(id); DownloadNotifications(context).dismissPaused(id) }
    // A paused notification outlives its pause only if the job expired or was removed elsewhere: clear it when the row leaves Paused.
    var pausedIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(jobs) {
        val now = jobs.filter { it.paused && !it.terminal }.map { it.jobId }.toSet()
        (pausedIds - now).forEach { DownloadNotifications(context).dismissPaused(it) }
        pausedIds = now
    }
    val retry: (String) -> Unit = { id ->
        scope.launch { if (links.downloads?.retry(id) != true) snackbar.showSnackbar(context.getString(R.string.download_retry_hint)) }
    }
    // One snackbar with the count of rows that actually changed; refused rows (finished meanwhile) are not counted.
    // One batch at a time: the button is disabled until it ends, so a double tap or a Pause all turning into Resume all never overlaps.
    var batchBusy by remember { mutableStateOf(false) }
    val batch: (BatchAction) -> Unit = batch@{ action ->
        if (batchBusy) return@batch
        val downloads = links.downloads ?: return@batch
        batchBusy = true
        scope.launch { try {
        val count = when (action.kind) {
            BatchKind.PAUSE -> app?.let { DownloadControls.pauseAll(context, it, action.ids) } ?: 0
            BatchKind.RESUME -> app?.let { DownloadControls.resumeAll(context, it, action.ids) } ?: 0
            BatchKind.RETRY -> downloads.retryAll(action.ids)
        }
        val message = when {
            count > 0 -> context.resources.getQuantityString(when (action.kind) {
                BatchKind.PAUSE -> R.plurals.downloads_paused_n
                BatchKind.RESUME -> R.plurals.downloads_resumed_n
                BatchKind.RETRY -> R.plurals.downloads_retried_n
            }, count, NezzelFormat.count(count, language))
            // Refused by the coordinator (engine update, WorkManager): say so here too, not only on Home.
            downloads.error.value != null -> context.getString(R.string.download_coordinator_error)
            action.kind == BatchKind.RETRY -> context.getString(R.string.download_retry_hint)
            else -> null
        }
        batchBusy = false
        if (message != null) { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
        } finally { batchBusy = false } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(links.downloads, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { links.multipleLinkEvents.collect { count ->
                val formattedCount = NezzelFormat.count(count, language)
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.multiple_links, count, formattedCount), duration = SnackbarDuration.Short)
            } }
            launch { links.emptyPasteEvents.collect {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.getString(R.string.paste_empty))
            } }
            launch { links.betterNoneEvents.collect {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.getString(R.string.better_quality_none))
            } }
            launch { links.downloads?.cancelled?.collect {
                snackbar.showSnackbar(context.getString(R.string.download_cancelled))
            } }
            // Queued after the photo result (never dismissing it): both messages are read.
            launch { links.soundFailedEvents.collect { snackbar.showSnackbar(context.getString(R.string.sound_save_failed)) } }
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
            val run = CompletionRun()
            links.downloads?.completed?.collect { job ->
                run.finished(job.jobId)
                launch {
                    val problem = job.check as? dev.ahmed.savlino.domain.ResultCheck.Problem
                    if (problem != null) {
                        run.problem()
                        snackbar.currentSnackbarData?.dismiss()
                        // A problem stays until handled: Try again (kept link) or dismiss.
                        val downloads = links.downloads
                        val canRetry = downloads?.warnings?.value?.any { it.jobId == job.jobId && it.canRetry } == true
                        val action = snackbar.showSnackbar(context.getString(DownloadNotifications.checkProblem(problem)),
                            if (canRetry) context.getString(R.string.try_again) else null, withDismissAction = true,
                            duration = SnackbarDuration.Indefinite)
                        if (action == SnackbarResult.ActionPerformed && downloads?.tryAgain(job.jobId) == null)
                            snackbar.showSnackbar(context.getString(R.string.download_retry_hint))
                        return@launch
                    }
                    // Mid-batch, each file posting its own snackbar kept one up for the whole batch over the paste bar;
                    // the cards and the batch header already say saved, so only the last file of a run speaks.
                    // A paused download is not part of the run: it may never finish, and this file would never speak.
                    if (links.downloads?.progress?.value.orEmpty().any { it.jobId != job.jobId && ((!it.terminal && !it.paused) || it.awaitingSchedule) }) return@launch
                    // A playlist feeds its videos a few at a time, so between two of them no other job may be listed yet.
                    val unfed = setOf(dev.ahmed.savlino.jobs.BatchItemState.WAITING, dev.ahmed.savlino.jobs.BatchItemState.QUEUED)
                    if (playlists.any { batch -> !batch.terminal && batch.items.any { it.jobId != job.jobId && it.state in unfed } }) return@launch
                    if (!run.isLast(job.jobId)) return@launch
                    val uri = job.publishedUri
                    val targets = uri?.let { sharer.targets(job.mimeType) }
                    // The lookup suspends: a file that finished meanwhile speaks for the whole run instead.
                    val size = run.take(job.jobId) ?: return@launch
                    val choice = targets?.let { dev.ahmed.savlino.sharing.completionAction(size, it) }
                    snackbar.currentSnackbarData?.dismiss()
                    val saved = context.getString(R.string.saved_snackbar, NezzelFormat.isolate(destinations.publishedFolderName(job.publishedUri)))
                    val label = when (choice) {
                        null -> context.getString(R.string.open_file)
                        dev.ahmed.savlino.sharing.CompletionAction.ViewSaved -> context.getString(R.string.view_saved_files)
                        is dev.ahmed.savlino.sharing.CompletionAction.Share -> choice.targets.singleOrNull()?.label
                            ?: context.getString(if (choice.targets.isEmpty()) R.string.share_file else R.string.share_whatsapp)
                    }
                    val action = snackbar.showSnackbar(
                        DownloadNotifications.checkLine(job.check)?.let { saved + "\n" + context.getString(it) } ?: saved,
                        label, duration = SnackbarDuration.Long)
                    if (action == SnackbarResult.ActionPerformed) when (choice) {
                        null -> try { context.startActivity(DownloadNotifications.openIntent(job)) }
                            catch (_: android.content.ActivityNotFoundException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                            catch (_: SecurityException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                        dev.ahmed.savlino.sharing.CompletionAction.ViewSaved -> { showSaved = true; links.navigate("Downloads") }
                        is dev.ahmed.savlino.sharing.CompletionAction.Share ->
                            if (choice.targets.size > 1) shareChoice = job to choice.targets
                            else if (uri != null) sharer.share(uri, job.mimeType, choice.targets.singleOrNull())
                    }
                }
            }
        }
    }
    // Removing the old top-bar action must not give the URL editor implicit initial focus.
    // Keep a neutral focus target until the user chooses an input or navigation destination.
    val artwork: suspend (String) -> android.graphics.Bitmap? = remember(links.downloads) {
        val store = links.downloads?.artwork; { id -> store?.load(id) }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, LocalOffline provides rememberOffline(),
        dev.ahmed.savlino.downloads.LocalHot provides dev.ahmed.savlino.downloads.rememberHot(), LocalJobArtwork provides artwork,
        dev.ahmed.savlino.sharing.LocalFileSharer provides sharer) {
    Surface(Modifier.fillMaxSize().focusable(), color = MaterialTheme.colorScheme.surface) {
        val showOptions = !links.hasPendingShare && links.optionsState != OptionsState.Hidden
        if (updates != null) AppUpdateSheets(updates, updateState, links, language, notificationRationaleShowing)
        if (links.destination == "AppUpdate" && updates != null) {
            Box(Modifier.fillMaxSize()) {
                AppUpdateRoute(updates, language) { links.navigate(updateBack) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        } else if (links.moreFormats && !links.hasPendingShare) {
            OptionsScreen(links, language, onDismissOptions) { SnackbarHost(snackbar) }
        } else {
            NavigationScaffold(links.destination, jobs, language, onNavigate, onReselect = onReselect,
                snackbarHost = { if (!showOptions) SnackbarHost(snackbar) },
                updateAvailable = updateState.badge(BuildConfig.VERSION_CODE.toLong())) {
                TabContent(links.destination, reselections, tabStateHolder) { route ->
                    CompositionLocalProvider(LocalLinkCopy provides linkCopy, dev.ahmed.savlino.downloads.LocalBetterQuality provides betterQuality) { when (route) {
                        "Settings" -> SettingsScreen(links, language, theme, wallpaper, suggestions,
                            onLanguage, onTheme, onWallpaper, onSuggestions, onAutomaticOptions, onHowItWorks, onClearLinks = clearLinks?.let { it::start }, clearingLinks = clearLinks?.busy == true)
                        "Statuses" -> dev.ahmed.savlino.statuses.ui.StatusesScreen(statusLibrary, language)
                        "Downloads" -> DownloadsScreen(jobs, language,
                            cancel,
                            removeFailed, library.open, library.share, library.openWith,
                            library.showFiles, library.delete, { links.navigate("Home"); onPaste() },
                            retry, pause, resume, libraryLoad = library.load, onLibraryRetry = library.onRetry,
                            onShareSelected = library.shareSelected, onDeleteSelected = library.deleteSelected,
                            canShowFiles = library.canShowFiles, onBatch = batch, batchBusy = batchBusy,
                            playlists = playlists, playlistsLoaded = loadedPlaylists != null, onPlaylistAction = playlistAction, playlistBusy = playlistBusy, onPlaylistVideoRetry = playlistVideoRetry,
                            warnings = warnings, onTryAgain = tryAgain, onDismissWarning = { links.downloads?.dismissWarning(it) },
                            openSavedUri = openSavedUri, onSavedOpened = onSavedOpened,
                            openPlaylistJob = playlistJob, onPlaylistJobOpened = { playlistJob = null },
                            completed = links.downloads?.completed, openSaved = showSaved, onSavedShown = { showSaved = false })
                        else -> HomeScreen(links, jobs, library, language, onPaste, onDismissSuggestion, onAcceptSuggestion, onAllowNotifications) { id ->
                            playlistJob = id
                            links.navigate("Downloads")
                        }
                    } }
                }
            }
            if (showOptions) OptionsScreen(links, language, onDismissOptions) { SnackbarHost(snackbar) }
            if (links.multi.visible) dev.ahmed.savlino.ui.MultiLinkSheet(links.multi, language, links::dismissMulti)
        }
        shareChoice?.let { (job, targets) ->
            dev.ahmed.savlino.sharing.ShareTargetsSheet(targets, { target ->
                job.publishedUri?.let { sharer.share(it, job.mimeType, target) }
            }) { shareChoice = null }
        }
    }
    }
}

/** A short window trades bottom chrome for a start rail; nested screens keep their vertical insets. */
@Composable
internal fun NavigationScaffold(route: String, jobs: List<DownloadProgress>, language: String,
                                onNavigate: (String) -> Unit, snackbarHost: @Composable () -> Unit = {},
                                updateAvailable: Boolean = false, onReselect: (String) -> Unit = {},
                                navigation: @Composable (String, NavigationBadges, (String) -> Unit, (String) -> Unit) -> Unit = { selected, badges, select, reselect ->
                                    NezzelNavigation(TopLevelDestinations, selected, badges, select, reselect)
                                }, content: @Composable () -> Unit) {
    val rail = LocalConfiguration.current.screenHeightDp < 480
    val badges = NavigationBadges(runningCount(jobs), jobs.any { it.phase == DownloadPhase.FAILED }, updateAvailable, language)
    // Protect both physical edges (cutout or three-button navigation, including reverse landscape).
    // Consume once for the row so nested Scaffolds do not apply these horizontal insets again.
    val sideInsets = if (rail) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier
    Row(Modifier.fillMaxSize().then(sideInsets)) {
        if (rail) navigation(route, badges, onNavigate, onReselect)
        Scaffold(modifier = Modifier.weight(1f).fillMaxHeight(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { if (!rail) navigation(route, badges, onNavigate, onReselect) },
            snackbarHost = snackbarHost) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }
}

@Composable
internal fun HomeScreen(links: LinkViewModel, jobs: List<DownloadProgress>, library: LibraryState, language: String, onPaste: () -> Unit,
                       onDismiss: () -> Unit, onAccept: () -> Unit, onAllowNotifications: () -> Unit,
                       onJob: (jobId: String) -> Unit = { links.navigate("Downloads") }) {
    val scroll = rememberScrollState()
    ScrollOnTabReselect { scroll.scrollTo(0) }
    // The copied link is offered inline only while nothing else is in front of the person.
    val copiedHost = links.suggestionHost.takeIf {
        links.hasSuggestion && links.destination == "Home" && links.optionsState == dev.ahmed.savlino.ui.OptionsState.Hidden &&
            !links.hasPendingNotificationChoice
    }
    // First run: the library loaded and nothing is saved or running yet, so the page says what Nezzel is for.
    // While loading or after a failed query the saved list is unknown, not empty.
    val firstRun = jobs.isEmpty() && library.load is LibraryLoadState.Ready && library.media.isEmpty()
    // Recents read the jobs in snapshots, so a progress tick redraws one card, not Home.
    val latestJobs = rememberUpdatedState(jobs)
    // The coordinator drops a job from its list the moment it completes. Home keeps the last few completions, so a job's
    // card knows the file it became and hands its slot to that file instead of leaving and sliding the row.
    val completedJobs = remember { mutableStateOf(emptyList<DownloadProgress>()) }
    LaunchedEffect(links.downloads) {
        links.downloads?.completed?.collect { job ->
            completedJobs.value = (completedJobs.value.filterNot { it.jobId == job.jobId } + job).takeLast(dev.ahmed.savlino.downloads.HOME_JOB_CARDS * 2)
        }
    }
    val homeJobs = { latestJobs.value.let { live -> live + completedJobs.value.filter { done -> live.none { it.jobId == done.jobId } } } }
    val reduced = LocalReducedMotion.current
    Scaffold(snackbarHost = {
        if (links.invalidShare) {
            Snackbar(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, action = {
                TextButton(onClick = links::clearShareMessage) { Text(stringResource(R.string.dismiss)) }
            }) { Text(stringResource(R.string.invalid_link)) }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // Short content keeps the first-run words just above the dock; long content scrolls.
                // Large text takes its room from the decorative logos, not from the words.
                val heroHeight = (maxHeight * .5f / LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceIn(180.dp, 440.dp)
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(bottom = Space.l),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                        HomeBrand()
                        HomeNotes(links, language)
                        // The first download takes the hero's place once: a fade, then the row stays for good.
                        AnimatedContent(firstRun, transitionSpec = {
                            (fadeIn(easeOut(reduced, 300)) togetherWith fadeOut(easeOut(reduced, Motion.FADE_OUT))).using(SizeTransform { _, _ -> easeOut(reduced, 300) })
                        },
                            label = "first run") { first ->
                            if (first) {
                                // The sites Nezzel saves from, around the mark; decorative, so TalkBack skips it.
                                dev.ahmed.savlino.update.UpdateHero(compact = false, top = 0.dp,
                                    Modifier.fillMaxWidth().height(heroHeight))
                            } else {
                                // A download is the row's first card, where its file will land: nothing above or below moves.
                                HomeRecents(library.media, language, library.open, homeJobs, onJob,
                                    library.share, library.openWith) {
                                    links.navigate("Downloads")
                                }
                            }
                        }
                        Box(Modifier.padding(horizontal = Space.l)) { LibraryLoadStatus(library.load, library.onRetry) }
                        if (links.engineUnavailable) Box(Modifier.padding(horizontal = Space.l)) { EngineUnavailableNote() }
                    }
                    NotificationsOffNote(jobs, firstRun, onAllowNotifications)
                    Crossfade(firstRun, animationSpec = easeOut(reduced, 300), label = "first run words") { first ->
                        if (first) FirstRunWords(Modifier.padding(horizontal = Space.xl).padding(top = Space.l))
                        else dev.ahmed.savlino.ui.HomeFooter(Modifier.padding(horizontal = Space.l).padding(top = Space.xl))
                    }
                }
            }
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.xs, bottom = Space.xs)) {
                HomeLinkCard(links, copiedHost, onAccept, onDismiss, onPaste)
            }
        }
    }
}

/** The mark beside the name, as in Settings' app card (owner 2026-10-21: "only the name without the logo"). */
@Composable
private fun HomeBrand() {
    Row(Modifier.padding(start = Space.l, end = Space.l, top = Space.s).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        dev.ahmed.savlino.ui.NezzelMark(Modifier.size(36.dp))
        Text(stringResource(R.string.app_name), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

/** Update card, errors, a pending share or the next queued link: whatever needs the person comes first. */
@Composable
private fun HomeNotes(links: LinkViewModel, language: String) {
    Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
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
        if (links.hasPendingShare) PendingShareNote(links.pendingShareHost, links::acceptShare, links::dismissShare)
        links.nextLinkHost?.let { host ->
            NextLinkNote(host, links.nextLinkNumber, links.linkQueueTotal, language, links::openNextLink, links::skipRemainingLinks)
        }
    }
}

/** Below the library, so appearing with a download and leaving after it never moves the cards above. */
@Composable
private fun NotificationsOffNote(jobs: List<DownloadProgress>, firstRun: Boolean, onAllowNotifications: () -> Unit) {
    if (!firstRun && runningCount(jobs) > 0 && !androidx.core.app.NotificationManagerCompat.from(LocalContext.current).areNotificationsEnabled()) {
        Column(Modifier.padding(horizontal = Space.l).padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAllowNotifications, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.allow_notifications)) }
        }
    }
}

@Composable
private fun FirstRunWords(modifier: Modifier = Modifier) {
    val arabic = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.home_hero_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 30.sp, lineHeight = if (arabic) 44.sp else 36.sp))
        Text(stringResource(R.string.home_hero_line), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.home_hero_sites), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Files saved since the last message: only the last of a run speaks, and one file can be shared while several are a batch. */
internal class CompletionRun {
    private var last: String? = null
    private var size = 0
    fun finished(jobId: String) { last = jobId; size++ }
    /** A problem speaks for itself and starts a new run. */
    fun problem() { size = 0 }
    fun isLast(jobId: String) = last == jobId
    /** The run's size, once, for its last file; null when a newer file finished since (it speaks for the run). */
    fun take(jobId: String): Int? = if (last != jobId) null else size.also { size = 0 }
}
