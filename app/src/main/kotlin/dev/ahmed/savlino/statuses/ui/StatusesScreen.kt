package dev.ahmed.savlino.statuses.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.ui.nav.Joined
import dev.ahmed.savlino.ui.nav.LocalTabActive
import dev.ahmed.savlino.ui.nav.ScrollOnTabReselect
import dev.ahmed.savlino.ui.nav.SharedAxisContent
import dev.ahmed.savlino.ui.nav.heldWhileLeaving
import dev.ahmed.savlino.ui.nav.rememberSharedAxis
import dev.ahmed.savlino.ui.nav.rememberTabResource
import dev.ahmed.savlino.ui.ThumbnailCache
import dev.ahmed.savlino.ui.rememberCachedThumbnail
import kotlinx.coroutines.flow.collectLatest
import dev.ahmed.savlino.R
import dev.ahmed.savlino.downloads.ScreenHeader
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusApp
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.statuses.StatusLibrary
import dev.ahmed.savlino.statuses.StatusSource
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.bleedGutter
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import dev.ahmed.savlino.viewer.MediaViewer
import dev.ahmed.savlino.viewer.ViewerIntents
import dev.ahmed.savlino.viewer.ViewerItem
import dev.ahmed.savlino.viewer.viewerStart

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
    val active = LocalTabActive.current
    LaunchedEffect(state, lifecycle, active) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Every resume rescans; a quick return to the tab keeps what it shows unless the scan is a minute old.
            var resumed = true
            snapshotFlow { active.value }.collectLatest { if (it && (resumed || state.stale())) { resumed = false; state.refresh() } }
        }
    }
    // Shown/hidden edges, not disposal: a visited tab stays composed while hidden. A rotation is no edge (the state remembers).
    LaunchedEffect(state, active) { snapshotFlow { active.value }.collect(state::setActive) }
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
                is StatusEvent.Batch -> snackbar.showSnackbar(listOfNotNull(
                    context.resources.getQuantityString(R.plurals.statuses_saved_n, event.saved, NezzelFormat.count(event.saved, language)),
                    event.notSaved.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.statuses_not_saved_n, it,
                        NezzelFormat.count(it, language)) },
                    context.getString(R.string.download_space_error).takeIf { event.noSpace },
                ).joinToString(context.getString(R.string.separator)))
                is StatusEvent.Gone -> snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.statuses_gone, event.count,
                    NezzelFormat.count(event.count, language)))
                StatusEvent.NoSpace -> snackbar.showSnackbar(context.getString(R.string.download_space_error))
                StatusEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.statuses_save_failed))
                StatusEvent.ReopenPicker -> openPicker()
            }
        }
    }
    BackHandler(state.selecting) { state.exitSelect() }
    // One account's grid sits a level below the account cards: the bar and the page move together.
    val opened = state.selectedAccount
    val pages = rememberSharedAxis(opened, backTo = null, back = if (!state.selecting && opened != null) state::closeAccount else null,
        "statuses_page")
    val accountsScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            val ready = state.access as? StatusAccess.Ready
            pages.SharedAxisContent(AccountDepth, joined = Joined.Bottom) { page ->
                if (state.selecting) SelectionBar(state, language)
                else if (ready != null && page != null) AccountBar(state, ready, page, media, language)
                // Decided by the page: the account cards' bar never shows the grid's Select, even while a back swipe peeks at it.
                else ScreenHeader(stringResource(R.string.statuses_tab)) { if (ready != null && ready.sources.size <= 1) SelectAction(state) }
            }
        },
        bottomBar = { if (state.selecting) SaveBar(state, language) },
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
                is StatusAccess.Ready -> PullToRefreshBox(state.pulling, state::pull, Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter) {
                    if (access.items.isEmpty()) EmptyStatuses(access.apps)
                    else pages.SharedAxisContent(AccountDepth, Modifier.fillMaxSize(), Joined.Top) { page ->
                        // Decided by the page, not live state, so a leaving page never turns into the next one.
                        if (page == null && access.sources.size > 1) AccountList(state, access, media, language, accountsScroll)
                        else StatusGrid(state, media, language, heldWhileLeaving(state.visible))
                    }
                }
            }
        }
    }
    state.preview?.let { opened -> StatusViewer(state, opened, media, language) }
}

