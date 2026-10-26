package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape
import dev.ahmed.savlino.ui.ThumbnailKind
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.savlino.domain.VideoQuality
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.AppPreferences
import java.util.Locale
import dev.ahmed.savlino.ui.nav.ScrollOnTabReselect
import dev.ahmed.savlino.ui.ThumbnailCache
import dev.ahmed.savlino.ui.rememberCachedThumbnail
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.ui.TintedIcon
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.isLargeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * Running jobs in the order they started (oldest on top, so rows never jump as a second one starts), then the queue in the
 * order it will run, failures last, newest first. [jobs] arrive oldest first; [started] holds job IDs in start order
 * ([rememberStartOrder]); a running job it does not know keeps its place in [jobs].
 */
fun visibleJobs(jobs: List<DownloadProgress>, started: List<String> = emptyList()): List<DownloadProgress> {
    val (running, queued) = jobs.filter { !it.terminal || it.awaitingSchedule }.partition { it.running }
    val rank = started.withIndex().associate { (index, id) -> id to index }
    return running.sortedBy { rank[it.jobId] ?: Int.MAX_VALUE } + queued +
        jobs.filter { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }.asReversed()
}


/** Job IDs in the order this screen saw them start running; a job that stops running gives up its place. */
@Composable
internal fun rememberStartOrder(jobs: List<DownloadProgress>): List<String> {
    val order = remember { mutableListOf<String>() }
    val running = jobs.filter { it.running }.map { it.jobId }
    order.retainAll(running.toSet())
    running.forEach { if (it !in order) order += it }
    return order.toList()
}
fun runningCount(jobs: List<DownloadProgress>) = jobs.count { !it.terminal || it.awaitingSchedule }

