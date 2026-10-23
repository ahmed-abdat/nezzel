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
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

@Composable
internal fun InspectionHeader(inspection: Inspection?, host: String, checking: Boolean, earlyPreview: EarlyPreview? = null) {
    val title = inspection?.title ?: earlyPreview?.title
    val thumbnail = inspection?.thumbnailUrl ?: earlyPreview?.thumbnailUrl
    Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        PreviewThumbnail(thumbnail, audioOnly = inspection?.formats?.all { !it.hasVideo } == true)
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
internal fun PreviewThumbnail(url: String?, audioOnly: Boolean = false, load: suspend (String) -> Bitmap? = { ThumbnailLoader().load(it) }) {
    // Reset the old bitmap immediately on URL change, including null; produceState alone retains it.
    // The coroutine is cancelled on URL change or sheet disposal. No disk or process cache.
    key(url) {
        var finished by remember { mutableStateOf(url == null) }
        val bitmap by produceState<Bitmap?>(null, url) {
            value = url?.let { load(it) }
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