private val AccountDepth: (StatusSource?) -> Int = { if (it == null) 0 else 1 }

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
    val scroll = rememberScrollState()
    ScrollOnTabReselect { scroll.scrollTo(0) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight().verticalScroll(scroll)
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

/** First visit and lost access: what the tab does, the apps it found, one button and the one system tap that matters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Explainer(apps: List<StatusApp>, media: StatusMedia, onGrant: (() -> Unit)?, error: String? = null) = CenteredColumn {
    Spacer(Modifier.height(Space.l))
    RingMark()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.statuses_intro_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center)
        Text(stringResource(R.string.statuses_intro_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (apps.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Space.s)) { apps.forEach { AppRow(it, media) } }
    if (error != null) Text(error, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    if (onGrant != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.statuses_grant)) }
        Text(stringResource(R.string.statuses_grant_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRow(app: StatusApp, media: StatusMedia) {
    Row(Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        AppIcon(app.packageName, media, 28.dp)
        // The label is PackageManager's, in whatever script the app uses.
        Text(app.label, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
    }
}

@Composable
private fun StatusGrid(state: StatusesState, media: StatusMedia, language: String, items: List<StatusItem>) {
    val scroll = rememberLazyGridState()
    ScrollOnTabReselect { scroll.scrollToItem(0) }
    // Columns follow the text size: three at 100%, two from about 130%, so Save stays whole in every language.
    val columns = 104.dp * (1f + (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f) * .6f)
    LazyVerticalGrid(GridCells.Adaptive(columns), Modifier.widthIn(max = 720.dp).fillMaxSize(), state = scroll,
        contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (state.showFilters || state.showHideSaved) item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            // One row that scrolls under the grid gutter instead of wrapping (Arabic, large text), like Downloads.
            Row(Modifier.bleedGutter().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(bottom = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (state.showFilters) StatusFilter.entries.forEach { filter ->
                    Chip(state.filter == filter, stringResource(when (filter) {
                        StatusFilter.ALL -> R.string.statuses_filter_all
                        StatusFilter.PHOTOS -> R.string.statuses_filter_photos
                        StatusFilter.VIDEOS -> R.string.statuses_filter_videos
                    })) { state.filter = filter }
                }
                // A toggle, not a fourth filter: set apart, and ticked while on.
                if (state.showFilters && state.showHideSaved) VerticalDivider(Modifier.height(24.dp))
                if (state.showHideSaved) Chip(state.hideSaved, stringResource(R.string.statuses_hide_saved), check = true) { state.hideSaved = !state.hideSaved }
            }
        }
        state.newCount.takeIf { it > 0 && !state.selecting && state.selectedAccount == null }?.let { count ->
            item(key = "new", span = { GridItemSpan(maxLineSpan) }) {
                Text(pluralStringResource(R.plurals.statuses_new_n, count, NezzelFormat.count(count, language)),
                    Modifier.padding(vertical = Space.xs), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        items(items, key = { it.uri.toString() }, contentType = { "status" }) { item ->
            StatusTile(item, media, state.selecting, item.uri in state.selection, item.uri in state.pending,
                onClick = { if (state.selecting) state.toggle(item) else state.openPreview(item) }, onLongClick = { state.toggle(item) },
                onSave = { state.save(listOf(item)) })
        }
    }
}

/** "Account 2" when the app has several account folders or this is not its first, else null: the app's own label names it. */
@Composable
private fun accountNumber(source: StatusSource, access: StatusAccess.Ready, language: String): String? =
    if (source.accountIndex != 1 || access.sources.count { it.packageName == source.packageName } > 1)
        stringResource(R.string.statuses_account_n, NezzelFormat.count(source.accountIndex, language)) else null

/** PackageManager's label, in whatever script the app uses; the account number stands in when the app is unknown. */
@Composable
private fun appLabel(source: StatusSource, access: StatusAccess.Ready, language: String): String =
    access.apps.firstOrNull { it.packageName == source.packageName }?.label
        ?: stringResource(R.string.statuses_account_n, NezzelFormat.count(source.accountIndex, language))

@Composable
private fun AppIcon(packageName: String, media: StatusMedia, size: Dp) {
    val icon by rememberTabResource<Bitmap?>(null, packageName) { media.appIcon(packageName) }
    val loaded = icon
    if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.size(size))
    else Surface(Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {}
}

/** The tab's first screen with several accounts: one open section each (icon, counts, newest few statuses); space alone separates them. */
@Composable
private fun AccountList(state: StatusesState, access: StatusAccess.Ready, media: StatusMedia, language: String, scroll: LazyListState) {
    ScrollOnTabReselect { scroll.scrollToItem(0) }
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = Space.l)) {
        items(state.accounts, key = { "${it.source.packageName}/${it.source.accountIndex}" }, contentType = { "account" }) { summary ->
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
    val thumb = rememberCachedThumbnail(ThumbnailCache.key(item.uri, 240, item.sizeBytes, item.lastModifiedMillis)) { media.thumbnail(item, 240) }
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
    // The dot stays with the app name when the title wraps.
    val title = listOfNotNull(appLabel(source, access, language), accountNumber(source, access, language))
        .joinToString(stringResource(R.string.separator).replaceFirst(' ', '\u00A0'))
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
            // Balanced lines when the name wraps, never a lone account number on the second line.
            Text(title, style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        SelectAction(state)
    }
}

/** The visible way into select mode; long press on a tile is the shortcut. Only while something can still be saved. */
@Composable
private fun SelectAction(state: StatusesState) {
    if (state.canSelect) TextButton(onClick = state::enterSelect, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.statuses_select))
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, check: Boolean = false, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) },
        leadingIcon = if (check && selected) { { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null, modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp), shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer))
}

