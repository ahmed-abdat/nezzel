package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.domain.Inspection
import dev.ahmed.savlino.engine.mediahttp.EarlyPreview
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.SheetArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

@Composable
internal fun InspectionHeader(inspection: Inspection?, host: String, checking: Boolean, earlyPreview: EarlyPreview? = null,
    load: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) }, onThumbnail: () -> Unit = {}, hint: String? = null) {
    // The sender's own words stand in until a real title arrives; the title skeleton shows only without them.
    val title = inspection?.title ?: earlyPreview?.title ?: hint
    // The early (oEmbed) preview and the engine name different images of the same video. Once one has drawn,
    // keep it for this link: switching would blank the tile and fetch a second, larger image.
    var kept by remember { mutableStateOf<String?>(null) }
    val thumbnail = kept ?: inspection?.thumbnailUrl ?: earlyPreview?.thumbnailUrl
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    val inspectionId = inspection?.inspectionId
    // A Download tapped before the preview draws still gets it: the job fetches this address afterwards.
    LaunchedEffect(inspectionId, thumbnail) { if (inspectionId != null && thumbnail != null) SheetArtwork.source(inspectionId, thumbnail) }
    // Hand the shown artwork to a confirmed job (memory only; written to disk only on Download).
    LaunchedEffect(inspectionId, shown) {
        val bitmap = shown ?: return@LaunchedEffect
        if (inspectionId == null) return@LaunchedEffect
        SheetArtwork.show(inspectionId, bitmap)
        withContext(Dispatchers.Default) { SheetArtwork.take(inspectionId) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        PreviewThumbnail(thumbnail, load = load, audioOnly = inspection?.formats?.all { !it.hasVideo } == true, onBitmap = { shown = it; if (it != null) { kept = thumbnail; onThumbnail() } })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (checking && title == null) {
                Column(Modifier.testTag("preview_title_skeleton").clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Spacer(Modifier.fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    Spacer(Modifier.fillMaxWidth(.65f).height(12.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                }
            }
            // One line of facts (duration · channel · site) keeps the sheet short; the host stays an LTR island.
            val metadata = listOfNotNull(inspection?.durationSeconds?.let { NezzelFormat.isolate(NezzelFormat.duration(it)) },
                inspection?.uploader?.let(NezzelFormat::firstStrong), NezzelFormat.isolate(host))
            Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PreviewThumbnail(url: String?, audioOnly: Boolean = false, load: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) },
    onBitmap: (Bitmap?) -> Unit = {}) {
    val report by rememberUpdatedState(onBitmap)
    // Reset the old bitmap immediately on URL change, including null; produceState alone retains it.
    // The coroutine is cancelled on URL change or sheet disposal. No disk or process cache.
    key(url) {
        var finished by remember { mutableStateOf(url == null) }
        val bitmap by produceState<Bitmap?>(null, url) {
            report(null)
            value = url?.let { load(it) }
            report(value)
            finished = true
        }
        val current = bitmap
        Box(Modifier.width(96.dp).aspectRatio(16f / 9).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (current == null) Modifier.testTag(if (finished) "preview_thumbnail_fallback" else "preview_thumbnail_placeholder") else Modifier)
            .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            if (current != null) Image(current.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (finished) {
                if (audioOnly) Icon(painterResource(R.drawable.ic_music), null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else Spacer(Modifier.fillMaxSize())
        }
    }
}
