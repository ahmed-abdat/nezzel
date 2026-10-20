package dev.ahmed.savlino.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import dev.ahmed.savlino.domain.MediaRenditionRef
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImageSaveAvailability
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.storage.StorageError
import dev.ahmed.savlino.ui.theme.Space

internal fun imageBatchMessage(resources: android.content.res.Resources,
    event: dev.ahmed.savlino.media.ImageSaveState.BatchFinished, language: String): String {
    val result = if (event.saved.size == event.total) resources.getQuantityString(R.plurals.image_saved_count, event.saved.size,
        NezzelFormat.count(event.saved.size, language))
    else resources.getString(if (event.cancelled) R.string.image_save_cancelled_count else R.string.image_save_partial_count,
        NezzelFormat.count(event.saved.size, language), NezzelFormat.count(event.total, language))
    return if (event.failure?.storage == StorageError.FolderUnavailable)
        result + "\n" + resources.getString(R.string.save_folder_error) else result
}

/** Bounded real photo previews, accessible selection, and truthful discovery limits. */
@Composable
internal fun ImageOptions(state: OptionsState.ImageReady, host: String, language: String,
    selected: Set<MediaRenditionRef> = state.photos.map { it.ref }.toSet(), enabled: Boolean = true,
    onToggle: (MediaRenditionRef) -> Unit = {}, onSelectAll: (Boolean) -> Unit = {}, onVideo: () -> Unit = {}) {
    // Preview pixels may belong to a smaller CDN rendition, never to the file being saved.
    val width = state.image.width
    val height = state.image.height
    // One LTR unit: isolating W and H separately lets an RTL paragraph reorder the pair (1125 × 1224 read as 1224 × 1125).
    val dimensions = if (width != null && height != null) NezzelFormat.isolate(stringResource(R.string.saved_image_dimensions,
        NezzelFormat.count(width, language), NezzelFormat.count(height, language))) else null
    val description = if (dimensions != null) stringResource(R.string.image_preview_desc, dimensions)
        else stringResource(R.string.image_save)
    val frame = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    val preview = state.preview
    if (state.isCarousel) {
        Text(pluralStringResource(R.plurals.image_available_count, state.photos.size,
            NezzelFormat.count(state.photos.size, language)), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onSelectAll(selected.size != state.photos.size) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (selected.size == state.photos.size) R.string.image_select_none else R.string.image_select_all))
        }
        // Regular Rows compose every bounded item and inherit LayoutDirection, including the reading order in Arabic.
        state.photos.chunked(3).forEachIndexed { row, photos ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                photos.forEachIndexed { column, photo ->
                    val index = row * 3 + column + 1
                    val checked = photo.ref in selected
                    val label = stringResource(R.string.image_thumbnail_desc, NezzelFormat.count(index, language),
                        NezzelFormat.count(state.photos.size, language))
                    Box(Modifier.weight(1f).aspectRatio(1f).heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(photo.ref) })
                        .semantics(mergeDescendants = true) { contentDescription = label }) {
                        state.previews[photo.ref]?.let { bitmap ->
                            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Checkbox(checked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd)
                            .clearAndSetSemantics {})
                    }
                }
                repeat(3 - photos.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else if (preview != null) Image(preview, contentDescription = description, modifier = frame, contentScale = ContentScale.Fit)
    else Box(frame.aspectRatio(4f / 3).semantics { contentDescription = description; role = Role.Image })
    if (!state.isCarousel) Text(listOfNotNull(dimensions, NezzelFormat.isolate(state.image.format.name)).joinToString(stringResource(R.string.separator)),
        style = MaterialTheme.typography.titleSmall)
    Text(NezzelFormat.isolate(host), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val found = maxOf(state.discovery.discoveredCount, state.discovery.claimedCount ?: 0)
    if (state.discovery.previewQuality) Text(stringResource(R.string.image_preview_quality),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.discovery.cappedCount > 0) Text(stringResource(R.string.image_first_limit,
        NezzelFormat.count(20, language)), style = MaterialTheme.typography.bodyMedium)
    if (state.discovery.unavailableCount > 0) Text(pluralStringResource(R.plurals.image_unavailable_count,
        state.discovery.unavailableCount, NezzelFormat.count(state.discovery.unavailableCount, language)),
        style = MaterialTheme.typography.bodyMedium)
    if (state.discovery.notPreviewedCount > 0) Text(pluralStringResource(R.plurals.image_not_previewed_count,
        state.discovery.notPreviewedCount, NezzelFormat.count(state.discovery.notPreviewedCount, language)), style = MaterialTheme.typography.bodyMedium)
    // Older single-image providers may enumerate attachments without selectable renditions.
    if (found > state.photos.size && state.discovery.unavailableCount == 0 && state.discovery.cappedCount == 0 && state.discovery.notPreviewedCount == 0)
        Text(stringResource(R.string.image_available_of, NezzelFormat.count(state.photos.size, language),
            NezzelFormat.count(found, language)), style = MaterialTheme.typography.bodyMedium)
    if (state.discovery.hasVideo) TextButton(onClick = onVideo, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.image_video_options)) }
    if (state.availability is ImageSaveAvailability.Unavailable) Text(stringResource(R.string.image_changed),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
}

