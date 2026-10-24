package dev.ahmed.savlino.statuses.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.downloads.ScreenHeader
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusApp
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.statuses.StatusLibrary
import dev.ahmed.savlino.statuses.StatusSource
import dev.ahmed.savlino.ui.theme.Space

@Composable
fun rememberStatusesState(library: StatusLibrary?): StatusesState {
    val scope = rememberCoroutineScope()
    return remember(library) { StatusesState(library, scope) }
}

/**
 * The Statuses tab: explainer and one folder grant, then a grid of statuses the person already opened in WhatsApp.
 * Reloads on every resume (statuses expire after about a day); saving is always an explicit tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusesScreen(library: StatusLibrary?, language: String, state: StatusesState = rememberStatusesState(library)) {
    val context = LocalContext.current
    val media = LocalStatusMedia.current ?: remember(context) { SystemStatusMedia(context) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { state.picked(it.data?.data) }
    val openPicker: () -> Unit = {
        library?.let { try { picker.launch(it.grantIntent()) } catch (_: ActivityNotFoundException) { } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.refresh() } }
    LaunchedEffect(state) {
        state.events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            when (event) {
                is StatusEvent.Saved -> {
                    val result = snackbar.showSnackbar(context.getString(R.string.statuses_saved), context.getString(R.string.open_file),
                        duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) try {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(event.uri, if (event.isVideo) "video/*" else "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: ActivityNotFoundException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                    catch (_: SecurityException) { snackbar.showSnackbar(context.getString(R.string.saved_action_failed)) }
                }
                is StatusEvent.Gone -> snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.statuses_gone, event.count,
                    NezzelFormat.count(event.count, language)))
                StatusEvent.NoSpace -> snackbar.showSnackbar(context.getString(R.string.download_space_error))
                StatusEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.statuses_save_failed))
                StatusEvent.ReopenPicker -> openPicker()
            }
        }
    }
    BackHandler(state.selecting) { state.clearSelection() }
    BackHandler(!state.selecting && state.selectedAccount != null) { state.closeAccount() }
    Scaffold(
        topBar = {
            val ready = state.access as? StatusAccess.Ready
            val opened = state.selectedAccount
            if (state.selecting) SelectionBar(state.selection.size, language, state::clearSelection)
            else if (ready != null && opened != null) AccountBar(state, ready, opened, media, language)
            else ScreenHeader(stringResource(R.string.statuses_tab))
        },
        bottomBar = { if (state.selecting) SaveBar(state.selection.size, language, state.saving) { state.save(state.selectedItems) } },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val access = state.access) {
                null -> if (!state.available) Explainer(emptyList(), media, onGrant = null)
                StatusAccess.NotInstalled -> Message(stringResource(R.string.statuses_not_installed))
                is StatusAccess.NeedsGrant -> Explainer(access.apps, media, onGrant = openPicker,
                    error = if (state.wrongFolder) stringResource(R.string.statuses_wrong_folder) else null)
                is StatusAccess.AccessLost -> Explainer(access.apps, media, onGrant = openPicker,
                    error = stringResource(if (state.wrongFolder) R.string.statuses_wrong_folder else R.string.statuses_access_lost))
                is StatusAccess.Ready -> if (access.items.isEmpty()) EmptyStatuses(access.apps)
                    else if (state.showAccountList) AccountList(state, access, media, language)
                    else StatusGrid(state, media, language)
            }
        }
    }
    state.preview?.let { item -> StatusPreview(item, media, state.saving, onClose = { state.preview = null }) { state.save(listOf(item)) } }
}

/** Dashed-ring mark of the tab, larger: the same generic glyph, never a messaging app's logo. */
@Composable
private fun RingMark() {
    Surface(Modifier.size(80.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_status_ring), null, Modifier.size(44.dp)) }
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = Space.gutter, vertical = Space.l), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l), content = content)
}

@Composable
private fun Message(text: String, action: (@Composable () -> Unit)? = null) = CenteredColumn {
    Spacer(Modifier.height(Space.xl))
    RingMark()
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    action?.invoke()
}

@Composable
private fun EmptyStatuses(apps: List<StatusApp>) {
    val context = LocalContext.current
    Message(stringResource(R.string.statuses_empty)) {
        apps.firstOrNull()?.let { app ->
            OutlinedButton(onClick = {
                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    try { context.startActivity(it) } catch (_: ActivityNotFoundException) { }
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.statuses_open_whatsapp)) }
        }
    }
}

