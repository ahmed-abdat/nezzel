package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.SavedBefore
import dev.ahmed.savlino.jobs.mimeTypeFor
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

/**
 * Saved-before notice and destination, at the end of the scrolling options: at large text a pinned footer that
 * held them would crowd out the choices themselves.
 */
@Composable
internal fun ConfirmationDetails(links: LinkViewModel, language: String) {
    if (!links.confirmed) links.savedBefore?.let { SavedBeforeNotice(it, links.selectedFormat?.container, language) }
    SavesTo()
}

@Composable
internal fun SavesTo() = Text(stringResource(R.string.saves_to, NezzelFormat.isolate(dev.ahmed.savlino.ui.saveFolderLabel())),
    style = MaterialTheme.typography.bodySmall)

/** The explicit Download action for the chosen quality, pinned under the options; nothing is queued until it is pressed. */
@Composable
fun ConfirmationScreen(links: LinkViewModel, language: String) {
    Column(Modifier.fillMaxWidth().padding(Space.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (links.confirmed) {
            Text(stringResource(R.string.choice_confirmed), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium)
        }
        if (links.optionsChanged) {
            Text(stringResource(R.string.options_changed), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
        }
        val format = links.selectedFormat
        val roughBytes = format?.let(links::roughSizeBytes)
        val sized = format != null && (format.size != Size.Unknown || roughBytes != null)
        val label = if (links.refreshing) stringResource(R.string.checking_again)
            else if (sized) stringResource(R.string.download_size, sizeLabel(format!!, language, roughBytes)) else stringResource(R.string.download)
        // TalkBack hears "About 4.7 MB, estimated", never the ≈ symbol.
        val spoken = if (sized && !links.refreshing) stringResource(R.string.download_size, spokenSize(format!!, language, roughBytes)) else label
        Button(onClick = links::requestDownload, enabled = links.downloadEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                contentDescription = spoken
                if (links.refreshing) liveRegion = LiveRegionMode.Polite
            }) { Text(label) }
    }
}

/**
 * Same link and same format already saved: say so and offer Open. Download stays enabled and saves
 * another copy; this never blocks.
 */
@Composable
private fun SavedBeforeNotice(saved: SavedBefore, container: String?, language: String) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("saved_before")) {
        Row(Modifier.padding(start = Space.m, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.saved_before, NezzelFormat.shortDate(saved.savedAt, language)),
                Modifier.weight(1f).padding(vertical = Space.m), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, container?.let(::mimeTypeFor))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, R.string.open_file_failed, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(context, R.string.open_file_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_file)) }
        }
    }
}