/** Compatibility entry point for existing callers; all drawing lives behind the shared navigation seam. */
@Composable
fun DownloadNavigation(route: String, jobs: List<DownloadProgress>, language: String, updateAvailable: Boolean = false, onNavigate: (String) -> Unit) {
    dev.ahmed.savlino.ui.nav.NezzelNavigation(
        dev.ahmed.savlino.ui.nav.TopLevelDestinations, route,
        dev.ahmed.savlino.ui.nav.NavigationBadges(runningCount(jobs), jobs.any { it.phase == DownloadPhase.FAILED }, updateAvailable, language),
        onNavigate, {},
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    jobs: List<DownloadProgress>, language: String,
    onCancel: (String) -> Unit, onRemove: (String) -> Unit,
    onOpen: (SavedMedia) -> Unit, onShare: (SavedMedia) -> Unit, onOpenWith: (SavedMedia) -> Unit,
    onShowFiles: ((SavedMedia) -> Unit)?, onDelete: (SavedMedia) -> Unit, onPaste: () -> Unit,
    onRetry: (String) -> Unit = {}, onPause: (String) -> Unit = {}, onResume: (String) -> Unit = {},
    libraryLoad: LibraryLoadState = LibraryLoadState.Ready(emptyList()), onLibraryRetry: () -> Unit = {},
    onShareSelected: (List<SavedMedia>) -> Unit = {},
    onDeleteSelected: (List<SavedMedia>, (cleared: Boolean) -> Unit) -> Unit = { _, done -> done(true) },
    canShowFiles: (SavedMedia) -> Boolean = { true },
    onBatch: (BatchAction) -> Unit = {}, batchBusy: Boolean = false,
    playlists: List<dev.ahmed.savlino.jobs.PlaylistBatch> = emptyList(),
    playlistsLoaded: Boolean = true,
    onPlaylistAction: (String, PlaylistBatchAction) -> Unit = { _, _ -> }, playlistBusy: Set<String> = emptySet(),
    warnings: List<CheckWarning> = emptyList(), onTryAgain: (String) -> Unit = {}, onDismissWarning: (String) -> Unit = {},
    renameRepository: SavedMediaRepository? = null,
    /** Null: the real offer (tests and captures pass their own). */
    oldDownloads: OldDownloadsOffer? = null,
    openSavedUri: String? = null, onSavedOpened: () -> Unit = {},
    /** A playlist video's job id (a Home card): opens that playlist's sheet at the video. */
    openPlaylistJob: String? = null, onPlaylistJobOpened: () -> Unit = {},
    /** Retry one failed playlist video: the batch id and the video's position. */
    onPlaylistVideoRetry: (String, Int) -> Unit = { _, _ -> },
    /** Finished jobs: the coordinator drops them from [jobs] at once, so their Saved moment comes from here. */
    completed: kotlinx.coroutines.flow.Flow<DownloadProgress>? = null,
    /** "View files" after a batch: show Saved with every file, whatever tab was remembered. */
    openSaved: Boolean = false, onSavedShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val repository = renameRepository ?: remember(context) { SavedMediaRepository(context.contentResolver, context.packageName) }
    var sort by remember { mutableStateOf(preferences.savedSort) }
    var filterName by rememberSaveable { mutableStateOf(MediaFilter.ALL.name) }
    var renamingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val offer = oldDownloads ?: rememberOldDownloadsOffer(snackbar)
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var deletingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = libraryLoad.lastSuccessfulRows.orEmpty()
    val deleting = saved.find { it.uri.toString() == deletingUri }
    val memberIds = playlists.flatMap { batch -> batch.items.map { it.jobId } }.toSet()
    val standalone = jobs.filter { it.jobId !in memberIds }
    val started = rememberStartOrder(standalone)
    val active = rememberWithSavedMoment(standalone, remember(standalone, started) { visibleJobs(standalone, started) }, completed)
    val reduced = LocalReducedMotion.current
    val batches = rememberVisibleBatches(playlists)
    var tab by rememberDownloadsTab(jobs, playlists)
    // Downloading exists only while it holds something: a job (moving, waiting, paused, failed or in its Saved moment),
    // a playlist, something that did not save or a check to look at. Otherwise there are no tabs and the saved files are the screen.
    val failures = failureCount(jobs, playlists)
    val showTabs = active.isNotEmpty() || batches.isNotEmpty() || warnings.isNotEmpty() || failures > 0
    if (!showTabs && tab != DownloadsTab.SAVED) SideEffect { tab = DownloadsTab.SAVED }
    val current = if (showTabs) tab else DownloadsTab.SAVED
    val runningState = rememberLazyListState()
    ScrollOnTabReselect { if (current == DownloadsTab.RUNNING) runningState.scrollToItem(0) else listState.scrollToItem(0) }
    var waitingOpen by rememberSaveable { mutableStateOf(false) }
    val filters = remember(saved) { availableMediaFilters(saved) }
    val effectiveFilter = MediaFilter.valueOf(filterName).takeIf { it in filters } ?: MediaFilter.ALL
    LaunchedEffect(effectiveFilter) { filterName = effectiveFilter.name }
    val view = rememberLibraryView(preferences, effectiveFilter)
    var viewingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val open = { item: SavedMedia -> if (item.inViewer) viewingUri = item.uri.toString() else onOpen(item) }
    // Match the actual library row before opening: stale/deleted widget tiles must not open a different file.
    LaunchedEffect(openSavedUri, libraryLoad) {
        if (openSavedUri != null && libraryLoad !is LibraryLoadState.Loading) {
            val item = saved.firstOrNull { it.uri.toString() == openSavedUri }
            filterName = MediaFilter.ALL.name
            query = ""
            search = false
            tab = DownloadsTab.SAVED
            viewingUri = null
            if (item != null) open(item)
            onSavedOpened()
        }
    }
    LaunchedEffect(openSaved) {
        if (openSaved) {
            filterName = MediaFilter.ALL.name
            query = ""
            search = false
            tab = DownloadsTab.SAVED
            onSavedShown()
        }
    }
    // The open playlist sheet: its id, and the video to bring into view. The last snapshot keeps it valid if the batch leaves.
    var sheetBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetFocus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openPlaylistJob, playlists) {
        val id = openPlaylistJob ?: return@LaunchedEffect
        playlists.firstOrNull { batch -> batch.items.any { it.jobId == id } }?.let { batch ->
            tab = DownloadsTab.RUNNING
            sheetBatchId = batch.id
            sheetFocus = id
        }
        onPlaylistJobOpened()
    }
    val lastSheet = remember(sheetBatchId) { arrayOfNulls<dev.ahmed.savlino.jobs.PlaylistBatch>(1) }
    val sheetBatch = sheetBatchId?.let { id -> playlists.firstOrNull { it.id == id }?.also { lastSheet[0] = it } ?: lastSheet[0] }
    if (sheetBatchId != null && sheetBatch == null && playlistsLoaded) SideEffect { sheetBatchId = null; sheetFocus = null }
    sheetBatch?.let { batch ->
        PlaylistVideosSheet(batch, jobs, language, { onPlaylistAction(batch.id, it) }, { sheetBatchId = null; sheetFocus = null },
            busy = batch.id in playlistBusy, focusJob = sheetFocus, onRetryVideo = { onPlaylistVideoRetry(batch.id, it) })
    }
    val filtered = remember(saved, effectiveFilter, query, sort, language) {
        savedLibraryItems(saved, effectiveFilter, query, sort, Locale.forLanguageTag(language))
    }
    // Changing the sub-tab/filter/search exits selection; hidden files never receive a bulk action.
    var selectedUris by remember(current, filterName, query) { mutableStateOf(emptySet<String>()) }
    val selected = filtered.filter { it.uri.toString() in selectedUris }
    val selecting = selected.isNotEmpty()
    var deleteSelection by remember { mutableStateOf<List<SavedMedia>?>(null) }
    var selectionBusy by remember { mutableStateOf(false) }
    val clearSelection = { selectedUris = emptySet<String>() }
    fun deleteSelected(items: List<SavedMedia>) {
        selectionBusy = true
        onDeleteSelected(items) { cleared -> selectionBusy = false; if (cleared) clearSelection() }
    }
    LaunchedEffect(saved) { selectedUris = selectedUris.intersect(saved.map { it.uri.toString() }.toSet()) }
    // Date sections only make sense in chronological order; regrouping other sorts would scramble their order.
    val groups = remember(filtered, sort) {
        if (sort == SavedSort.NEWEST) filtered.groupBy { it.date } else linkedMapOf<LocalDate?, List<SavedMedia>>(null to filtered)
    }
    val today = LocalDate.now()
    val closeSearch = { search = false; query = "" }
    BackHandler(search, closeSearch)
    BackHandler(selecting) { clearSelection() }
    // The list keeps its first visible row anchored when something is inserted above it (the old-downloads card, the load
    // status), which cuts the new top item off. Composition still sees the state from before the change (also while
    // Downloading is shown and the list is not composed), so a list resting at its start is sent back to the start.
    val head = Triple(libraryLoad !is LibraryLoadState.Ready, offer.card && !selecting && !search, saved.isNotEmpty())
    val shownHead = remember { arrayOf(head) }
    if (shownHead[0] != head) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        shownHead[0] = head
    }
    // Results replace the list in place; start them at the top so a filtered list never opens mid-scroll.
    LaunchedEffect(query) { if (search && query.isNotEmpty()) listState.scrollToItem(0) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { Column {
        if (selecting) SelectionHeader(selected.size, language, selectionBusy, clearSelection, selected.size == filtered.size,
            { selectedUris = if (selected.size == filtered.size) emptySet() else filtered.map { it.uri.toString() }.toSet() }, { onShareSelected(selected) }, {
                if (Build.VERSION.SDK_INT >= 30) deleteSelected(selected) else deleteSelection = selected.toList()
            })
        else ScreenHeader(stringResource(R.string.downloads)) {
            if (current == DownloadsTab.SAVED) {
                if (saved.isNotEmpty()) LibraryViewToggle(view)
                if (saved.isNotEmpty()) SavedSortMenu(sort) { sort = it; preferences.savedSort = it }
                // One toggle in the same spot: Search opens the field under the bar, Close search shuts and clears it.
                if (search) IconButton(onClick = closeSearch) { Icon(Icons.Default.Close, stringResource(R.string.saved_search_close)) }
                else if (saved.isNotEmpty()) IconButton(onClick = { search = true }) { Icon(Icons.Default.Search, stringResource(R.string.saved_search)) }
            }
        }
        // The row folds away (and comes back) in place of a jump; reduced motion snaps.
        AnimatedVisibility(showTabs, enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + expandVertically(easeOut(reduced, Motion.TAB)),
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT)) + shrinkVertically(easeOut(reduced, Motion.TAB))) {
            DownloadsTabRow(current, downloadingCount(standalone, playlists), warnings.isNotEmpty(), language, failures) { tab = it }
        }
        // Pinned under the app bar, outside the list: always in view, and free to grow at 200% text.
        AnimatedVisibility(search && saved.isNotEmpty() && current == DownloadsTab.SAVED, enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + expandVertically(easeOut(reduced)),
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT)) + shrinkVertically(easeOut(reduced, Motion.FADE_OUT))) {
            SearchField(query, { query = it }, closeSearch)
        }
    } }) { padding ->
        val hot = LocalHot.current
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Crossfade(current, animationSpec = easeOut(reduced, Motion.TAB), label = "downloads-tab") { shown ->
            if (shown == DownloadsTab.RUNNING) LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("running_list"), state = runningState,
                contentPadding = PaddingValues(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.item)) {
                fun LazyItemScope.rowMotion() = Modifier.animateItem(fadeInSpec = easeOut(reduced), placementSpec = easeOut(reduced, 220), fadeOutSpec = easeOut(reduced, 120))
                @Composable fun LazyItemScope.JobItem(job: DownloadProgress) =
                    JobRow(job, { onCancel(job.jobId) }, { onRemove(job.jobId) }, { onRetry(job.jobId) }, rowMotion(),
                        onPause = { onPause(job.jobId) }, onResume = { onResume(job.jobId) })
                // Two or more queued rows fold into one "N waiting" line; one stays a row of its own.
                val waiting = active.filter { it.queuedOnly }.takeIf { it.size >= 2 }.orEmpty()
                val (failed, moving) = (active - waiting.toSet()).partition { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }
                // A finished playlist with failures lives only on its «Not saved» line; one still running keeps its row below too.
                val failedLists = failedPlaylists(batches, jobs)
                val listed = batches.filter { !(it.terminal && it in failedLists) }
                // One download alone: the tab's badge already says "1", so no header repeats it.
                val lone = active.size == 1 && runningCount(jobs) == 1 && batches.isEmpty() && warnings.isEmpty()
                // What did not save comes first, each with its reason and Retry; Retry all once two rows offer it.
                if (failed.isNotEmpty() || failedLists.isNotEmpty()) {
                    stickyHeader(key = "failed-header") {
                        val retry = failureRetry(failed, failedLists)
                        SectionHeader(stringResource(R.string.dl_failed_header), error = true, retry?.let { all -> {
                            BatchButton(BatchAction(BatchKind.RETRY, all.jobs + all.playlists), language, !batchBusy) {
                                if (all.jobs.isNotEmpty()) onBatch(BatchAction(BatchKind.RETRY, all.jobs))
                                all.playlists.forEach { onPlaylistAction(it, PlaylistBatchAction.RETRY) }
                            }
                        } })
                    }
                    items(failed, key = { "job-${it.jobId}" }, contentType = { "job" }) { job -> JobItem(job) }
                    items(failedLists, key = { "playlist-failed-${it.id}" }, contentType = { "playlist-failed" }) { batch ->
                        PlaylistFailureRow(batch, jobs, language, { onPlaylistAction(batch.id, PlaylistBatchAction.RETRY) },
                            { sheetFocus = null; sheetBatchId = batch.id }, busy = batch.id in playlistBusy, modifier = rowMotion())
                    }
                }
                val unfinished = active.filter { it !in failed }
                if (unfinished.isNotEmpty()) {
                    if (!lone) stickyHeader(key = "jobs-header") {
                        val batch = batchAction(unfinished)
                        SectionHeader(stringResource(if (runningCount(jobs) == 0) R.string.downloads_stopped_header else R.string.downloads_running_header,
                            NezzelFormat.count(if(runningCount(jobs) == 0) unfinished.size else runningCount(jobs), language)), runningCount(jobs) == 0,
                            batch?.let { { BatchButton(it, language, !batchBusy) { onBatch(it) } } })
                    }
                    // Severe heat holds downloads to one; say so once, above the rows that wait for it.
                    if (hot && active.any { it.waiting && !it.awaitingSchedule && !it.paused && !it.terminal })
                        item(key = "heat-note") { HeatNote(Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120))) }
                    items(moving, key = { "job-${it.jobId}" }, contentType = { "job" }) { job -> JobItem(job) }
                }
                if (waiting.isNotEmpty()) {
                    item(key = "waiting-line") {
                        WaitingLine(waiting.size, waitingOpen, language, Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120))) {
                            waitingOpen = !waitingOpen
                        }
                    }
                    if (waitingOpen) items(waiting, key = { "job-${it.jobId}" }, contentType = { "job" }) { job -> JobItem(job) }
                }
                listed.forEach { batch ->
                    item(key = "playlist-${batch.id}") {
                        PlaylistBatchRow(batch, language, { onPlaylistAction(batch.id, it) }, { sheetFocus = null; sheetBatchId = batch.id },
                            busy = batch.id in playlistBusy, modifier = Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120)))
                    }
                    items(batchMemberRows(batch, jobs), key = { "playlist-job-${it.jobId}" }, contentType = { "job" }) { job -> JobItem(job) }
                }
                // Result check: saved files that need a look (in memory only).
                if (warnings.isNotEmpty()) {
                    item(key = "warnings-header") { SectionHeader(stringResource(R.string.check_needs_look), error = true) }
                    items(warnings, key = { "warning-${it.jobId}" }, contentType = { "warning" }) { warning ->
                        CheckWarningRow(warning, (libraryLoad as? LibraryLoadState.Ready)?.rows?.firstOrNull { it.uri == warning.uri },
                            { onTryAgain(warning.jobId) }, { onDismissWarning(warning.jobId) }, rowMotion())
                    }
                }
            } else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("saved_list"), state = listState, contentPadding = PaddingValues(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.item)) {
                if (libraryLoad !is LibraryLoadState.Ready) item(key = "library-status") { LibraryLoadStatus(libraryLoad, onLibraryRetry) }
                if (offer.card && !selecting && !search) item(key = "old-downloads") {
                    OldDownloadsCard(offer.show, offer.dismiss, Modifier.animateItem(fadeInSpec = null, placementSpec = easeOut(reduced, 220),
                        fadeOutSpec = easeOut(reduced, 100)))
                }
                if (saved.isNotEmpty()) {
                    if (filters.isNotEmpty()) item(key = "filters") {
                        // One row that scrolls under the list gutter instead of wrapping (Arabic, large text).
                        Row(Modifier.bleedGutter().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter).testTag("filter_row"),
                            horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                            filters.forEach { filter ->
                                FilterChip(effectiveFilter == filter, { filterName = filter.name }, modifier = Modifier.heightIn(min = 48.dp),
                                    shape = CircleShape, colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    leadingIcon = if (filter == MediaFilter.STATUSES) { { Icon(painterResource(R.drawable.ic_status_ring), null, Modifier.size(18.dp)) } } else null,
                                    label = { Text(stringResource(when(filter) { MediaFilter.ALL -> R.string.filter_all; MediaFilter.VIDEO -> R.string.filter_videos; MediaFilter.AUDIO -> R.string.audio_group; MediaFilter.IMAGE -> R.string.filter_images; MediaFilter.STATUSES -> R.string.statuses_tab })) })
                            }
                        }
                    }
                    if (filtered.isEmpty()) item(key = "no-matches") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(stringResource(R.string.saved_no_matches))
                            if (query.isNotEmpty()) TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.saved_search_clear))
                            }
                        }
                    }
                    groups.forEach { (date, entries) ->
                        if (date != null) stickyHeader(key = "date-$date") { SectionHeader(when(date) {
                            today -> stringResource(R.string.saved_today)
                            today.minusDays(1) -> stringResource(R.string.saved_yesterday)
                            else -> mediaDate(date, language)
                        }) }
                        savedEntries(entries, view.grid, language, selecting, selectedUris, open, { if (!selectionBusy) selectedUris = selectedUris + it.uri.toString() },
                            { if (!selectionBusy) selectedUris = if (it.uri.toString() in selectedUris) selectedUris - it.uri.toString() else selectedUris + it.uri.toString() }) { item -> SavedRow(item, language, { open(item) }, { onShare(item) },
                            { onOpenWith(item) }, onShowFiles.takeIf { canShowFiles(item) }, { deletingUri = item.uri.toString() },
                            // No fade-in: filter switches must not flicker tens of rows; removal fades and neighbours slide.
                            Modifier.animateItem(fadeInSpec = null, placementSpec = easeOut(reduced, 220), fadeOutSpec = easeOut(reduced, 100)),
                            selecting = selecting, selected = item.uri.toString() in selectedUris, selectionEnabled = !selectionBusy,
                            renameRepository = repository, onRename = { renamingUri = item.uri.toString() },
                            onSelect = { selectedUris = selectedUris + item.uri.toString() },
                            onToggle = { selectedUris = if (item.uri.toString() in selectedUris) selectedUris - item.uri.toString() else selectedUris + item.uri.toString() }) }
                    }
                    // Where files live is reference, not a task: quiet, after the list.
                    item(key = "location") {
                        Row(Modifier.padding(vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.files_location, NezzelFormat.isolate(dev.ahmed.savlino.ui.saveFolderLabel())),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (libraryLoad is LibraryLoadState.Ready) item(key = "empty") {
                    // One quiet block on the page surface: a bare glyph (no tile), what this tab will hold, the one action.
                    Column(Modifier.fillMaxWidth().padding(top = Space.xxl * 2, bottom = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Space.s))
                        Text(stringResource(R.string.saved_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.saved_empty_body), Modifier.widthIn(max = 300.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Space.s))
                        FilledTonalButton(onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.paste_link)) }
                    }
                }
            }
            }
        }
    }
    viewingUri?.let { LibraryViewer(filtered, it, language, { viewingUri = null }, onShare, onOpenWith) }
    saved.find { it.uri.toString() == renamingUri }?.let { item ->
        SavedRenameDialog(item, onDismiss = { renamingUri = null }, onRename = { name ->
            val finalName = withContext(Dispatchers.IO) { repository.rename(item.uri, name) }
            if (finalName == null && !withContext(Dispatchers.IO) { repository.canRename(item.uri) }) {
                renamingUri = null
                scope.launch { snackbar.showSnackbar(context.getString(R.string.saved_rename_failed)) }
            }
            finalName
        }, onComplete = { finalName ->
            renamingUri = null
            scope.launch { snackbar.showSnackbar(context.getString(R.string.saved_renamed, NezzelFormat.isolate(finalName))) }
        })
    }
    deleteSelection?.let { items ->
        AlertDialog(onDismissRequest = { deleteSelection = null },
            title = { Text(stringResource(R.string.saved_delete_selected_title)) }, text = { Text(stringResource(R.string.saved_delete_selected_body)) },
            confirmButton = { TextButton(onClick = { deleteSelection = null; deleteSelected(items) }) { Text(stringResource(R.string.saved_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteSelection = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (deleting != null) AlertDialog(onDismissRequest = { deletingUri = null },
        title = { Text(stringResource(R.string.saved_delete_title)) }, text = { Text(stringResource(R.string.saved_delete_body)) },
        confirmButton = { TextButton(onClick = { deletingUri = null; onDelete(deleting) }) { Text(stringResource(R.string.saved_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deletingUri = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SavedSortMenu(sort: SavedSort, onSort: (SavedSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(value: SavedSort) = when (value) {
        SavedSort.NEWEST -> R.string.saved_sort_newest
        SavedSort.LARGEST -> R.string.saved_sort_largest
        SavedSort.NAME -> R.string.saved_sort_name
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_ms_sort), stringResource(R.string.saved_sort_accessibility, stringResource(label(sort))))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SavedSort.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(stringResource(label(choice))) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = choice == sort },
                    trailingIcon = { if (choice == sort) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onSort(choice) })
            }
        }
    }
}

/** Stem is selected on entry; the provider's extension stays visible and immutable outside the field. */
@Composable
internal fun SavedRenameDialog(item: SavedMedia, onDismiss: () -> Unit, onRename: suspend (String) -> String?, onComplete: (String) -> Unit) {
    var name by rememberSaveable(item.uri.toString(), stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(item.displayName, TextRange(0, item.displayName.length)))
    }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val valid = validSavedName(name.text)
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun submit() {
        if (busy || !valid) return
        busy = true
        scope.launch {
            try {
                val finalName = onRename(name.text)
                if (finalName != null) onComplete(finalName) else failed = true
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed = true
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.saved_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(value = name, onValueChange = { name = it; failed = false }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("saved_rename_name"),
                    label = { Text(stringResource(R.string.saved_rename_name)) }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    isError = !valid || failed,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                // AlertDialog subcomposes its content; request only after this field is attached.
                LaunchedEffect(focus) { focus.requestFocus() }
                val extension = savedExtension(item.filename)
                if (extension.isNotEmpty()) Text(stringResource(R.string.saved_rename_extension, NezzelFormat.isolate(extension)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!valid || failed) Text(stringResource(if (!valid) R.string.saved_rename_invalid else R.string.saved_rename_failed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = valid && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.saved_rename))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

/** The title can wrap at large text; all four actions retain their 48dp touch targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionHeader(count: Int, language: String, busy: Boolean, onClose: () -> Unit, allSelected: Boolean,
    onAll: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.saved_selection_close)) }
            Text(pluralStringResource(R.plurals.saved_selected, count, NezzelFormat.count(count, language)),
                Modifier.weight(1f).padding(vertical = Space.s).semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onAll, enabled = !busy, modifier = Modifier.size(48.dp)) {
                // A toggle: with every visible file selected, the same button clears the selection.
                Icon(if (allSelected) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle,
                    stringResource(if (allSelected) R.string.saved_deselect_all else R.string.saved_select_all))
            }
            IconButton(onClick = onShare, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Share, stringResource(R.string.share_file)) }
            IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Delete, stringResource(R.string.saved_delete)) }
        }
    }
}

/** Focused with the keyboard up as soon as it appears: tapping Search means "I'm about to type". */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val fieldLabel = stringResource(R.string.saved_search)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.s), contentAlignment = Alignment.TopCenter) {
        OutlinedTextField(query, onQuery, Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(focus)
            .semantics { contentDescription = fieldLabel },
            placeholder = { Text(fieldLabel) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); focus.requestFocus() }) {
                Icon(Icons.Default.Close, stringResource(R.string.saved_search_clear)) } },
            singleLine = true, shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
}

/** Heat guard note: plain words, no alarm colour; it goes away by itself when the phone cools. */
@Composable
private fun HeatNote(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(painterResource(R.drawable.ic_ms_thermostat), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.downloads_heat_note), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(text: String, error: Boolean = false, action: (@Composable RowScope.() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        // With an action the 48dp button sets the row's height and the title centres on it, level with the button's label.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(text, Modifier.weight(1f).then(if (action == null) Modifier.padding(top = Space.m, bottom = Space.xs) else Modifier)
                .semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                color = if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke(this)
        }
    }
}

/** Pause all / Resume all / Retry all at the header's end; acts at once (every action is reversible), TalkBack hears the count. */
@Composable
private fun BatchButton(batch: BatchAction, language: String, enabled: Boolean, onClick: () -> Unit) {
    val text = stringResource(when (batch.kind) {
        BatchKind.PAUSE -> R.string.downloads_pause_all
        BatchKind.RESUME -> R.string.downloads_resume_all
        BatchKind.RETRY -> R.string.downloads_retry_all
    })
    val label = stringResource(R.string.downloads_batch_accessibility, text,
        pluralStringResource(R.plurals.downloads_count, batch.ids.size, NezzelFormat.count(batch.ids.size, language)))
    TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("batch_action").semantics { contentDescription = label }) {
        Text(text, maxLines = 2, textAlign = TextAlign.End)
    }
}

/** Loading is unresolved; a retained snapshot remains useful while refreshing or retrying. */
@Composable
fun LibraryLoadStatus(load: LibraryLoadState, onRetry: () -> Unit) {
    when (load) {
        is LibraryLoadState.Loading -> Column(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.saved_loading), style = MaterialTheme.typography.bodyMedium)
            // A kept-but-hidden tab must not keep asking for frames.
            if (dev.ahmed.savlino.ui.nav.LocalTabActive.current.value) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator({ 0f }, Modifier.fillMaxWidth())
        }
        is LibraryLoadState.Error -> Column(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.saved_load_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.try_again)) }
        }
        is LibraryLoadState.Ready -> Unit
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SavedRow(item: SavedMedia, language: String, onOpen: () -> Unit, onShare: () -> Unit, onOpenWith: () -> Unit, onShowFiles: ((SavedMedia) -> Unit)?, onDelete: () -> Unit, modifier: Modifier = Modifier,
    selecting: Boolean = false, selected: Boolean = false, selectionEnabled: Boolean = true,
    onSelect: () -> Unit = {}, onToggle: () -> Unit = {},
    renameRepository: SavedMediaRepository, onRename: () -> Unit,
) {
    val selectLabel = stringResource(R.string.saved_select)
    val selectedState = stringResource(if (selected) R.string.saved_selected_state else R.string.saved_unselected_state)
    val openLabel = stringResource(R.string.open_file)
    val meta = mediaMeta(item, language)
    val metaStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Text needs ~9em of body text (em converted first: sp scaling is non-linear) and the whole meta line; below that the thumbnail shrinks, then moves above the text.
    val textNeed = with(density) { maxOf(MaterialTheme.typography.bodyLarge.fontSize.toDp() * 9,
        measurer.measure(meta, metaStyle, maxLines = 1).size.width.toDp()) }
    val row = Modifier.fillMaxWidth().heightIn(min = JobRowHeight).clip(MaterialTheme.shapes.medium)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        .semantics(mergeDescendants = true) {
            if (selecting) { toggleableState = ToggleableState(selected); stateDescription = selectedState }
        }.combinedClickable(enabled = selectionEnabled, role = if (selecting) Role.Checkbox else Role.Button,
            onClickLabel = if (selecting) null else openLabel, onClick = if (selecting) onToggle else onOpen,
            onLongClickLabel = if (selecting) null else selectLabel, onLongClick = onSelect).padding(vertical = Space.s)
    @Composable fun Action() {
        if (selecting) Checkbox(selected, null, Modifier.size(48.dp).clearAndSetSemantics {})
        else SavedMenu(item, meta, onShare, onOpenWith, onShowFiles, onDelete, renameRepository, onRename)
    }
    @Composable fun Texts(modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
        Text(NezzelFormat.firstStrong(item.displayName), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.postPosition?.let { position ->
            Text(stringResource(R.string.saved_post_position, NezzelFormat.count(position.index, language),
                NezzelFormat.count(position.total, language)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(meta, Modifier.fillMaxWidth(), style = metaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // The item's animation modifier goes on the outermost node, the lazy list's direct child.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Row chrome beside the text: two 12dp gaps and the 48dp menu.
        val textBeside = { thumb: androidx.compose.ui.unit.Dp -> maxWidth - thumb - Space.inline * 2 - 48.dp }
        val thumb = when {
            textBeside(96.dp) >= textNeed -> 96.dp
            textBeside(72.dp) >= textNeed -> 72.dp
            else -> null
        }
        // Too narrow even with the small thumbnail: it sits above the text, so the title and "720p · 68 MB" get the full row width.
        if (thumb == null) Column(row, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                MediaThumbnail(item, Modifier.size(128.dp, 72.dp))
                Spacer(Modifier.weight(1f))
                Action()
            }
            Texts(Modifier.fillMaxWidth())
        } else Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
            MediaThumbnail(item, Modifier.width(thumb).aspectRatio(16f / 9f))
            Texts(Modifier.weight(1f))
            Action()
        }
    }
}

/**
 * File actions in a bottom sheet headed by the file itself, so it is clear which download they act on.
 * The action starts at once (a chooser or the delete confirmation) while the sheet slides away under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedMenu(item: SavedMedia, meta: String, onShare: () -> Unit, onOpenWith: () -> Unit, onShowFiles: ((SavedMedia) -> Unit)?, onDelete: () -> Unit,
    repository: SavedMediaRepository, onRename: () -> Unit) {
    var open by rememberSaveable(item.uri.toString()) { mutableStateOf(false) }
    var canRename by remember(item.uri, repository, item.postPosition) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(open, item.uri, repository, item.postPosition) {
        canRename = null
        if (open) canRename = item.postPosition == null && withContext(Dispatchers.IO) { repository.canRename(item.uri) }
    }
    IconButton(onClick = { canRename = null; open = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.saved_more, item.displayName)) }
    if (!open) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val copy = LocalLinkCopy.current
    val link by produceState<String?>(null, item.uri) { value = copy?.find?.invoke(item) }
    val better = LocalBetterQuality.current
    val sharer = dev.ahmed.savlino.sharing.LocalFileSharer.current
    // Installed WhatsApp apps for this file type; null while the package manager answers.
    val targets by produceState<List<dev.ahmed.savlino.sharing.ShareTarget>?>(if (sharer == null) emptyList() else null, item.uri, sharer) {
        if (sharer != null) value = sharer.targets(item.mimeType)
    }
    val canLookForBetter by produceState(false, item.uri) {
        value = item.kind == SavedMediaKind.VIDEO && better?.eligible?.invoke(item) == true
    }
    fun act(action: () -> Unit) {
        action()
        scope.launch { sheet.hide() }.invokeOnCompletion { open = false }
    }
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.s)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.m),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                MediaThumbnail(item, Modifier.width(96.dp).aspectRatio(16f / 9f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                    Text(NezzelFormat.firstStrong(item.displayName), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(meta, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = Space.xl))
            Spacer(Modifier.height(Space.s))
            // With a WhatsApp installed: one row per app, then Other apps (the chooser). Without: the plain Share row.
            when {
                targets == null -> Spacer(Modifier.height(56.dp))
                targets.isNullOrEmpty() || sharer == null -> SheetAction(R.drawable.ic_ms_share, stringResource(R.string.share_file)) {
                    act { sharer?.share(item.uri, item.mimeType, null) ?: onShare() }
                }
                else -> dev.ahmed.savlino.sharing.ShareTargetRows(targets.orEmpty()) { target -> act { sharer?.share(item.uri, item.mimeType, target) } }
            }
            link?.let { url ->
                SheetAction(R.drawable.ic_ms_link, stringResource(R.string.copy_link),
                    supporting = android.net.Uri.parse(url).host?.removePrefix("www.")) { act { copy?.copy?.invoke(url) } }
            }
            if (canLookForBetter) SheetAction(R.drawable.ic_ms_tune, stringResource(R.string.better_quality_action),
                supporting = stringResource(R.string.better_quality_support), supportingLtr = false) { act { better?.open?.invoke(item) } }
            if (canRename == true) SheetAction(R.drawable.ic_ms_edit, stringResource(R.string.saved_rename)) {
                scope.launch { sheet.hide(); open = false; onRename() }
            } else if (canRename == null && item.postPosition == null) Spacer(Modifier.height(56.dp))
            SheetAction(R.drawable.ic_ms_open_in_new, stringResource(R.string.saved_open_with)) { act(onOpenWith) }
            if (onShowFiles != null) SheetAction(R.drawable.ic_ms_folder, stringResource(R.string.saved_show_files)) { act { onShowFiles(item) } }
            HorizontalDivider(Modifier.padding(horizontal = Space.xl, vertical = Space.s))
            SheetAction(R.drawable.ic_ms_delete, stringResource(R.string.saved_delete), MaterialTheme.colorScheme.error) { act(onDelete) }
        }
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
                        supporting: String? = null, supportingLtr: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.xl, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        TintedIcon(icon, tint)
        Column(verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
            // Hosts stay LTR inside Arabic.
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall.copy(textDirection = if (supportingLtr) TextDirection.Ltr else TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
/** Plain words only: audio says «صوت»/"Audio", video its quality; never file types like M4A/MP4 or bitrates. */
fun mediaMeta(item: SavedMedia, language: String): String = listOfNotNull(
    when (item.kind) {
        SavedMediaKind.IMAGE -> if (item.width != null && item.width > 0 && item.height != null && item.height > 0)
            stringResource(R.string.saved_image_dimensions, NezzelFormat.count(item.width, language), NezzelFormat.count(item.height, language)) else null
        // Isolated so «صوت» doesn't pull the size digits into an RTL run inside the LTR meta line.
        SavedMediaKind.AUDIO -> NezzelFormat.isolate(stringResource(R.string.audio_group))
        SavedMediaKind.VIDEO -> VideoQuality.of(item.width, item.height)
            ?.let { stringResource(R.string.video_quality, NezzelFormat.count(it, language)) }
        null -> null
    },
    item.size?.let { NezzelFormat.size(it, language) } ?: stringResource(R.string.saved_size_unknown)
).joinToString(stringResource(R.string.separator)).let(NezzelFormat::isolate)

private val SavedMedia.thumbnailKind get() = when (kind) { SavedMediaKind.VIDEO -> ThumbnailKind.VIDEO; SavedMediaKind.AUDIO -> ThumbnailKind.AUDIO; else -> ThumbnailKind.IMAGE }

/** Row and card artwork edge in pixels; Home recents and the Downloads list share these cache entries. */
private const val THUMB_PX = 320

/** Loads a saved file's list artwork; audio gives its embedded cover, or null when it has none. */
@Composable
private fun rememberThumbnailLoader(): suspend (SavedMedia) -> android.graphics.Bitmap? {
    val context = LocalContext.current
    val pictures = LocalMediaPictures.current
    return remember(context, pictures) { { item ->
        when {
            item.kind == null -> null
            pictures != null -> pictures.load(item.uri, item.thumbnailKind, THUMB_PX)
            else -> dev.ahmed.savlino.ui.decodeMediaThumbnail(context, item.uri, item.thumbnailKind, THUMB_PX, 180)
        }
    } }
}

@Composable
fun MediaThumbnail(item: SavedMedia, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium) {
    val load = rememberThumbnailLoader()
    // Audio shows its embedded cover; the music icon stays only when the file has none.
    val bitmap = rememberCachedThumbnail(ThumbnailCache.key(item.uri, THUMB_PX)) { load(item) }
    Surface(modifier.clip(shape), color = if (item.kind == SavedMediaKind.AUDIO) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if(loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (item.kind == SavedMediaKind.VIDEO) Icon(Icons.Default.PlayArrow, null)
            else Icon(painterResource(when(item.kind) { SavedMediaKind.IMAGE -> R.drawable.ic_image; SavedMediaKind.AUDIO -> R.drawable.ic_music; else -> R.drawable.ic_folder }), null, Modifier.size(24.dp))
            item.durationMillis?.takeIf { item.kind != SavedMediaKind.IMAGE }?.let { duration ->
                Surface(Modifier.align(Alignment.BottomEnd).padding(Space.xs), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .72f),
                    contentColor = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.extraSmall) {
                    Row(Modifier.padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        // Only over a cover: without one the centred music icon already says "audio".
                        if (item.kind == SavedMediaKind.AUDIO && loaded != null) Icon(painterResource(R.drawable.ic_music), null, Modifier.size(12.dp).padding(end = 2.dp))
                        Text(NezzelFormat.duration(duration / 1000), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecentFiles(media: List<SavedMedia>, language: String, onOpen: (SavedMedia) -> Unit, onSeeAll: () -> Unit) {
    if(media.isEmpty()) return
    val openLabel = stringResource(R.string.open_file)
    val largeText = isLargeText()
    // TextAlign.Start follows each paragraph's own direction; the captions need the layout's start edge instead.
    val captionAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (largeText || maxWidth < 300.dp) 1 else 2
        // The 48dp header row carries its own air: 4dp to the grid, 8dp between columns, 16dp between rows.
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.recent_files), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().semantics { heading() })
                TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.see_all)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
                media.take(4).chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { item ->
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.clickable(onClickLabel = openLabel) { onOpen(item) },
                                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                MediaThumbnail(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f), MaterialTheme.shapes.large)
                                // Full-width captions on the layout's start edge, whatever the title's own direction.
                                Text(NezzelFormat.firstStrong(item.displayName), Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content, textAlign = captionAlign))
                                Text(mediaMeta(item, language), Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr, textAlign = captionAlign))
                            }
                        }
                        if(row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Home's library: the newest files as a sideways row of fixed-size cards (the cut-off card at the end says there is
 * more), then an «Earlier» list. Real saved files only; thumbnails come from the tab-gated loader.
 * Downloads lead the row as cards of the same size, and a finished one becomes its file's card in the same slot, so
 * Home never changes height while files arrive (owner 2026-10-25). [jobs] is read in snapshots, never in this scope:
 * a progress tick recomposes only its own card.
 */
@Composable
fun HomeRecents(media: List<SavedMedia>, language: String, onOpen: (SavedMedia) -> Unit,
                jobs: () -> List<DownloadProgress> = { emptyList() }, onJob: (jobId: String) -> Unit = {},
                onShare: (SavedMedia) -> Unit = {}, onOpenWith: (SavedMedia) -> Unit = {}, onSeeAll: () -> Unit) {
    val reduced = LocalReducedMotion.current
    // A card opens the swipe viewer Downloads uses, over the same files in Home's order.
    // Own key: unkeyed saveables in one function share a key and restore in call order, and the row's
    // rememberLazyListState below sits after an early return, so a restored Home could hand its saved list here (rc3 crash).
    var viewing by rememberSaveable(key = "home-recents-viewing") { mutableStateOf<String?>(null) }
    val open = { item: SavedMedia -> if (item.inViewer) viewing = item.uri.toString() else onOpen(item) }
    val entries = remember(media) { homeEntries(media) }
    val recent = remember(entries) { entries.take(RECENT_ROW) }
    val earlier = remember(entries) { entries.drop(RECENT_ROW).take(EARLIER_ROWS) }
    // Bookkeeping outside state, updated inside the derivation like rememberStartOrder: the phase each job last had here,
    // running ids in start order, and jobs that finished here whose file is not listed yet.
    val seen = remember { HashMap<String, DownloadProgress>() }
    val started = remember { mutableListOf<String>() }
    val landing = remember { LinkedHashSet<String>() }
    val fileJobs = remember { LinkedHashMap<String, String>() }
    // Only a job that had a card here lands: one past the visible cards has no card to keep its slot with.
    val carded = remember { HashSet<String>() }
    val expiry = remember { mutableIntStateOf(0) }
    val slots by remember(media) {
        val listed = media.mapTo(HashSet()) { fileKey(it.uri) }
        derivedStateOf {
            expiry.intValue
            val all = jobs()
            // The coordinator drops a job from the list the moment it completes: one that leaves after its transfer
            // finished is landing, and keeps its slot until its file lists. Leaving mid-transfer is a cancel: the slot goes.
            val ids = all.mapTo(HashSet()) { it.jobId }
            seen.entries.removeAll { (id, last) -> (id !in ids).also { gone -> if (gone && last.transferDone && id in carded) landing += id } }
            all.forEach { job ->
                val before = seen.put(job.jobId, job)?.phase
                if (job.saved && before != null && before != DownloadPhase.COMPLETED && before != DownloadPhase.FAILED &&
                    before != DownloadPhase.CANCELLED && job.jobId in carded) landing += job.jobId
                if (!job.saved) landing -= job.jobId
            }
            val running = all.filter { it.running }.map { it.jobId }
            started.retainAll(running.toSet()); running.forEach { if (it !in started) started += it }
            // A landed job whose file listed is done landing: if that file is deleted later, no empty job card comes back.
            landing.removeAll { id -> fileJobs.any { (file, job) -> job == id && file in listed } }
            homeSlots(all, recent, listed, started, landing, fileJobs).also { shown ->
                carded.clear(); shown.forEach { if (it is JobSlot) carded += it.jobId }
                // Only the newest mappings can still hold a slot; the rest would grow with every download.
                fileJobs.keys.iterator().let { keys -> while (fileJobs.size > FILE_JOBS && keys.hasNext()) { keys.next(); keys.remove() } }
            }
        }
    }
    // A file that never lists (deleted at once, a failed query) gives its slot up after a while.
    slots.forEach { slot -> if (slot is JobSlot && slot.landing) key(slot.jobId) {
        LaunchedEffect(slot.jobId) { kotlinx.coroutines.delay(LANDING_MS); landing -= slot.jobId; expiry.intValue++ }
    } }
    if (slots.isEmpty()) return
    // A new card (a download, or a file landing) comes in at the start, and LazyRow keeps the old first card anchored.
    // If the row was at its start, show the new one. Scroll state is read only when the first key changes.
    val rowState = rememberLazyListState()
    val thumbs = dev.ahmed.savlino.ui.rememberThumbnailCache()
    val loadThumb = rememberThumbnailLoader()
    val firstKey = slots.first().key
    val shownFirst = remember { arrayOf(firstKey) }
    if (shownFirst[0] != firstKey) {
        if (rowState.firstVisibleItemIndex == 0 && rowState.firstVisibleItemScrollOffset == 0) rowState.requestScrollToItem(0)
        shownFirst[0] = firstKey
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recent_files), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.see_all)) }
        }
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            items(slots, key = { it.key }) { slot ->
                val motion = Modifier.animateItem(fadeInSpec = easeOut(reduced), placementSpec = easeOut(reduced, 220), fadeOutSpec = easeOut(reduced, 120))
                if (slot.key.startsWith("job:")) {
                    // One call site for a job's key, so its card fades into its file's card in place when the file lists.
                    val file = (slot as? FileSlot)?.item
                    val lastFile = remember { arrayOfNulls<SavedMedia>(1) }.also { if (file != null) it[0] = file }
                    // The job card stays until the file's picture is decoded, so the fade never lands on an empty box.
                    val jobId = slot.key.removePrefix("job:")
                    val ready = remember { mutableStateOf(file != null && (thumbs[ThumbnailCache.key(file.uri, THUMB_PX)] != null || jobs().none { it.jobId == jobId })) }
                    LaunchedEffect(file?.uri) {
                        if (file == null || ready.value) return@LaunchedEffect
                        // A file that just listed can fail to decode for a moment; audio may have no cover at all.
                        withTimeoutOrNull(PICTURE_WAIT_MS) {
                            while (thumbs.getOrLoad(ThumbnailCache.key(file.uri, THUMB_PX), retryMiss = true) { loadThumb(file) } == null &&
                                file.kind != SavedMediaKind.AUDIO && file.kind != null) kotlinx.coroutines.delay(120)
                        }
                        ready.value = true
                    }
                    Crossfade(file != null && ready.value, motion, easeOut(reduced, 300), label = "landed") { landed ->
                        val shown = if (landed) file ?: lastFile[0] else null
                        if (shown != null) RecentCard(shown, language, open) else JobSlotCard(jobId, jobs) { onJob(jobId) }
                    }
                } else RecentCard((slot as FileSlot).item, language, open, motion)
            }
        }
        if (earlier.isNotEmpty()) Column(Modifier.padding(horizontal = Space.l).padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val openLabel = stringResource(R.string.open_file)
            Text(stringResource(R.string.home_earlier), Modifier.heightIn(min = 48.dp).wrapContentHeight().semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            earlier.forEach { item ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).semantics(mergeDescendants = true) {}
                    .clickable(onClickLabel = openLabel) { open(item) }.heightIn(min = 72.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    MediaThumbnail(item, Modifier.width(112.dp).aspectRatio(16f / 9f), MaterialTheme.shapes.medium)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                        Text(NezzelFormat.firstStrong(item.displayName), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(mediaMeta(item, language), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    viewing?.let { LibraryViewer(groupDownloads(media), it, language, { viewing = null }, onShare, onOpenWith) }
}

/** A saved file's recents card: 196dp wide, 16:10 picture, title and meta. [HomeJobCard] keeps the same box. */
@Composable
private fun RecentCard(item: SavedMedia, language: String, onOpen: (SavedMedia) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(196.dp).clip(MaterialTheme.shapes.large).semantics(mergeDescendants = true) {}
        .clickable(onClickLabel = stringResource(R.string.open_file)) { onOpen(item) }, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        MediaThumbnail(item, Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(bottom = Space.xs), MaterialTheme.shapes.large)
        // Inset from the card's rounded clip: at the corners it cut the meta's first and last letters.
        Column(Modifier.padding(start = Space.xs, end = Space.xs, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(NezzelFormat.firstStrong(item.displayName), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mediaMeta(item, language), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Reads its own job from the snapshot: only this card recomposes on a progress tick. Keeps the last job while it leaves. */
@Composable
private fun JobSlotCard(jobId: String, jobs: () -> List<DownloadProgress>, onClick: () -> Unit) {
    val job by remember(jobId) { derivedStateOf { jobs().firstOrNull { it.jobId == jobId } } }
    val last = remember { arrayOfNulls<DownloadProgress>(1) }
    (job ?: last[0])?.let { last[0] = it; HomeJobCard(it, onClick) }
}

private const val RECENT_ROW = 8
private const val FILE_JOBS = 64
private val DownloadProgress.transferDone get() = !terminal && (phase in setOf(DownloadPhase.JOINING, DownloadPhase.CONVERTING,
    DownloadPhase.SAVING) || percent == 100)

/** How long a finished job waits for its file to list before it gives its slot up. */
private const val LANDING_MS = 10_000L
private const val EARLIER_ROWS = 3
/** How long a landed job waits for its file's picture before it fades to the file card anyway. */
private const val PICTURE_WAIT_MS = 1_500L

/**
 * A small top app bar that grows with its title: the fixed 64dp TopAppBar clips an Arabic headline
 * (38sp line) at 200% text. Same 16dp title inset, 4dp action inset and surface colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp)
            .padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = Space.s).semantics { heading() }) { ScreenTitle(title) }
            actions()
        }
    }
}

/** Same title voice on every tab: Home's brand line, Downloads and Settings. */
@Composable
fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
}

/** Widens a list item by the list's gutter on both sides, so a scrolling row runs to the screen edges. */
private fun Modifier.bleedGutter() = layout { measurable, constraints ->
    val extra = (Space.gutter * 2).roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(wide)
    layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width, placeable.height) { placeable.place(-extra / 2, 0) }
}