@Composable
private fun rememberDuration(item: StatusItem, media: StatusMedia): Long? =
    rememberTabResource<Long?>(null, item.uri) { if (item.isVideo) media.durationMillis(item) else null }.value

/** "Video, 0:15, saved": kind, duration when known, then the marks. */
@Composable
private fun describe(item: StatusItem, duration: Long?): String = listOfNotNull(
    stringResource(if (item.isVideo) R.string.statuses_video else R.string.statuses_photo),
    duration?.let { NezzelFormat.isolate(NezzelFormat.duration(it / 1000)) },
    stringResource(R.string.statuses_saved).takeIf { item.saved },
    stringResource(R.string.statuses_new).takeIf { item.isNew },
).joinToString(stringResource(R.string.separator))

/**
 * One status: the picture previews on tap (picks in select mode) and Save is its own touch target on the picture's
 * corner, so saving needs no preview. Save, then Saving, then Saved.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusTile(item: StatusItem, media: StatusMedia, selecting: Boolean, selected: Boolean, saving: Boolean,
                       onClick: () -> Unit, onLongClick: () -> Unit, onSave: () -> Unit) {
    val thumb = rememberCachedThumbnail(ThumbnailCache.key(item.uri, 320, item.sizeBytes, item.lastModifiedMillis)) { media.thumbnail(item, 320) }
    val duration = rememberDuration(item, media)
    val description = describe(item, duration)
    val shape = MaterialTheme.shapes.small
    val primary = MaterialTheme.colorScheme.primary
    val pickable = !item.saved && !saving
    Box(Modifier.aspectRatio(9f / 14f)) {
        Box(Modifier.fillMaxSize().clip(shape)
            .then(if (selected) Modifier.border(3.dp, primary, shape) else Modifier)
            .combinedClickable(onClickLabel = stringResource(if (selecting) R.string.statuses_select else R.string.statuses_preview),
                onLongClickLabel = stringResource(R.string.statuses_select).takeIf { pickable },
                onLongClick = onLongClick.takeIf { pickable }, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (selecting && pickable) this.selected = selected
            }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.secondaryContainer) {
                val loaded = thumb
                if (loaded != null) Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(contentAlignment = Alignment.Center) {
                    if (item.isVideo) Icon(Icons.Default.PlayArrow, null) else Icon(painterResource(R.drawable.ic_image), null)
                }
            }
            // Marks: new at top-start, the selection circle at top-end, duration at bottom-start.
            if (item.isNew && !selecting) Box(Modifier.align(Alignment.TopStart).padding(Space.s).size(12.dp).clip(CircleShape)
                .background(Color.White).padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
            if (selecting && pickable) SelectCircle(selected, Modifier.align(Alignment.TopEnd).padding(Space.xs))
            duration?.let {
                TileLabel(Modifier.align(Alignment.BottomStart)) {
                    Text(NezzelFormat.duration(it / 1000), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr))
                }
            }
            // A saved mark on the picture (an icon and a word, never a check circle, so it can't read as selected).
            if (item.saved) TileLabel(Modifier.align(Alignment.BottomEnd).testTag("status-saved")) {
                Icon(painterResource(R.drawable.ic_download_done), null, Modifier.size(12.dp))
                Text(stringResource(R.string.statuses_saved), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        if (!item.saved) CornerSave(saving, onSave, Modifier.align(Alignment.BottomEnd))
    }
}

/** Save: a round button on the picture's bottom-end corner, 48dp to touch. */
@Composable
private fun CornerSave(saving: Boolean, onSave: () -> Unit, modifier: Modifier) {
    val label = stringResource(if (saving) R.string.statuses_saving else R.string.statuses_save)
    // The ripple stays round like the button it surrounds.
    Box(modifier.size(48.dp).clip(CircleShape).clickable(enabled = !saving, role = Role.Button, onClick = onSave)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                else Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp))
            }
        }
    }
}

