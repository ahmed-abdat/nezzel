package dev.ahmed.savlino.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.CheckWarning
import dev.ahmed.savlino.jobs.DownloadNotifications
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText

/** One "Needs a look" row: the file, what the check found, Try again (only with a kept link) and Dismiss. Same flat row as a failed job. */
@Composable
internal fun CheckWarningRow(warning: CheckWarning, onTryAgain: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    Surface(modifier.fillMaxWidth().testTag("check_warning"), shape = MaterialTheme.shapes.large, color = rowBase(MaterialTheme.colorScheme, light)) {
        Row(Modifier.fillMaxWidth().heightIn(min = JobRowHeight).padding(horizontal = Space.rowH, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.inline), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                // A filename stays LTR inside Arabic, starting where the layout starts.
                Text(NezzelFormat.isolate(warning.displayName), Modifier.fillMaxWidth(), maxLines = if (isLargeText()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
                Text(stringResource(DownloadNotifications.checkProblem(warning.problem)), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                if (warning.canRetry) {
                    val retry = stringResource(R.string.try_again)
                    FilledTonalIconButton(onClick = onTryAgain, modifier = Modifier.size(48.dp).testTag("check_try_again")
                        .semantics { contentDescription = retry }) { Icon(Icons.Default.Refresh, null, Modifier.size(24.dp)) }
                }
                val dismiss = stringResource(R.string.dismiss)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("check_dismiss")
                    .semantics { contentDescription = dismiss }) { Icon(Icons.Default.Close, null, Modifier.size(20.dp)) }
            }
        }
    }
}

/** "Look for better quality" in a saved file's sheet: [eligible] decides off the network; [open] starts the explicit inspection. */
class BetterQualityLink(val eligible: suspend (SavedMedia) -> Boolean, val open: (SavedMedia) -> Unit)
val LocalBetterQuality = staticCompositionLocalOf<BetterQualityLink?> { null }
