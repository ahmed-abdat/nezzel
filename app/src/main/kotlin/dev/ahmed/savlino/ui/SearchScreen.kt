package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.downloads.LocalOffline
import dev.ahmed.savlino.engine.SearchFilter
import dev.ahmed.savlino.engine.SearchKind
import dev.ahmed.savlino.engine.SearchResult
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Dock
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * YouTube search results over Home: the field on top searches again, a row opens the ordinary options sheet.
 * Back returns to Home with the words in its field.
 */
@Composable
internal fun SearchScreen(links: LinkViewModel, thumbnail: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) },
                          snackbarHost: @Composable () -> Unit = {}) {
    val vm = links.search
    BackHandler { links.closeSearch() }
    Scaffold(Modifier.testTag("search_screen"), snackbarHost = snackbarHost,
        topBar = {
            Column {
                SearchField(vm, links::closeSearch)
                SearchFilters(vm)
            }
        }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            SearchContent(vm, LocalOffline.current, thumbnail, links::openSearchResult)
        }
    }
}

@Composable
private fun SearchField(vm: SearchViewModel, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val search = { focus.clearFocus(); keyboard?.hide(); vm.search() }
    val label = stringResource(R.string.search_field_label)
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.xs, end = Space.l, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        OutlinedTextField(value = vm.query, onValueChange = vm::edit,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = label }.testTag("search_field"),
            shape = Dock.SHAPE, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.edit("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.saved_search_clear))
                }
            },
            placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            // Arabic words read RTL, English LTR, whatever the app language.
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Dock.color, unfocusedContainerColor = Dock.color,
                focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Transparent))
    }
}

@Composable
private fun SearchFilters(vm: SearchViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(start = Space.l, end = Space.l, bottom = Space.s).selectableGroup().testTag("search_filters"),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        SearchFilter.entries.forEach { filter ->
            val selected = vm.filter == filter
            FilterChip(selected, { vm.choose(filter) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected }
                    .testTag("search_filter_${filter.name}"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer),
                label = { Text(stringResource(filterLabel(filter))) })
        }
    }
}

private fun filterLabel(filter: SearchFilter) = when (filter) {
    SearchFilter.VIDEOS -> R.string.search_filter_videos
    SearchFilter.MUSIC -> R.string.search_filter_music
    SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
    SearchFilter.CHANNELS -> R.string.search_filter_channels
}

@Composable
private fun SearchContent(vm: SearchViewModel, offline: Boolean, thumbnail: suspend (String) -> Bitmap?, onOpen: (SearchResult) -> Unit) {
    val error = vm.error
    when {
        vm.results.isEmpty() && vm.loading -> SearchSkeleton()
        vm.results.isEmpty() && error != null ->
            if (offline || error == DownloadError.Offline) SearchMessage(Icons.Default.Warning, R.string.offline_title, R.string.offline_body, vm::retry)
            else SearchMessage(Icons.Default.Warning, R.string.search_failed, R.string.search_failed_body, vm::retry)
        vm.empty -> SearchMessage(Icons.Default.Search, R.string.search_no_results, R.string.search_no_results_body, null)
        else -> ResultList(vm, offline, thumbnail, onOpen)
    }
}

@Composable
private fun ResultList(vm: SearchViewModel, offline: Boolean, thumbnail: suspend (String) -> Bitmap?, onOpen: (SearchResult) -> Unit) {
    val list = rememberLazyListState()
    // Near the end, the next page; a failed page waits for its Retry.
    LaunchedEffect(vm, list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last >= vm.results.size - 5 && vm.hasMore && !vm.loading && vm.error == null) vm.loadMore()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("search_results"), state = list,
        contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        items(vm.results, key = { it.id }) { result -> ResultRow(result, thumbnail) { onOpen(result) } }
        if (vm.loading) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
        }
        vm.error?.let { error -> item(key = "error") {
            Column(Modifier.fillMaxWidth().padding(vertical = Space.m).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (offline || error == DownloadError.Offline) R.string.offline_title else R.string.search_more_failed),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(vm::retry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.try_again)) }
            }
        } }
        item(key = "nav") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun ResultRow(result: SearchResult, thumbnail: suspend (String) -> Bitmap?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val language = LocalConfiguration.current.locales[0].language
    val channel = result.kind == SearchKind.CHANNEL
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(MaterialTheme.shapes.medium)
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s)
        .semantics(mergeDescendants = true) {}.testTag("search_result_${result.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        // Lazy rows own at most a screenful of bounded bitmaps; leaving composition cancels the loader.
        val thumb = result.thumbnailUrl
        val bitmap by produceState<Bitmap?>(null, thumb) {
            value = if (thumb == null) null else thumbnail(thumb)
        }
        val shape = if (channel) CircleShape else MaterialTheme.shapes.medium
        Box((if (channel) Modifier.size(48.dp) else Modifier.width(128.dp).aspectRatio(16f / 9f))
            .clip(shape).background(colors.surfaceContainerHighest)) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(NezzelFormat.firstStrong(result.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
            // Channel and length are separate runs: an Arabic channel name never pulls the time across.
            val count = result.videoCount?.takeIf { result.kind == SearchKind.PLAYLIST }?.let { n ->
                NezzelFormat.firstStrong(pluralStringResource(R.plurals.search_playlist_videos, n, NezzelFormat.count(n, language)))
            }
            val meta = when (result.kind) {
                SearchKind.CHANNEL -> ""
                SearchKind.PLAYLIST -> listOfNotNull(result.channel?.let(NezzelFormat::firstStrong), count).joinToString(" · ")
                SearchKind.VIDEO -> listOfNotNull(result.channel?.let(NezzelFormat::firstStrong),
                    result.durationSeconds?.let { NezzelFormat.isolate(NezzelFormat.duration(it)) }).joinToString(" · ")
            }
            if (meta.isNotEmpty()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize().padding(horizontal = Space.l).testTag("search_skeleton").clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        repeat(7) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(block))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.fillMaxWidth(.9f).height(16.dp).clip(MaterialTheme.shapes.small).background(block))
                    Box(Modifier.fillMaxWidth(.6f).height(16.dp).clip(MaterialTheme.shapes.small).background(block))
                    Box(Modifier.fillMaxWidth(.4f).height(12.dp).clip(MaterialTheme.shapes.small).background(block))
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(icon: ImageVector, title: Int, body: Int, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.xl).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) Button(onRetry, Modifier.padding(top = Space.s).heightIn(min = 48.dp)) { Text(stringResource(R.string.try_again)) }
    }
}
