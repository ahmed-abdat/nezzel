package dev.ahmed.savlino.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.CheckWarning
import dev.ahmed.savlino.jobs.DownloadNotifications
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText

/**
 * One "Needs a look" row, shaped like a library row: thumbnail, title, the red reason, Try again (only with a kept link)
 * and Dismiss. [media] is the library entry for the file when it is listed (thumbnail + display title).
 */
@Composable
internal fun CheckWarningRow(warning: CheckWarning, media: SavedMedia?, onTryAgain: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val title = media?.displayName ?: warning.displayName.substringBeforeLast('.')
    @Composable fun Thumb(m: Modifier) = if (media != null) MediaThumbnail(media, m)
        else Surface(m.clip(MaterialTheme.shapes.medium), color = MaterialTheme.colorScheme.secondaryContainer) {}
    @Composable fun Texts(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
        Text(NezzelFormat.firstStrong(title), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(DownloadNotifications.checkProblem(warning.problem)), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    @Composable fun Actions() = Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (warning.canRetry) {
            val retry = stringResource(R.string.try_again)
            FilledTonalIconButton(onClick = onTryAgain, modifier = Modifier.size(48.dp).testTag("check_try_again")
                .semantics { contentDescription = retry }) { Icon(Icons.Default.Refresh, null, Modifier.size(24.dp)) }
        }
        val dismiss = stringResource(R.string.dismiss)
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("check_dismiss")
            .semantics { contentDescription = dismiss }) { Icon(Icons.Default.Close, null, Modifier.size(20.dp)) }
    }
    val row = modifier.fillMaxWidth().heightIn(min = JobRowHeight).testTag("check_warning").padding(vertical = Space.s)
    // Large text: the thumbnail and buttons share the top line and the words get the full width, like a narrow library row.
    if (isLargeText()) Column(row, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Thumb(Modifier.size(128.dp, 72.dp)); Spacer(Modifier.weight(1f)); Actions()
        }
        Texts(Modifier.fillMaxWidth())
    } else Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        Thumb(Modifier.width(96.dp).aspectRatio(16f / 9f))
        Texts(Modifier.weight(1f))
        Actions()
    }
}

/** "Look for better quality" in a saved file's sheet: [eligible] decides off the network; [open] starts the explicit inspection. */
class BetterQualityLink(val eligible: suspend (SavedMedia) -> Boolean, val open: (SavedMedia) -> Unit)
val LocalBetterQuality = staticCompositionLocalOf<BetterQualityLink?> { null }

/** Failed jobs use their retained source, checked again on tap; no source is stored in UI state. */
class FailedQualityLink(val eligible: suspend (String) -> Boolean, val open: (String) -> Unit)
val LocalFailedQuality = staticCompositionLocalOf<FailedQualityLink?> { null }