/** First visit and lost access: what the tab does, which apps it found, one button, and the two system taps that follow. */
@Composable
private fun Explainer(apps: List<StatusApp>, media: StatusMedia, onGrant: (() -> Unit)?, error: String? = null) = CenteredColumn {
    RingMark()
    Text(stringResource(R.string.statuses_intro_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Step(1, stringResource(R.string.statuses_step_1))
        Step(2, stringResource(R.string.statuses_step_2))
    }
    if (apps.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = Space.xs)) { apps.forEach { AppRow(it, media) } }
    }
    if (error != null) Text(error, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (onGrant != null) {
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.statuses_grant)) }
        SystemSteps()
    }
    Text(stringResource(R.string.statuses_privacy), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        NumberBadge(number)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NumberBadge(number: Int) {
    Surface(Modifier.size(28.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Box(contentAlignment = Alignment.Center) {
            // Step numbers stay Latin like the rest of the app's digits.
            Text(number.toString(), style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
        }
    }
}

@Composable
private fun AppRow(app: StatusApp, media: StatusMedia) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.l).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        AppIcon(app.packageName, media, 36.dp)
        // The label is PackageManager's, in whatever script the app uses.
        Text(app.label, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
    }
}

/** A drawn hint of the system picker's two buttons, numbered; TalkBack reads the sentence instead. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemSteps() {
    val hint = stringResource(R.string.statuses_grant_hint)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = hint }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.statuses_next_screen), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            FakeSystemButton(1, stringResource(R.string.statuses_use_folder))
            FakeSystemButton(2, stringResource(R.string.statuses_allow))
        }
    }
}

@Composable
private fun FakeSystemButton(number: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        NumberBadge(number)
        Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
            Text(label, Modifier.padding(horizontal = Space.l, vertical = Space.s), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusGrid(state: StatusesState, media: StatusMedia, language: String) {
    val items = state.visible
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (state.showFilters || state.showHideSaved) item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (state.showFilters) StatusFilter.entries.forEach { filter ->
                    Chip(state.filter == filter, stringResource(when (filter) {
                        StatusFilter.ALL -> R.string.statuses_filter_all
                        StatusFilter.PHOTOS -> R.string.statuses_filter_photos
                        StatusFilter.VIDEOS -> R.string.statuses_filter_videos
                    })) { state.filter = filter }
                }
                if (state.showHideSaved) Chip(state.hideSaved, stringResource(R.string.statuses_hide_saved)) { state.hideSaved = !state.hideSaved }
            }
        }
        state.newCount.takeIf { it > 0 && !state.selecting && state.selectedAccount == null }?.let { count ->
            item(key = "new", span = { GridItemSpan(maxLineSpan) }) {
                Text(pluralStringResource(R.plurals.statuses_new_n, count, NezzelFormat.count(count, language)),
                    Modifier.padding(vertical = Space.xs), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        items(items, key = { it.uri.toString() }) { item ->
            StatusTile(item, media, state.selecting, item.uri in state.selection,
                onClick = { if (state.selecting) state.toggle(item) else state.preview = item }, onLongClick = { state.toggle(item) })
        }
    }
}

/** "Account 2" when the app has several account folders, else null: the app's own label already names it. */
@Composable
private fun accountNumber(source: StatusSource, access: StatusAccess.Ready, language: String): String? =
    if (access.sources.count { it.packageName == source.packageName } > 1)
        stringResource(R.string.statuses_account_n, NezzelFormat.count(source.accountIndex, language)) else null

/** PackageManager's label, in whatever script the app uses; the account number stands in when the app is unknown. */
@Composable
private fun appLabel(source: StatusSource, access: StatusAccess.Ready, language: String): String =
    access.apps.firstOrNull { it.packageName == source.packageName }?.label
        ?: stringResource(R.string.statuses_account_n, NezzelFormat.count(source.accountIndex, language))

@Composable
private fun AppIcon(packageName: String, media: StatusMedia, size: Dp) {
    val icon by produceState<Bitmap?>(null, packageName) { value = media.appIcon(packageName) }
    val loaded = icon
    if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.size(size))
    else Surface(Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {}
}

/** The tab's first screen with several accounts: one open section each (icon, counts, newest few statuses); space alone separates them. */
@Composable
private fun AccountList(state: StatusesState, access: StatusAccess.Ready, media: StatusMedia, language: String) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        items(state.accounts, key = { "${it.source.packageName}/${it.source.accountIndex}" }) { summary ->
            AccountSection(summary, access, media, language) { state.open(summary.source) }
        }
    }
}

