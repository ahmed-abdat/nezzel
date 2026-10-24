package dev.ahmed.savlino.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

/** A link arrived while another is being edited: replace it or keep working. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingShareNote(host: String?, onReplace: () -> Unit, onDismiss: () -> Unit) {
    NoteRow(R.drawable.ic_link) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.pending_share), style = MaterialTheme.typography.bodyLarge)
            host?.let {
                Text(stringResource(R.string.pending_share_from, NezzelFormat.isolate(it)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                FilledTonalButton(onClick = onReplace, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.replace_link)) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

/** Several links were shared at once: the next one waits here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NextLinkNote(host: String, number: Int, total: Int, language: String, onOpen: () -> Unit, onSkip: () -> Unit) {
    NoteRow(R.drawable.ic_link) {
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.next_link, NezzelFormat.count(number, language), NezzelFormat.count(total, language)),
                style = MaterialTheme.typography.titleSmall)
            Text(NezzelFormat.isolate(host), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_file)) }
                TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.skip_remaining_links)) }
            }
        }
    }
}

/** The download engine could not start. */
@Composable
internal fun EngineUnavailableNote() {
    NoteRow(null, MaterialTheme.colorScheme.error) {
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.unavailable_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.unavailable_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A plain note on the page: leading 24dp glyph, content beside it. No container; the section gap separates it. */
@Composable
private fun NoteRow(@androidx.annotation.DrawableRes icon: Int?, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        if (icon != null) TintedIcon(icon, tint)
        else androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Warning, null, Modifier.size(24.dp), tint = tint)
        Box(Modifier.weight(1f)) { content() }
    }
}
