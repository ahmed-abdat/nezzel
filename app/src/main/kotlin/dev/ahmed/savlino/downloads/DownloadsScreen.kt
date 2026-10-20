package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.isLargeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun visibleJobs(jobs: List<DownloadProgress>) = jobs.filter { !it.terminal || it.awaitingSchedule || it.phase == DownloadPhase.FAILED }.asReversed()
fun runningCount(jobs: List<DownloadProgress>) = jobs.count { !it.terminal || it.awaitingSchedule }

@Composable
fun DownloadNavigation(route: String, jobs: List<DownloadProgress>, language: String, onNavigate: (String) -> Unit) {
    val rail = LocalConfiguration.current.screenHeightDp < 480
    val count = runningCount(jobs)
    val failed = jobs.any { it.phase == DownloadPhase.FAILED }
    val label = stringResource(when { count > 0 -> R.string.downloads_running_accessibility; failed -> R.string.downloads_failed_accessibility; else -> R.string.downloads }, NezzelFormat.count(count, language))
    @Composable fun DestinationIcon(destination: String) {
        when (destination) {
            "Home" -> Icon(Icons.Default.Home, null)
            "Settings" -> Icon(Icons.Default.Settings, null)
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
    val destinations = listOf("Home" to R.string.home, "Downloads" to R.string.downloads, "Settings" to R.string.settings)
    if (rail) {
        NavigationRail(modifier = Modifier.testTag("navigation_rail").fillMaxHeight().verticalScroll(rememberScrollState()),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) {
            destinations.forEach { (destination, text) ->
                NavigationRailItem(selected = route == destination, onClick = { onNavigate(destination) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { if (destination == "Downloads") contentDescription = label },
                    icon = { DestinationIcon(destination) }, label = { NavLabel(stringResource(text)) }, alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    } else {
        NavigationBar(modifier = Modifier.testTag("navigation_bar"), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            destinations.forEach { (destination, text) ->
                NavigationBarItem(selected = route == destination, onClick = { onNavigate(destination) },
                    modifier = Modifier.semantics { if (destination == "Downloads") contentDescription = label },
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

/** Busy jobs first (bytes moving, joining, converting, saving), then queued/waiting ones, each newest first; failures last. */
internal fun homeStripOrder(jobs: List<DownloadProgress>): List<DownloadProgress> {
    val active = jobs.filter { !it.terminal || it.awaitingSchedule }.asReversed()
    val failed = jobs.filter { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }.asReversed()
    return active.sortedBy { if (it.busy) 0 else 1 } + failed
}

private val BUSY_PHASES = setOf(DownloadPhase.DOWNLOADING, DownloadPhase.JOINING, DownloadPhase.CONVERTING, DownloadPhase.SAVING)
private val DownloadProgress.busy get() = !terminal && !awaitingSchedule && !waiting && phase in BUSY_PHASES

/** Home's view of the queue: the busy job as one row (a failure as its own row), a count when there are more. */
@Composable
fun DownloadStrip(jobs: List<DownloadProgress>, language: String, onCancel: (String) -> Unit = {}, onRemove: (String) -> Unit = {},
                  onRetry: (String) -> Unit = {}, onClick: () -> Unit) {
    val active = jobs.filter { !it.terminal || it.awaitingSchedule }
    val failed = jobs.filter { it.phase == DownloadPhase.FAILED && !it.awaitingSchedule }
    val shown = rememberWithSavedMoment(jobs, homeStripOrder(jobs))
    if (shown.isEmpty()) return
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    // With a count action the 48dp header row carries its own air (4dp to the row); a bare heading is natural height + 8dp.
    val counted = shown.size > 1
    Column(Modifier.fillMaxWidth().animateContentSize(easeOut(reduced)), verticalArrangement = Arrangement.spacedBy(if (counted) Space.xs else Space.s)) {
        val first = shown.first()
        // The header names what the lead card is, so a failure alone never reads as progress.
        val header = when {
            first.saved -> stringResource(R.string.saved_files)
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
        // A new lead job cross-fades in; the saved card fades out instead of vanishing when its moment ends.
        AnimatedContent(first, contentKey = { it.jobId }, transitionSpec = {
            fadeIn(easeOut(reduced, Motion.FADE_IN, 50)) togetherWith fadeOut(easeOut(reduced, Motion.FADE_OUT))
        }, label = "home job") { job ->
            JobRow(job, { onCancel(job.jobId) }, { onRemove(job.jobId) }, { onRetry(job.jobId) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    jobs: List<DownloadProgress>, language: String,
    onCancel: (String) -> Unit, onRemove: (String) -> Unit,
    onOpen: (SavedMedia) -> Unit, onShare: (SavedMedia) -> Unit, onOpenWith: (SavedMedia) -> Unit,
    onShowFiles: (() -> Unit)?, onDelete: (SavedMedia) -> Unit, onPaste: () -> Unit,
    onRetry: (String) -> Unit = {},
    libraryLoad: LibraryLoadState = LibraryLoadState.Ready(emptyList()), onLibraryRetry: () -> Unit = {},
    onShareSelected: (List<SavedMedia>) -> Unit = {},
    onDeleteSelected: (List<SavedMedia>, (cleared: Boolean) -> Unit) -> Unit = { _, done -> done(true) },
) {
    var filterName by rememberSaveable { mutableStateOf(MediaFilter.ALL.name) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var deletingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = libraryLoad.lastSuccessfulRows.orEmpty()
    val deleting = saved.find { it.uri.toString() == deletingUri }
    val active = rememberWithSavedMoment(jobs, remember(jobs) { visibleJobs(jobs) })
    val reduced = LocalReducedMotion.current
    val filtered = remember(saved, filterName, query) { filterMedia(saved, MediaFilter.valueOf(filterName), query) }
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
    val groups = remember(filtered) { filtered.groupBy { it.date } }
    val today = LocalDate.now()
    val closeSearch = { search = false; query = "" }
    BackHandler(search, closeSearch)
    BackHandler(selecting) { clearSelection() }
    // Results replace the list in place; start them at the top so a filtered list never opens mid-scroll.
    LaunchedEffect(query) { if (search && query.isNotEmpty()) listState.scrollToItem(0) }
    Scaffold(topBar = { Column {
        if (selecting) SelectionHeader(selected.size, language, selectionBusy, clearSelection,
            { selectedUris = filtered.map { it.uri.toString() }.toSet() }, { onShareSelected(selected) }, {
                if (Build.VERSION.SDK_INT >= 30) deleteSelected(selected) else deleteSelection = selected.toList()
            })
        else ScreenHeader(stringResource(R.string.downloads)) {
            // One toggle in the same spot: Search opens the field under the bar, Close search shuts and clears it.
            if (search) IconButton(onClick = closeSearch) { Icon(Icons.Default.Close, stringResource(R.string.saved_search_close)) }
            else if (saved.isNotEmpty()) IconButton(onClick = { search = true }) { Icon(Icons.Default.Search, stringResource(R.string.saved_search)) }
        }
        // Pinned under the app bar, outside the list: always in view, and free to grow at 200% text.
        AnimatedVisibility(search && saved.isNotEmpty(), enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + expandVertically(easeOut(reduced)),
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT)) + shrinkVertically(easeOut(reduced, Motion.FADE_OUT))) {
            SearchField(query, { query = it }, closeSearch)
        }
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = listState, contentPadding = PaddingValues(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.item)) {
                if(active.isNotEmpty()) {
                    stickyHeader(key = "jobs-header") {
                        SectionHeader(stringResource(if (runningCount(jobs) == 0) R.string.downloads_stopped_header else R.string.downloads_running_header,
                            NezzelFormat.count(if(runningCount(jobs) == 0) active.size else runningCount(jobs), language)), runningCount(jobs) == 0)
                    }
                    items(active, key = { "job-${it.jobId}" }) { job ->
                        JobRow(job, { onCancel(job.jobId) }, { onRemove(job.jobId) }, { onRetry(job.jobId) },
                            Modifier.animateItem(fadeInSpec = easeOut(reduced), placementSpec = easeOut(reduced, 220), fadeOutSpec = easeOut(reduced, 120)))
                    }
                }
                if (libraryLoad !is LibraryLoadState.Ready) item(key = "library-status") { LibraryLoadStatus(libraryLoad, onLibraryRetry) }
                if (saved.isNotEmpty()) {
                    item(key = "filters") {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            MediaFilter.entries.forEach { filter ->
                                FilterChip(filterName == filter.name, { filterName = filter.name }, modifier = Modifier.heightIn(min = 40.dp),
                                    shape = CircleShape, colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    label = { Text(stringResource(when(filter) { MediaFilter.ALL -> R.string.filter_all; MediaFilter.VIDEO -> R.string.video_group; MediaFilter.AUDIO -> R.string.audio_group; MediaFilter.IMAGE -> R.string.filter_images })) })
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
                        stickyHeader(key = "date-$date") { SectionHeader(when(date) {
                            today -> stringResource(R.string.saved_today)
                            today.minusDays(1) -> stringResource(R.string.saved_yesterday)
                            else -> mediaDate(date, language)
                        }) }
                        items(entries, key = { it.uri.toString() }) { item -> SavedRow(item, language, { onOpen(item) }, { onShare(item) },
                            { onOpenWith(item) }, onShowFiles, { deletingUri = item.uri.toString() },
                            // No fade-in: filter switches must not flicker tens of rows; removal fades and neighbours slide.
                            Modifier.animateItem(fadeInSpec = null, placementSpec = easeOut(reduced, 220), fadeOutSpec = easeOut(reduced, 100)),
                            selecting = selecting, selected = item.uri.toString() in selectedUris, selectionEnabled = !selectionBusy,
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
                    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.padding(20.dp).size(40.dp))
                        }
                        Text(stringResource(R.string.saved_empty_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.saved_empty_body), Modifier.widthIn(max = 320.dp), textAlign = TextAlign.Center)
                        Button(onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.paste_link)) }
                    }
                }
            }
        }
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

/** The title can wrap at large text; all four actions retain their 48dp touch targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionHeader(count: Int, language: String, busy: Boolean, onClose: () -> Unit,
    onAll: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.saved_selection_close)) }
            Text(pluralStringResource(R.plurals.saved_selected, count, NezzelFormat.count(count, language)),
                Modifier.weight(1f).padding(vertical = Space.s).semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onAll, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.CheckCircle, stringResource(R.string.saved_select_all)) }
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

@Composable
private fun SectionHeader(text: String, error: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(top = Space.m, bottom = Space.xs).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
            color = if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SavedRow(item: SavedMedia, language: String, onOpen: () -> Unit, onShare: () -> Unit, onOpenWith: () -> Unit, onShowFiles: (() -> Unit)?, onDelete: () -> Unit, modifier: Modifier = Modifier,
    selecting: Boolean = false, selected: Boolean = false, selectionEnabled: Boolean = true,
    onSelect: () -> Unit = {}, onToggle: () -> Unit = {},
) {
    val selectLabel = stringResource(R.string.saved_select)
    val selectedState = stringResource(if (selected) R.string.saved_selected_state else R.string.saved_unselected_state)
    val openLabel = stringResource(R.string.open_file)
    val meta = mediaMeta(item, language, withFormat = true)
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
        else SavedMenu(item, onShare, onOpenWith, onShowFiles, onDelete)
    }
    @Composable fun Texts(modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
        Text(NezzelFormat.isolate(item.title), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun SavedMenu(item: SavedMedia, onShare: () -> Unit, onOpenWith: () -> Unit, onShowFiles: (() -> Unit)?, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.saved_more, item.title)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Share, null) }, text = { Text(stringResource(R.string.share_file)) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.PlayArrow, null) }, text = { Text(stringResource(R.string.saved_open_with)) }, onClick = { menu = false; onOpenWith() })
            if(onShowFiles != null) DropdownMenuItem(leadingIcon = { Icon(painterResource(R.drawable.ic_folder), null) }, text = { Text(stringResource(R.string.saved_show_files)) }, onClick = { menu = false; onShowFiles() })
            HorizontalDivider()
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, text = { Text(stringResource(R.string.saved_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
fun mediaMeta(item: SavedMedia, language: String, withFormat: Boolean = false): String = listOfNotNull(
    item.format.takeIf { withFormat && it.isNotBlank() },
    when (item.kind) {
        SavedMediaKind.IMAGE -> if (item.width != null && item.width > 0 && item.height != null && item.height > 0)
            stringResource(R.string.saved_image_dimensions, NezzelFormat.count(item.width, language), NezzelFormat.count(item.height, language)) else null
        SavedMediaKind.AUDIO -> item.bitrateKbps?.let { "${NezzelFormat.count(it, language)}K" }
        SavedMediaKind.VIDEO -> VideoQuality.of(item.width, item.height)
            ?.let { stringResource(R.string.video_quality, NezzelFormat.count(it, language)) }
        null -> null
    },
    item.size?.let { NezzelFormat.size(it, language) } ?: stringResource(R.string.saved_size_unknown)
).joinToString(stringResource(R.string.separator)).let(NezzelFormat::isolate)

/** MediaStore rows get system thumbnails; custom-folder documents often don't, so decode one frame ourselves. */
private fun loadThumbnail(context: android.content.Context, item: SavedMedia): Bitmap? {
    try { return context.contentResolver.loadThumbnail(item.uri, Size(320, 180), null) } catch (_: Exception) { }
    return try {
        if (item.kind == SavedMediaKind.VIDEO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                retriever.getScaledFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            } finally { retriever.release() }
        } else android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, info, _ ->
            val scale = maxOf(1, info.size.width / 320, info.size.height / 180)
            decoder.setTargetSize(maxOf(1, info.size.width / scale), maxOf(1, info.size.height / scale))
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}

@Composable
fun MediaThumbnail(item: SavedMedia, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        if(item.kind == SavedMediaKind.IMAGE || item.kind == SavedMediaKind.VIDEO)
            value = withContext(Dispatchers.IO) { loadThumbnail(context, item) }
    }
    Surface(modifier.clip(shape), color = if (item.kind == SavedMediaKind.AUDIO) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if(loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (item.kind == SavedMediaKind.VIDEO) Icon(Icons.Default.PlayArrow, null)
            else Icon(painterResource(when(item.kind) { SavedMediaKind.IMAGE -> R.drawable.ic_image; SavedMediaKind.AUDIO -> R.drawable.ic_music; else -> R.drawable.ic_folder }), null)
            item.durationMillis?.takeIf { item.kind != SavedMediaKind.IMAGE }?.let { duration ->
                Surface(Modifier.align(Alignment.BottomEnd).padding(Space.xs), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .72f),
                    contentColor = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.extraSmall) {
                    Text(NezzelFormat.duration(duration / 1000), Modifier.padding(horizontal = Space.xs), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
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
                                Text(NezzelFormat.isolate(item.title), Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis,
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