@Composable
private fun AccountSection(summary: AccountSummary, access: StatusAccess.Ready, media: StatusMedia, language: String, onOpen: () -> Unit) {
    val title = appLabel(summary.source, access, language)
    val count = pluralStringResource(R.plurals.statuses_count, summary.total, NezzelFormat.count(summary.total, language))
    val subtitle = listOfNotNull(accountNumber(summary.source, access, language), count).joinToString(stringResource(R.string.separator))
    val newText = if (summary.newCount > 0)
        pluralStringResource(R.plurals.statuses_new_n, summary.newCount, NezzelFormat.count(summary.newCount, language)) else null
    val description = listOfNotNull(title, subtitle, newText).joinToString(stringResource(R.string.separator))
    // Flat: no card. The whole section is one full-width touch target and one TalkBack stop that reads as a sentence.
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.statuses_open), role = Role.Button, onClick = onOpen)
        .clearAndSetSemantics { contentDescription = description }.padding(horizontal = Space.gutter, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            AppIcon(summary.source.packageName, media, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            newText?.let {
                Text(it, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val more = summary.total - (PREVIEW_COUNT - 1)
            repeat(PREVIEW_COUNT) { index ->
                val item = summary.preview.getOrNull(index)
                Box(Modifier.weight(1f).aspectRatio(1f)) {
                    if (item != null) PreviewThumb(item, media, if (index == PREVIEW_COUNT - 1 && more > 1) more else null, language)
                }
            }
        }
    }
}

/** A small square of the newest statuses; the last one carries "+N" when more wait inside. */
@Composable
private fun PreviewThumb(item: StatusItem, media: StatusMedia, more: Int?, language: String) {
    val thumb by produceState<Bitmap?>(null, item.uri) { value = media.thumbnail(item, 240) }
    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = thumb
            if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (item.isVideo) Icon(Icons.Default.PlayArrow, null) else Icon(painterResource(R.drawable.ic_image), null)
            if (more != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.statuses_more_n, NezzelFormat.count(more, language)), color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Ltr))
            } else if (item.isVideo && loaded != null) Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.BottomStart)
                .padding(Space.xs).size(18.dp), tint = Color.White)
        }
    }
}

