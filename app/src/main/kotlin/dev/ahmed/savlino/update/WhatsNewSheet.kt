package dev.ahmed.savlino.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.NezzelMark
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewSheet(whatsNew: WhatsNew, language: String, update: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        Column(Modifier.fillMaxWidth().testTag("whats_new_sheet").verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                NezzelMark(Modifier.size(48.dp))
                Text(stringResource(R.string.whats_new_title),
                    Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            val notes = whatsNew.notes(language)
            notes.forEach { NoteBullet(it, large = true) }
            if (update != null) {
                Button(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { update() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_action)) }
                TextButton(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_later)) }
            } else {
                Button(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.whats_new_ok)) }
            }
        }
    }
}

/** Onboarding owns a separate composition. All Home modals take priority over update prompts. */
@Composable
internal fun AppUpdateSheets(updates: AppUpdates, state: AppUpdateState, links: dev.ahmed.savlino.ui.LinkViewModel,
    language: String, notificationRationaleShowing: Boolean) {
    if (links.destination != "Home" || links.optionsState != dev.ahmed.savlino.ui.OptionsState.Hidden ||
        links.moreFormats || links.hasPendingShare || links.hasPendingNotificationChoice ||
        links.confirmPausedUpdate || notificationRationaleShowing) return
    val installed = state.whatsNew
    val offered = state.offerSheet
    if (installed != null) WhatsNewSheet(installed, language, onDismiss = updates::dismissWhatsNew)
    else if (offered != null) {
        LaunchedEffect(offered.code) { updates.offerSheetShown(offered.code) }
        WhatsNewSheet(WhatsNew(offered.version, offered.english, offered.arabic, offered.french), language,
            update = { updates.dismissOfferSheet(); links.navigate("AppUpdate") }, onDismiss = updates::dismissOfferSheet)
    }
}

@Composable
internal fun NoteBullet(note: String, large: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
        Text("•", style = style)
        Text(note, Modifier.weight(1f), style = style)
    }
}
