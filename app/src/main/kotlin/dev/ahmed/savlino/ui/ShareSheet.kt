package dev.ahmed.savlino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.ui.theme.Space
import java.net.URI

/**
 * What the share sheet adds around the shared options body. [sent]: the coordinator took the job (or Download was
 * tapped); the sheet shows a check until it closes. [notifyLine]: first download only, one line above the button.
 */
class ShareSheetHooks(val onOpenApp: () -> Unit, val onFinish: () -> Unit, val notifyLine: Boolean = false,
    val onAllowNotifications: () -> Unit = {}, val sent: Boolean = false)

/** The options as a bottom sheet over the app the person shared from. Every way out closes the sheet, except mid photo save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(links: LinkViewModel, language: String, hooks: ShareSheetHooks) {
    val saving = links.imageSaving
    // A photo save lives in this sheet's view model: dragging, tapping outside or Back must not end it. Cancel stays.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !links.imageSaving })
    ModalBottomSheet(onDismissRequest = { if (!links.imageSaving) hooks.onFinish() }, sheetState = state,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !saving)) {
        BackHandler(enabled = saving) { }
        ShareSheetBody(links, language, hooks)
    }
}

/** Sheet body, separate from the popup window so it can be rendered and tested in place. */
@Composable
internal fun ShareSheetBody(links: LinkViewModel, language: String, hooks: ShareSheetHooks) {
    val pane = stringResource(R.string.share_sheet_pane)
    // The done view keeps the last height, so the sheet does not jump in the half second before it closes.
    var height by remember { mutableIntStateOf(0) }
    val minHeight = with(LocalDensity.current) { height.toDp() }
    Box(Modifier.fillMaxWidth().semantics { paneTitle = pane }.onSizeChanged { if (!hooks.sent) height = it.height }) {
        when {
            hooks.sent && links.optionsState == OptionsState.Hidden -> StartedBody(minHeight)
            links.optionsState != OptionsState.Hidden -> OptionsSheetBody(links, language, hooks.onFinish, hooks)
            else -> PreviewBody(links, language, hooks)
        }
    }
}

/** Options not loaded yet (Load options automatically is off), or the share held no link. Nothing touches the network. */
@Composable
private fun PreviewBody(links: LinkViewModel, language: String, hooks: ShareSheetHooks) = Column {
    val host = LinkInput.parse(links.text)?.let { URI(it.rawUrl).host }
    Column(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (host == null) {
            Text(stringResource(R.string.invalid_link), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = hooks.onFinish, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        } else {
            Text(stringResource(R.string.shared_video), Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
            key(links.text) { InspectionHeader(null, host, checking = false, hint = links.shareHint) }
            links.savedLink?.let { SavedBeforeNotice(it, null, language, onOpened = hooks.onFinish) }
            Button(onClick = links::checkLink, modifier = Modifier.fillMaxWidth().padding(top = Space.s).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.check_link))
            }
        }
    }
    OpenInNezzelBelow(hooks)
}

@Composable
private fun OpenInNezzelBelow(hooks: ShareSheetHooks) = Column(Modifier.padding(top = Space.s)) { OpenInNezzel(hooks.onOpenApp) }

/** After the coordinator took the job: the same check the button showed, until the sheet closes. */
@Composable
private fun StartedBody(minHeight: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(Space.l).testTag("share_started_body"),
        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text(stringResource(R.string.share_sheet_started), style = MaterialTheme.typography.titleMedium)
    }
}