/** Inside one account: back to the cards, the app's icon and name, and how many statuses (and new ones) it holds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBar(state: StatusesState, access: StatusAccess.Ready, source: StatusSource, media: StatusMedia, language: String) {
    val title = listOfNotNull(appLabel(source, access, language), accountNumber(source, access, language))
        .joinToString(stringResource(R.string.separator))
    val summary = state.accounts.firstOrNull { it.source == source }
    val subtitle = summary?.let { listOfNotNull(
        pluralStringResource(R.plurals.statuses_count, it.total, NezzelFormat.count(it.total, language)),
        if (it.newCount > 0) pluralStringResource(R.plurals.statuses_new_n, it.newCount, NezzelFormat.count(it.newCount, language)) else null,
    ).joinToString(stringResource(R.string.separator)) }
    Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        IconButton(onClick = state::closeAccount, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        }
        AppIcon(source.packageName, media, 36.dp)
        Column(Modifier.weight(1f).padding(vertical = Space.s).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) }, modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp), shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer))
}

@Composable
private fun rememberDuration(item: StatusItem, media: StatusMedia): Long? =
    produceState<Long?>(null, item.uri) { if (item.isVideo) value = media.durationMillis(item) }.value

/** "Video, 0:15, saved": kind, duration when known, then the marks. */
@Composable
private fun describe(item: StatusItem, duration: Long?): String = listOfNotNull(
    stringResource(if (item.isVideo) R.string.statuses_video else R.string.statuses_photo),
    duration?.let { NezzelFormat.isolate(NezzelFormat.duration(it / 1000)) },
    stringResource(R.string.statuses_saved).takeIf { item.saved },
    stringResource(R.string.statuses_new).takeIf { item.isNew },
).joinToString(stringResource(R.string.separator))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusTile(item: StatusItem, media: StatusMedia, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.uri) { value = media.thumbnail(item, 320) }
    val duration = rememberDuration(item, media)
    val description = describe(item, duration)
    val shape = MaterialTheme.shapes.small
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.aspectRatio(9f / 14f).clip(shape)
        .then(if (selected) Modifier.border(3.dp, primary, shape) else Modifier)
        .combinedClickable(onClickLabel = stringResource(if (selecting) R.string.statuses_select else R.string.statuses_save),
            onLongClickLabel = stringResource(R.string.statuses_select), onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = description
            if (selecting) this.selected = selected
        }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.secondaryContainer) {
            val loaded = thumb
            if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(contentAlignment = Alignment.Center) {
                if (item.isVideo) Icon(Icons.Default.PlayArrow, null) else Icon(painterResource(R.drawable.ic_image), null)
            }
        }
        // Marks: new at top-start, saved or the selection circle at top-end, duration at bottom-start (mockup).
        if (item.isNew && !selecting) Box(Modifier.align(Alignment.TopStart).padding(Space.s).size(12.dp).clip(CircleShape)
            .background(Color.White).padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
        if (selecting) SelectCircle(selected, Modifier.align(Alignment.TopEnd).padding(Space.xs))
        else if (item.saved) Surface(Modifier.align(Alignment.TopEnd).padding(Space.xs).size(22.dp), shape = CircleShape,
            color = primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Icon(Icons.Default.Check, null, Modifier.padding(3.dp))
        }
        duration?.let {
            Surface(Modifier.align(Alignment.BottomStart).padding(Space.xs), color = Color.Black.copy(alpha = .72f), contentColor = Color.White,
                shape = MaterialTheme.shapes.extraSmall) {
                Text(NezzelFormat.duration(it / 1000), Modifier.padding(horizontal = Space.xs),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
}

@Composable
private fun SelectCircle(selected: Boolean, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(modifier.size(24.dp), shape = CircleShape, color = if (selected) primary else Color.Black.copy(alpha = .25f),
        contentColor = MaterialTheme.colorScheme.onPrimary, border = BorderStroke(2.dp, if (selected) primary else Color.White)) {
        if (selected) Icon(Icons.Default.Check, null, Modifier.padding(3.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, language: String, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.saved_selection_close)) }
            Text(pluralStringResource(R.plurals.saved_selected, count, NezzelFormat.count(count, language)),
                Modifier.weight(1f).padding(vertical = Space.s).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SaveBar(count: Int, language: String, saving: Boolean, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        SaveButton(pluralStringResource(R.plurals.statuses_save_n, count, NezzelFormat.count(count, language)), saving, onSave,
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s))
    }
}

@Composable
private fun SaveButton(label: String, saving: Boolean, onSave: () -> Unit, modifier: Modifier) {
    Button(onClick = onSave, enabled = !saving, modifier = modifier.heightIn(min = 48.dp)) {
        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        else Text(label)
    }
}

/** Full-screen look at one status before saving it. Videos play in place; nothing is copied until Save. */
@Composable
private fun StatusPreview(item: StatusItem, media: StatusMedia, saving: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.statuses_preview_close)) }
                    Spacer(Modifier.weight(1f))
                    rememberDuration(item, media)?.let {
                        Text(NezzelFormat.duration(it / 1000), Modifier.padding(horizontal = Space.l),
                            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { PreviewMedia(item, media) }
                if (item.saved) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.gutter).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primaryContainer)
                    Text(stringResource(R.string.statuses_saved), style = MaterialTheme.typography.labelLarge)
                }
                SaveButton(stringResource(R.string.statuses_save), saving, onSave,
                    Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l))
            }
        }
    }
}

@Composable
private fun PreviewMedia(item: StatusItem, media: StatusMedia) {
    val poster by produceState<Bitmap?>(null, item.uri) { value = media.thumbnail(item, 1080) }
    var playing by remember(item.uri) { mutableStateOf(false) }
    val description = stringResource(if (item.isVideo) R.string.statuses_video else R.string.statuses_photo)
    if (item.isVideo) AndroidView({ context ->
        VideoView(context).apply {
            setOnPreparedListener { player -> player.isLooping = true; start(); playing = true }
            setOnErrorListener { _, _, _ -> playing = false; true }
            setVideoURI(item.uri)
            setOnClickListener { if (isPlaying) pause() else start() }
        }
    }, Modifier.semantics { contentDescription = description }, onRelease = { it.stopPlayback() })
    if (!playing) poster?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}