/** Explicit Save of the chosen images; progress and Cancel replace it during the batch. */
@Composable
internal fun ImageSaveBar(links: LinkViewModel, language: String) {
    val ready = links.optionsState as? OptionsState.ImageReady ?: return
    Column(Modifier.fillMaxWidth().padding(Space.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (links.imageSaving) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val progress = links.imageSaveProgress
            val saving = if (progress != null && ready.isCarousel) stringResource(R.string.image_saving_count,
                NezzelFormat.count(progress.first, language), NezzelFormat.count(progress.second, language))
                else stringResource(R.string.image_saving)
            Text(saving, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = links::cancelImageSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        } else {
            // Only the complete measured body is a size; otherwise none is shown.
            val bytes = ready.image.proof?.measuredBytes
            val label = if (ready.isCarousel) pluralStringResource(R.plurals.image_save_count, links.selectedImageRefs.size,
                NezzelFormat.count(links.selectedImageRefs.size, language)) else if (bytes != null) stringResource(R.string.image_save_size, NezzelFormat.isolate(NezzelFormat.size(bytes, language)))
                else stringResource(R.string.image_save)
            Button(onClick = links::saveImage, enabled = links.imageSaveEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
        }
    }
}

@Composable
internal fun ImageInspectionError(state: OptionsState.ImageError, onRetry: () -> Unit, onDismiss: () -> Unit,
    host: String? = null, onPasteAnother: ((String?) -> Unit)? = null) {
    val (title, body, retry) = imageErrorCopy(state)
    // A failed Save is about this image, not the link: no "Paste another link" there.
    val saveFailure = state.failure in setOf(ImageFailure.STAGING, ImageFailure.INSUFFICIENT_SPACE, ImageFailure.STALE, ImageFailure.PROOF_MISMATCH,
        ImageFailure.SAVE_UNAVAILABLE)
    FailureContent(title, body, host, onRetry.takeIf { retry }, onPasteAnother.takeUnless { saveFailure }, onDismiss)
}

internal fun imageErrorCopy(state: OptionsState.ImageError): Triple<Int, Int?, Boolean> = when (state.failure) {
    ImageFailure.PREVIEW_LIMIT -> Triple(R.string.image_preview_limit_title, R.string.image_preview_limit_body, false)
    ImageFailure.LINKED_ELSEWHERE -> Triple(R.string.image_linked_elsewhere, null, false)
    ImageFailure.NO_MEDIA -> Triple(R.string.image_none_title, R.string.image_none_body, false)
    ImageFailure.UNSUPPORTED, ImageFailure.ANIMATED -> Triple(R.string.image_type_title, R.string.image_type_body, false)
    // Identity/transport refusal of the post or link: never reported as an image-type problem.
    ImageFailure.SOURCE_REFUSED -> if (state.directImage)
        Triple(R.string.image_refused_title, R.string.image_refused_body, true)
        else Triple(R.string.image_source_refused_title, R.string.image_source_refused_body, true)
    ImageFailure.PRIVATE -> Triple(R.string.image_private_title, R.string.private_body, false)
    ImageFailure.HTTP -> if (state.statusCode == 401 || state.statusCode == 403)
        Triple(R.string.image_private_title, R.string.private_body, false)
        else Triple(R.string.read_failed_title, R.string.read_failed_body, true)
    ImageFailure.BUDGET -> if (state.phase == ImagePhase.PAGE) Triple(R.string.read_failed_title, R.string.read_failed_body, true)
        else Triple(R.string.image_large_title, null, false)
    ImageFailure.DIMENSIONS -> Triple(R.string.image_large_title, null, false)
    ImageFailure.NETWORK -> Triple(R.string.network_title, R.string.network_body, true)
    ImageFailure.STALE, ImageFailure.PROOF_MISMATCH, ImageFailure.SAVE_UNAVAILABLE -> Triple(R.string.image_changed, null, true)
    ImageFailure.INSUFFICIENT_SPACE -> Triple(R.string.image_save_failed, R.string.download_space_error, false)
    ImageFailure.STAGING -> if (state.storage == StorageError.FolderUnavailable)
        Triple(R.string.image_save_failed, R.string.save_folder_error, true)
        else if (state.storage == StorageError.InsufficientSpace)
        Triple(R.string.image_save_failed, R.string.download_space_error, false)
        else Triple(R.string.image_save_failed, null, true)
    ImageFailure.UNAVAILABLE, ImageFailure.INVALID_IMAGE, ImageFailure.DECODE, ImageFailure.CANCELLED ->
        Triple(R.string.read_failed_title, R.string.read_failed_body, true)
}
