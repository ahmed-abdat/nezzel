package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.ahmed.savlino.ui.TintedIcon
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.isLargeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Composable
fun DownloadNavigation(route: String, jobs: List<DownloadProgress>, language: String, updateAvailable: Boolean = false, onNavigate: (String) -> Unit) {
    val rail = LocalConfiguration.current.screenHeightDp < 480
    val count = runningCount(jobs)
    val failed = jobs.any { it.phase == DownloadPhase.FAILED }
    val settingsLabel = stringResource(R.string.settings_update_accessibility)
    val label = stringResource(when { count > 0 -> R.string.downloads_running_accessibility; failed -> R.string.downloads_failed_accessibility; else -> R.string.downloads }, NezzelFormat.count(count, language))
    @Composable fun DestinationIcon(destination: String) {
        when (destination) {
            "Home" -> Icon(Icons.Default.Home, null)
            // A generic dashed ring, never a messaging app's logo or colour (spec 0.5 §5.5).
            "Statuses" -> Icon(painterResource(R.drawable.ic_status_ring), null)
            "Settings" -> BadgedBox(badge = {
                if (updateAvailable) {
                    val description = stringResource(R.string.update_badge_description)
                    Badge(Modifier.semantics { contentDescription = description }, containerColor = MaterialTheme.colorScheme.primary)
                }
            }) { Icon(Icons.Default.Settings, null) }
            else -> {
                // M3 small badge on the icon's top-end corner (BadgedBox mirrors it in RTL): a dot, never a digit pill that
                // grows with font scale. Primary while downloads run, error after a failure; the item's label carries the count.
                BadgedBox(badge = {
                    if (count > 0) Badge(Modifier.offset(x = 4.dp, y = (-2).dp), containerColor = MaterialTheme.colorScheme.primary)
                    else if (failed) Badge(Modifier.offset(x = 4.dp, y = (-2).dp))
                }) { Icon(painterResource(R.drawable.ic_download), null) }
            }
        }
    }
    val destinations = listOf("Home" to R.string.home, "Downloads" to R.string.shortcut_downloads_short, "Statuses" to R.string.statuses_tab, "Settings" to R.string.settings)
    if (rail) {
        NavigationRail(modifier = Modifier.testTag("navigation_rail").fillMaxHeight().verticalScroll(rememberScrollState()),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) {
            destinations.forEach { (destination, text) ->
                NavigationRailItem(selected = route == destination, onClick = { onNavigate(destination) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { if (destination == "Downloads") contentDescription = label else if (destination == "Settings" && updateAvailable) contentDescription = settingsLabel },
                    icon = { DestinationIcon(destination) }, label = { NavLabel(stringResource(text)) }, alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    } else {
        NavigationBar(modifier = Modifier.testTag("navigation_bar"), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            destinations.forEach { (destination, text) ->
                NavigationBarItem(selected = route == destination, onClick = { onNavigate(destination) },
                    modifier = Modifier.semantics { if (destination == "Downloads") contentDescription = label else if (destination == "Settings" && updateAvailable) contentDescription = settingsLabel },
                    icon = { DestinationIcon(destination) }, label = { NavLabel(stringResource(text)) }, alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    }
}

/** Up to two centred lines at large text (the bar's min height lets it grow); only a third line would ellipsize. */
@Composable
private fun NavLabel(text: String) = Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)

/** Most rows Home stacks: one per transfer slot. */
internal const val HOME_STRIP_ROWS = 3

/**
 * Home's view of the queue: one row per running job (up to [HOME_STRIP_ROWS], in start order), or the next job in line
 * (a failure as its own row) when none runs; a count opens Downloads when more wait beyond the rows.
 */
@Composable
fun DownloadStrip(jobs: List<DownloadProgress>, language: String, onCancel: (String) -> Unit = {}, onRemove: (String) -> Unit = {},
                  onRetry: (String) -> Unit = {}, onPause: (String) -> Unit = {}, onResume: (String) -> Unit = {}, onClick: () -> Unit) {
    val active = jobs.filter { !it.terminal || it.awaitingSchedule }
    val failed = jobs.filter { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }
    val started = rememberStartOrder(jobs)
    val shown = rememberWithSavedMoment(jobs, visibleJobs(jobs, started))
    if (shown.isEmpty()) return
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    // Saved cards (in their moment) and running jobs each get a row; otherwise the first in line stands alone.
    val rows = shown.takeWhile { it.saved || it.running }.take(HOME_STRIP_ROWS).ifEmpty { shown.take(1) }
    // With a count action the 48dp header row carries its own air (4dp to the row); a bare heading is natural height + 8dp.
    val counted = shown.size > rows.size
    Column(Modifier.fillMaxWidth().animateContentSize(easeOut(reduced)), verticalArrangement = Arrangement.spacedBy(if (counted) Space.xs else Space.s)) {
        val first = rows.first()
        // The header names what the lead card is, so a failure alone never reads as progress.
        val header = when {
            first.saved && rows.none { it.running } -> stringResource(R.string.saved_files)
            first.phase == DownloadPhase.FAILED && !first.awaitingSchedule ->
                stringResource(R.string.downloads_stopped_header, NezzelFormat.count(failed.size, language))
            else -> stringResource(R.string.home_active_header)
        }
        Row(Modifier.fillMaxWidth().then(if (counted) Modifier.heightIn(min = 48.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Text(header, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (counted) TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (active.size > 1) context.resources.getQuantityString(R.plurals.downloads_running, active.size, NezzelFormat.count(active.size, language))
                    else stringResource(R.string.see_all))
            }
        }
        // Rows already there when Home opens are simply shown; only a job that starts later fades in.
        val opening = remember { rows.mapTo(HashSet()) { it.jobId } }
        Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
            rows.forEach { job ->
                // Keyed by job: a row keeps its state while others start or leave.
                key(job.jobId) {
                    val appear = remember { MutableTransitionState(job.jobId in opening) }.apply { targetState = true }
                    AnimatedVisibility(appear, enter = fadeIn(easeOut(reduced, Motion.FADE_IN, 50)) + expandVertically(easeOut(reduced)),
                        label = "home job") {
                        JobRow(job, { onCancel(job.jobId) }, { onRemove(job.jobId) }, { onRetry(job.jobId) },
                            onPause = { onPause(job.jobId) }, onResume = { onResume(job.jobId) })
                    }
                }
            }
        }
    }
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
    onPlaylistAction: (String, PlaylistBatchAction) -> Unit = { _, _ -> }, playlistBusy: Set<String> = emptySet(),
    warnings: List<CheckWarning> = emptyList(), onTryAgain: (String) -> Unit = {}, onDismissWarning: (String) -> Unit = {},
    renameRepository: SavedMediaRepository? = null,
    /** Null: the real offer (tests and captures pass their own). */
    oldDownloads: OldDownloadsOffer? = null,
    openSavedUri: String? = null, onSavedOpened: () -> Unit = {},
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
    val active = rememberWithSavedMoment(standalone, remember(standalone, started) { visibleJobs(standalone, started) })
    val reduced = LocalReducedMotion.current
    val batches = rememberVisibleBatches(playlists)
    val tabs = showDownloadsTabs(active, batches, warnings.size)
    var tab by rememberDownloadsTab(jobs, playlists, tabs)
    val current = if (tabs) tab else DownloadsTab.SAVED
    val runningState = rememberLazyListState()
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
    val filtered = remember(saved, effectiveFilter, query, sort, language) {
        savedLibraryItems(saved, effectiveFilter, query, sort, Locale.forLanguageTag(language))
    }
    // Changing the filter/search exits selection; hidden files never receive a bulk action.
    var selectedUris by remember(filterName, query) { mutableStateOf(emptySet<String>()) }
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
        // Only while Downloading has rows; when the last one leaves, the row folds away and the library stays.
        AnimatedVisibility(tabs && !selecting, enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + expandVertically(easeOut(reduced)),
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT)) + shrinkVertically(easeOut(reduced, Motion.TAB))) {
            DownloadsTabRow(current, downloadingCount(standalone, playlists), warnings.isNotEmpty(), language) { tab = it }
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
                if (active.isNotEmpty()) {
                    stickyHeader(key = "jobs-header") {
                        val batch = batchAction(active)
                        SectionHeader(stringResource(if (runningCount(jobs) == 0) R.string.downloads_stopped_header else R.string.downloads_running_header,
                            NezzelFormat.count(if(runningCount(jobs) == 0) active.size else runningCount(jobs), language)), runningCount(jobs) == 0,
                            batch?.let { { BatchButton(it, language, !batchBusy) { onBatch(it) } } })
                    }
                    // Severe heat holds downloads to one; say so once, above the rows that wait for it.
                    if (hot && active.any { it.waiting && !it.awaitingSchedule && !it.paused && !it.terminal })
                        item(key = "heat-note") { HeatNote(Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120))) }
                    items(moving, key = { "job-${it.jobId}" }) { job -> JobItem(job) }
                }
                batches.forEach { batch ->
                    item(key = "playlist-${batch.id}") {
                        PlaylistBatchCard(batch, language, { onPlaylistAction(batch.id, it) }, busy = batch.id in playlistBusy,
                            modifier = Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120)))
                    }
                    items(batchMemberRows(batch, jobs), key = { "playlist-job-${it.jobId}" }) { job -> JobItem(job) }
                }
                items(failed, key = { "job-${it.jobId}" }) { job -> JobItem(job) }
                if (waiting.isNotEmpty()) {
                    item(key = "waiting-line") {
                        WaitingLine(waiting.size, waitingOpen, language, Modifier.animateItem(fadeInSpec = easeOut(reduced), fadeOutSpec = easeOut(reduced, 120))) {
                            waitingOpen = !waitingOpen
                        }
                    }
                    if (waitingOpen) items(waiting, key = { "job-${it.jobId}" }) { job -> JobItem(job) }
                }
                // Result check: saved files that need a look (in memory only).
                if (warnings.isNotEmpty()) {
                    item(key = "warnings-header") { SectionHeader(stringResource(R.string.check_needs_look), error = true) }
                    items(warnings, key = { "warning-${it.jobId}" }) { warning ->
                        CheckWarningRow(warning, (libraryLoad as? LibraryLoadState.Ready)?.rows?.firstOrNull { it.uri == warning.uri },
                            { onTryAgain(warning.jobId) }, { onDismissWarning(warning.jobId) }, rowMotion())
                    }
                }
            } else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = listState, contentPadding = PaddingValues(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.item)) {
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
            LinearProgressIndicator(Modifier.fillMaxWidth())
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
            SheetAction(R.drawable.ic_ms_share, stringResource(R.string.share_file)) { act(onShare) }
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

private fun loadThumbnail(context: android.content.Context, item: SavedMedia): Bitmap? = dev.ahmed.savlino.ui.loadMediaThumbnail(context, item.uri,
    when (item.kind) { SavedMediaKind.VIDEO -> ThumbnailKind.VIDEO; SavedMediaKind.AUDIO -> ThumbnailKind.AUDIO; else -> ThumbnailKind.IMAGE })

@Composable
fun MediaThumbnail(item: SavedMedia, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        // Audio shows its embedded cover; the music icon stays only when the file has none.
        if(item.kind != null) value = withContext(Dispatchers.IO) { loadThumbnail(context, item) }
    }
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
