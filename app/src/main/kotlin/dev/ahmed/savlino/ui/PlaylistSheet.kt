package dev.ahmed.savlino.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Preset
import dev.ahmed.savlino.engine.PlaylistItem
import dev.ahmed.savlino.engine.PlaylistUnavailable
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun PlaylistOptionsRow(onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playlist_options_row"),
        contentPadding = PaddingValues(vertical = Space.s)) {
        Text(stringResource(R.string.playlist_see_all), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSheet(vm: PlaylistViewModel, language: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        PlaylistSheetContent(vm, language)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistSheetContent(vm: PlaylistViewModel, language: String) {
    val list = rememberLazyListState()
    LaunchedEffect(vm, list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged().collect { last ->
            if (last >= vm.items.size - 5 && vm.hasMore && !vm.loading && vm.error == null) vm.loadMore()
        }
    }
    // Observe newly loaded pages too: a short page may leave the last visible index unchanged.
    LaunchedEffect(vm.items.size, vm.hasMore, vm.loading) {
        if (vm.hasMore && !vm.loading && vm.error == null &&
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= vm.items.size - 5) vm.loadMore()
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().testTag("playlist_sheet")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (vm.title.isNotBlank()) Text(NezzelFormat.firstStrong(vm.title), style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                        modifier = Modifier.semantics { heading() })
                    val count = NezzelFormat.number((vm.total ?: vm.items.size.toLong()).toDouble(), language, 0) + if (vm.total == null && vm.hasMore) "+" else ""
                    Text(stringResource(R.string.playlist_title, count), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        TextButton(vm::selectAll, enabled = !vm.submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.playlist_select_all)) }
                        TextButton(vm::selectNone, enabled = !vm.submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.playlist_select_none)) }
                    }
                }
            }
            item(key = "quality") {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(Preset.BEST to R.string.preset_best, Preset.BALANCED to R.string.preset_balanced,
                        Preset.SMALL to R.string.preset_small, Preset.AUDIO_M4A to R.string.audio_only,
                        Preset.AUDIO_MP3 to R.string.mp3_title).forEach { (preset, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(vm.preset == preset, enabled = !vm.submitting,
                            role = Role.RadioButton, onClick = { vm.quality(preset) }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(vm.preset == preset, null, Modifier.size(48.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(stringResource(R.string.playlist_quality_helper), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.playlist_sizes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(vm.items, key = { it.id }) { item ->
                PlaylistItemRow(item, item.id in vm.selected, item.id in vm.savedBefore, !vm.submitting) { vm.toggle(item.id) }
            }
            if (vm.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (vm.error != null) item(key = "error") {
                Column {
                    Text(stringResource(R.string.playlist_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(vm::loadMore, enabled = !vm.loading && !vm.submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.try_again)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.playlist_selected, NezzelFormat.count(vm.selected.size, language)),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (vm.selected.size > 200) Text(stringResource(R.string.playlist_limit), color = MaterialTheme.colorScheme.error)
            if (vm.submitError) {
                Text(stringResource(R.string.playlist_submit_failed), color = MaterialTheme.colorScheme.error)
                TextButton(vm::confirm, enabled = vm.canConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_submit_retry")) {
                    Text(stringResource(R.string.try_again))
                }
            }
            Button(vm::confirm, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("playlist_download"), enabled = vm.canConfirm) {
                Text(pluralStringResource(R.plurals.playlist_download, vm.selected.size, NezzelFormat.count(vm.selected.size, language)))
            }
        }
    }
}

@Composable
private fun PlaylistItemRow(item: PlaylistItem, selected: Boolean, saved: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val reason = if (item.selectable) null else stringResource(when (item.unavailable) {
        PlaylistUnavailable.Live -> R.string.playlist_live
        PlaylistUnavailable.Private -> R.string.playlist_private
        else -> R.string.playlist_unavailable
    })
    val selection = stringResource(if (selected) R.string.saved_selected_state else R.string.saved_unselected_state)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).alpha(if (item.selectable) 1f else .5f)
        .toggleable(selected, enabled = enabled && item.selectable, role = Role.Checkbox, onValueChange = { onToggle() })
        .semantics(mergeDescendants = true) { stateDescription = selection }.testTag("playlist_item_${item.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        // Lazy rows own at most a screenful of bounded bitmaps; disposal cancels the existing bounded loader.
        key(item.id) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, item.thumbnailUrl) {
                value = item.thumbnailUrl?.let { ThumbnailLoader().load(it) }
            }
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(NezzelFormat.firstStrong(item.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content))
            if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall)
            else item.durationSeconds?.let { Text(NezzelFormat.duration(it), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) }
            if (saved) Text(stringResource(R.string.playlist_saved_before), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Checkbox(selected, null, Modifier.size(48.dp).clearAndSetSemantics {}, enabled = enabled && item.selectable)
    }
}
