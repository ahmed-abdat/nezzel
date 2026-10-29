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
import dev.ahmed.savlino.preview.CachedPreview
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
    load: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) }, onThumbnail: () -> Unit = {}, hint: String? = null,
    cachedPreview: CachedPreview? = null, onArtwork: (Bitmap) -> Unit = {}) {
    // The sender's own words stand in until a real title arrives; the title skeleton shows only without them.
    val title = if (inspection != null) inspection.title else cachedPreview?.title ?: earlyPreview?.title ?: hint
    // The early (oEmbed) preview and the engine name different images of the same video. Once one has drawn,
    // keep it for this link: switching would blank the tile and fetch a second, larger image.
    var kept by remember { mutableStateOf<String?>(null) }
    // Cached pixels need no stale URL fetch; the live result still gets its own fresh artwork load.
    val thumbnail = when {
        cachedPreview != null && inspection != null -> inspection.thumbnailUrl
        cachedPreview?.artwork != null -> null
        else -> kept ?: inspection?.thumbnailUrl ?: earlyPreview?.thumbnailUrl
    }
    val fallback = cachedPreview?.artwork?.takeIf { inspection == null || thumbnail != null }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    val inspectionId = inspection?.inspectionId
    // A Download tapped before the preview draws still gets it: the job fetches this address afterwards.
    LaunchedEffect(inspectionId, thumbnail) { if (inspectionId != null && thumbnail != null) SheetArtwork.source(inspectionId, thumbnail) }
    val cacheArtwork by rememberUpdatedState(onArtwork)
    // Once a live inspection exists, share its drawn artwork with both the display cache and explicit-download handoff.
    LaunchedEffect(inspectionId, shown) {
        val bitmap = shown ?: return@LaunchedEffect
        if (inspectionId == null) return@LaunchedEffect
        if (bitmap !== cachedPreview?.artwork) cacheArtwork(bitmap)
        SheetArtwork.show(inspectionId, bitmap)
        withContext(Dispatchers.Default) { SheetArtwork.take(inspectionId) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        PreviewThumbnail(thumbnail, load = load, fallback = fallback,
            audioOnly = inspection?.formats?.all { !it.hasVideo } == true, onBitmap = {
                shown = it
                if (it != null) {
                    if (it !== fallback) kept = thumbnail
                    onThumbnail()
                }
            })
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
            // The channel gets its own shortened line, so a long or mixed-direction name never splits the facts or hides the site.
            val small = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)
            (if (inspection != null) inspection.uploader else cachedPreview?.uploader)?.let {
                Text(NezzelFormat.firstStrong(it), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // duration · site; the host stays an LTR island.
            val duration = if (inspection != null) inspection.durationSeconds else cachedPreview?.durationSeconds
            val facts = listOfNotNull(duration?.let { NezzelFormat.isolate(NezzelFormat.duration(it)) }, NezzelFormat.isolate(host))
            Text(facts.joinToString(" · "), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PreviewThumbnail(url: String?, audioOnly: Boolean = false, load: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) },
    onBitmap: (Bitmap?) -> Unit = {}, fallback: Bitmap? = null) {
    val report by rememberUpdatedState(onBitmap)
    // Reset the old bitmap immediately on URL change, including null; produceState alone retains it.
    // The coroutine is cancelled on URL change or sheet disposal; cached display pixels may bridge a live refresh.
    key(url, fallback) {
        var finished by remember { mutableStateOf(url == null) }
        val bitmap by produceState<Bitmap?>(fallback, url, fallback) {
            report(fallback)
            value = url?.let { load(it) } ?: fallback
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