/** The tile's dark corner label: duration, or the saved mark. Silent: the tile's description already says both. */
@Composable
private fun TileLabel(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier.padding(Space.xs).clearAndSetSemantics { }, color = Color.Black.copy(alpha = .72f), contentColor = Color.White, shape = MaterialTheme.shapes.extraSmall) {
        Row(Modifier.padding(horizontal = Space.xs), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
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
private fun SelectionBar(state: StatusesState, language: String) {
    // Picked ones that are copying now still count as picked.
    val count = state.selection.size
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::exitSelect, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, stringResource(R.string.saved_selection_close)) }
            Text(pluralStringResource(R.plurals.saved_selected, count, NezzelFormat.count(count, language)),
                Modifier.weight(1f).padding(vertical = Space.s).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Select all covers what is shown here: this account and filter, not saved yet.
            if (state.allSelected) TextButton(onClick = state::clearSelection, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.statuses_clear_selection))
            } else if (state.canSelect) TextButton(onClick = state::selectAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.statuses_select_all))
            }
        }
    }
}

/** Save N, with the real count done while copies run. */
@Composable
private fun SaveBar(state: StatusesState, language: String) {
    val count = state.selectedItems.size
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val (done, total) = state.progress
            if (state.saving && total > 0) {
                Text(stringResource(R.string.statuses_saving_progress, NezzelFormat.count((done + 1).coerceAtMost(total), language),
                    NezzelFormat.count(total, language)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth())
            }
            // While copies run, the bar is their progress; Save N returns once more statuses are picked.
            if (!state.saving || count > 0) Button(onClick = { state.save(state.selectedItems) }, enabled = count > 0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when {
                    count > 0 -> pluralStringResource(R.plurals.statuses_save_n, count, NezzelFormat.count(count, language))
                    !state.canSelect && !state.saving -> stringResource(R.string.statuses_all_saved)
                    else -> stringResource(R.string.statuses_save)
                })
            }
        }
    }
}

/** The shared swipe viewer over the open account's current filter; Save sits in its top bar, and the viewer stays on the page after it. */
@Composable
private fun StatusViewer(state: StatusesState, opened: StatusItem, media: StatusMedia, language: String) {
    val context = LocalContext.current
    val items = state.previewItems
    val pages = remember(items) { items.map { ViewerItem(it.uri, it.isVideo, it.mimeType) } }
    val start = remember(opened.uri) { viewerStart(pages, opened.uri) }
    val pictures = remember(media, items) {
        val byUri = items.associateBy { it.uri }
        MediaPictures { uri, _, size -> byUri[uri]?.let { media.thumbnail(it, size) } }
    }
    fun launch(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
    CompositionLocalProvider(LocalMediaPictures provides pictures) {
        MediaViewer(pages, start, language, onClose = state::closePreview,
            onShare = { launch(ViewerIntents.share(it)) }, onOpenWith = { launch(ViewerIntents.openWith(it)) }) { page ->
            val item = items.firstOrNull { it.uri == page.uri } ?: return@MediaViewer
            val saving = item.uri in state.pending
            if (item.saved) Row(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.s).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primaryContainer)
                Text(stringResource(R.string.statuses_saved), style = MaterialTheme.typography.labelLarge)
            } else TextButton(onClick = { state.save(listOf(item)) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .8f))) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(Space.xs))
                }
                Text(stringResource(if (saving) R.string.statuses_saving else R.string.statuses_save))
            }
        }
    }
}
