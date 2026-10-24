package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.rememberMetered
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
    val metered = rememberMetered()
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
        if (format != null && !links.confirmed && largeOnMobileData(format, roughBytes, metered)) {
            MobileDataNote(format, roughBytes, language)
        }
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

/** States the cost: "Uses about 412 MB of your mobile data". The words carry "about", so the size drops its ≈. */
@Composable
private fun MobileDataNote(format: Format, roughBytes: Long?, language: String) {
    val bytes = knownBytes(format, roughBytes) ?: return
    val amount = NezzelFormat.isolate(NezzelFormat.size(bytes, language))
    Row(Modifier.testTag("mobile_data_note").semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        // Centred on the first line, so a wrapped sentence keeps the icon beside its start.
        val line = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
        Icon(painterResource(R.drawable.ic_mobile_data), contentDescription = null,
            Modifier.padding(top = ((line - 18.dp) / 2).coerceAtLeast(0.dp)).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mobileDataText(isEstimate(format, roughBytes), amount), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun mobileDataText(estimate: Boolean, amount: String): String =
    stringResource(if (estimate) R.string.mobile_data_about else R.string.mobile_data_exact, amount)

private fun knownBytes(format: Format, roughBytes: Long?): Long? = when (val size = format.size) {
    is Size.Exact -> size.bytes
    is Size.Estimated -> size.bytes
    Size.Unknown -> roughBytes
}

/** The pick is big enough to matter on a data plan: only a size the Download button shows (exact or ≈), never a guess. */
internal const val LARGE_ON_MOBILE_DATA_BYTES = 100_000_000L

internal fun largeOnMobileData(format: Format, roughBytes: Long?, metered: Boolean): Boolean {
    if (!metered) return false
    return (knownBytes(format, roughBytes) ?: return false) >= LARGE_ON_MOBILE_DATA_BYTES
}

/**
 * Same link and same format already saved: say so and offer Open. Download stays enabled and saves
 * another copy; this never blocks.
 */
@Composable
internal fun SavedBeforeNotice(saved: SavedBefore, container: String?, language: String) {
    val context = LocalContext.current
    // Plain note: a leading glyph, the sentence, a text action. No box around one row.
    Row(Modifier.fillMaxWidth().testTag("saved_before"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(painterResource(R.drawable.ic_download), contentDescription = null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
