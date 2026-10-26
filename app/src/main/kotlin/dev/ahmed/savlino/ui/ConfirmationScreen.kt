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
import dev.ahmed.savlino.ui.theme.motionSpec
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Notifications

/**
 * Saved-before notice and destination, at the end of the scrolling options: at large text a pinned footer that
 * held them would crowd out the choices themselves.
 */
@Composable
internal fun ConfirmationDetails(links: LinkViewModel, language: String, savedNotice: Boolean = true) {
    if (savedNotice && !links.confirmed) links.savedBefore?.let { SavedBeforeNotice(it, links.selectedFormat?.container, language) }
    SavesTo()
}

@Composable
internal fun SavesTo() = Text(stringResource(R.string.saves_to, NezzelFormat.isolate(dev.ahmed.savlino.ui.saveFolderLabel())),
    style = MaterialTheme.typography.bodySmall)

/** The explicit Download action for the chosen quality, pinned under the options; nothing is queued until it is pressed. */
@Composable
fun ConfirmationScreen(links: LinkViewModel, language: String, share: ShareSheetHooks? = null) {
    val metered = rememberMetered()
    Column(Modifier.fillMaxWidth().padding(Space.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (share != null) {
            // The share sheet confirms on the button itself (a check), then closes; no second line of text.
            val done = links.confirmed || share.sent
            if (!done && share.notifyLine) NotifyLine(share.onAllowNotifications)
            val reduced = rememberReducedMotion()
            androidx.compose.animation.Crossfade(done, animationSpec = motionSpec(reduced, androidx.compose.animation.core.tween(300)),
                label = "download_check") { checked ->
                if (checked) StartedCheck() else DownloadButton(links, language, metered, again = links.savedBefore != null)
            }
            return@Column
        }
        if (links.confirmed) {
            Text(stringResource(R.string.choice_confirmed), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium)
        }
        if (links.optionsChanged) {
            Text(stringResource(R.string.options_changed), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
        }
        DownloadButton(links, language, metered)
    }
}

/** [again]: the chosen format was saved before; the button says so and still saves another copy. */
@Composable
private fun DownloadButton(links: LinkViewModel, language: String, metered: Boolean, again: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val format = links.selectedFormat
        val roughBytes = format?.let(links::roughSizeBytes)
        if (format != null && !links.confirmed && largeOnMobileData(format, roughBytes, metered)) {
            MobileDataNote(format, language)
        }
        val sizedLabel = if (again) R.string.download_again_size else R.string.download_size
        val size = format?.let { sizeLabel(it, language) }
        val label = if (links.refreshing) stringResource(R.string.checking_again)
            else if (size != null) stringResource(sizedLabel, size)
            else stringResource(if (again) R.string.download_again else R.string.download)
        Button(onClick = links::requestDownload, enabled = links.downloadEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                if (links.refreshing) liveRegion = LiveRegionMode.Polite
            }) { Text(label) }
    }
}

/** The Download button's place after the tap: a check and "Download started", announced once. */
@Composable
private fun StartedCheck() {
    val label = stringResource(R.string.share_sheet_started)
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("download_started").semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        }) {
        Row(Modifier.padding(horizontal = Space.l), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** First download from the share sheet only: one line instead of a separate permission screen. */
@Composable
private fun NotifyLine(onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("share_notify_line"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Notifications, contentDescription = null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.share_notify_line), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.allow_notifications)) }
    }
}

/** States the cost: "Uses 412 MB of your mobile data" when the size is exact; a big estimate names no number. */
@Composable
private fun MobileDataNote(format: Format, language: String) {
    val amount = exactBytes(format)?.let { NezzelFormat.isolate(NezzelFormat.size(it, language)) }
    Row(Modifier.testTag("mobile_data_note").semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        // Centred on the first line, so a wrapped sentence keeps the icon beside its start.
        val line = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
        Icon(painterResource(R.drawable.ic_mobile_data), contentDescription = null,
            Modifier.padding(top = ((line - 18.dp) / 2).coerceAtLeast(0.dp)).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mobileDataText(amount), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun mobileDataText(amount: String?): String =
    if (amount == null) stringResource(R.string.mobile_data_large) else stringResource(R.string.mobile_data_exact, amount)

private fun knownBytes(format: Format, roughBytes: Long?): Long? = when (val size = format.size) {
    is Size.Exact -> size.bytes
    is Size.Estimated -> size.bytes
    Size.Unknown -> roughBytes
}

/** The pick is big enough to matter on a data plan, by its exact size or the engine's estimate. */
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
internal fun SavedBeforeNotice(saved: SavedBefore, container: String?, language: String, onOpened: () -> Unit = {}) {
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
                    // Before a format is picked only the file knows its type; MediaStore keeps it for our own rows.
                    val type = container?.let(::mimeTypeFor) ?: context.contentResolver.getType(saved.uri)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    onOpened()
                } catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, R.string.open_file_failed, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(context, R.string.open_file_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_file)) }
    }
}
