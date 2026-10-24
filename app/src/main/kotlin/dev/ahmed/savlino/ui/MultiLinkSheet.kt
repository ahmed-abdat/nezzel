package dev.ahmed.savlino.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.MultiLinkLabel
import dev.ahmed.savlino.domain.MultiLinks
import dev.ahmed.savlino.domain.Preset
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiLinkSheet(vm: MultiLinkViewModel, language: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        MultiLinkSheetContent(vm, language)
    }
}

/** Same presets and button as the playlist sheet; rows are the links themselves (host + short path, LTR). */
@Composable
internal fun MultiLinkSheetContent(vm: MultiLinkViewModel, language: String) {
    val count = vm.links.size
    val title = pluralStringResource(R.plurals.multi_link_title, count, NezzelFormat.count(count, language))
    Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().testTag("multi_link_sheet")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            item(key = "header") {
                Column(Modifier.padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (vm.truncated) Text(pluralStringResource(R.plurals.multi_link_limit, MultiLinks.MAX,
                        NezzelFormat.count(MultiLinks.MAX, language)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "quality") {
                Column(Modifier.selectableGroup().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(Preset.BEST to R.string.preset_best, Preset.BALANCED to R.string.preset_balanced,
                        Preset.SMALL to R.string.preset_small, Preset.AUDIO_M4A to R.string.audio_only).forEach { (preset, label) ->
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
            itemsIndexed(vm.links, key = { _, link -> link.normalizedUrl }) { index, link ->
                val label = MultiLinkLabel.short(link)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("multi_link_item_$index"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(NezzelFormat.isolate(label), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                    IconButton({ vm.remove(index) }, enabled = !vm.submitting, modifier = Modifier.size(48.dp).testTag("multi_link_remove_$index")) {
                        Icon(Icons.Default.Close, stringResource(R.string.multi_link_remove, MultiLinkLabel.host(link)), Modifier.size(20.dp))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (vm.submitError) {
                Text(stringResource(R.string.multi_link_submit_failed), color = MaterialTheme.colorScheme.error)
                TextButton({ vm.confirm(title) }, enabled = vm.canConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.try_again))
                }
            }
            Button({ vm.confirm(title) }, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("multi_link_download"), enabled = vm.canConfirm) {
                Text(pluralStringResource(R.plurals.playlist_download, count, NezzelFormat.count(count, language)))
            }
        }
    }
}
