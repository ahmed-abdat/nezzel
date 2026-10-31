package dev.ahmed.savlino.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.viewer.MediaViewer
import dev.ahmed.savlino.viewer.ViewerItem
import dev.ahmed.savlino.viewer.rememberMediaThumbnail
import dev.ahmed.savlino.viewer.viewerStart

/** One line of the grid: up to three square tiles. Audio is a tile too (its cover, or a music note), so the grid stays one look. */
internal data class GridLine(val items: List<SavedMedia>) { val key get() = "grid-${items.first().uri}" }

/** Tiles fill rows of [columns] in list order. */
internal fun gridLines(entries: List<SavedMedia>, columns: Int = 3): List<GridLine> = entries.chunked(columns).map(::GridLine)

/** Images, videos and audio open in Nezzel's viewer (audio plays over its cover); unknown files go to the phone's own app. */
internal val SavedMedia.inViewer get() = kind == SavedMediaKind.IMAGE || kind == SavedMediaKind.VIDEO || kind == SavedMediaKind.AUDIO

internal fun viewerItems(items: List<SavedMedia>) = items.filter { it.inViewer }
    .map { ViewerItem(it.uri, it.kind == SavedMediaKind.VIDEO, it.mimeType, it.durationMillis, isAudio = it.kind == SavedMediaKind.AUDIO,
        resumePolicy = dev.ahmed.savlino.playback.ResumePolicy.SAVED) } // Saved files remember where you stopped; status copies stay session-only.

/** Grid or list for the current filter, remembered per filter. */
@Stable
internal class LibraryView(private val preferences: AppPreferences, private val filter: MediaFilter) {
    var grid by mutableStateOf(preferences.libraryGrid(filter.name)); private set
    fun toggle() { grid = !grid; preferences.setLibraryGrid(filter.name, grid) }
}

@Composable
internal fun rememberLibraryView(preferences: AppPreferences, filter: MediaFilter) = remember(preferences, filter) { LibraryView(preferences, filter) }

@Composable
internal fun LibraryViewToggle(view: LibraryView) = IconButton(onClick = view::toggle) {
    if (view.grid) Icon(painterResource(R.drawable.ic_list), stringResource(R.string.library_show_list))
    else Icon(painterResource(R.drawable.ic_grid), stringResource(R.string.library_show_grid))
}

/** The saved entries of one date group, as rows or as the grid; [row] draws the list row. */
internal fun LazyListScope.savedEntries(entries: List<SavedMedia>, grid: Boolean, language: String,
    selecting: Boolean, selectedUris: Set<String>, onOpen: (SavedMedia) -> Unit, onSelect: (SavedMedia) -> Unit, onToggle: (SavedMedia) -> Unit,
    row: @Composable LazyItemScope.(SavedMedia) -> Unit) {
    if (!grid) { items(entries.size, key = { entries[it].uri.toString() }, contentType = { "saved-row" }) { row(entries[it]) }; return }
    val lines = gridLines(entries)
    items(lines.size, key = { lines[it].key }, contentType = { "saved-grid-line" }) { index ->
        val line = lines[index]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.item)) {
            line.items.forEach { item ->
                val key = item.uri.toString()
                GridTile(item, language, selecting, key in selectedUris, Modifier.weight(1f),
                    onClick = { if (selecting) onToggle(item) else onOpen(item) }, onLongClick = { if (!selecting) onSelect(item) })
            }
            repeat(3 - line.items.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(item: SavedMedia, language: String, selecting: Boolean, selected: Boolean, modifier: Modifier,
                     onClick: () -> Unit, onLongClick: () -> Unit) {
    val video = item.kind == SavedMediaKind.VIDEO
    val audio = item.kind == SavedMediaKind.AUDIO
    val picture = rememberMediaThumbnail(item.uri, when (item.kind) {
        SavedMediaKind.VIDEO -> ThumbnailKind.VIDEO; SavedMediaKind.AUDIO -> ThumbnailKind.AUDIO; else -> ThumbnailKind.IMAGE }, 360)
    val description = "${NezzelFormat.firstStrong(item.displayName)}${stringResource(R.string.separator)}${mediaMeta(item, language)}"
    val selectedState = stringResource(if (selected) R.string.saved_selected_state else R.string.saved_unselected_state)
    val shape = MaterialTheme.shapes.small
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.aspectRatio(1f).clip(shape)
        .then(if (selected) Modifier.border(3.dp, primary, shape) else Modifier)
        .combinedClickable(role = if (selecting) Role.Checkbox else Role.Button,
            onClickLabel = if (selecting) null else stringResource(R.string.open_file),
            onLongClickLabel = if (selecting) null else stringResource(R.string.saved_select), onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = description
            if (selecting) { toggleableState = ToggleableState(selected); stateDescription = selectedState }
        }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.secondaryContainer) {
            if (picture != null) Image(picture.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(contentAlignment = Alignment.Center) {
                if (video) Icon(Icons.Default.PlayArrow, null)
                else Icon(painterResource(if (audio) R.drawable.ic_music else R.drawable.ic_image), null)
            }
        }
        if (selecting) Surface(Modifier.align(Alignment.TopEnd).padding(Space.xs).size(22.dp), shape = CircleShape,
            color = if (selected) primary else Color.Black.copy(alpha = .35f), contentColor = MaterialTheme.colorScheme.onPrimary,
            border = if (selected) null else androidx.compose.foundation.BorderStroke(2.dp, Color.White)) {
            if (selected) Icon(Icons.Default.Check, null, Modifier.padding(3.dp))
        }
        // Audio tiles carry a small music note beside the duration, so a cover is never mistaken for a video.
        if (video || audio) Surface(Modifier.align(Alignment.BottomStart).padding(Space.xs), color = Color.Black.copy(alpha = .72f),
            contentColor = Color.White, shape = MaterialTheme.shapes.extraSmall) {
            Row(Modifier.padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                if (audio) Icon(painterResource(R.drawable.ic_music), null, Modifier.size(12.dp))
                item.durationMillis?.let { Text(NezzelFormat.durationMillis(it), Modifier.padding(start = if (audio) 2.dp else 0.dp),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr)) }
            }
        }
    }
}

/** The viewer over the current filter's images and videos, opened at [uri]; [all] is the whole library, filters aside. */
@Composable
internal fun LibraryViewer(filtered: List<SavedMedia>, all: List<SavedMedia>, uri: String, language: String, onClose: () -> Unit,
                           onShare: (SavedMedia) -> Unit, onOpenWith: (SavedMedia) -> Unit) {
    val pages = remember(filtered) { viewerItems(filtered) }
    val library = remember(all) { all.mapTo(HashSet()) { it.uri } }
    val start = remember(uri) { viewerStart(pages, android.net.Uri.parse(uri)) }
    // Every file in view was deleted or filtered away: nothing left to show.
    if (pages.isEmpty()) LaunchedEffect(Unit) { onClose() }
    fun find(page: ViewerItem) = filtered.firstOrNull { it.uri == page.uri }
    MediaViewer(pages, start, language, onClose, onShare = { page -> find(page)?.let(onShare) }, onOpenWith = { page -> find(page)?.let(onOpenWith) },
        library = library)
}
