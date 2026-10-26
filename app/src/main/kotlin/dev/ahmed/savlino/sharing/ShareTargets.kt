package dev.ahmed.savlino.sharing

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.TintedIcon
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the "Saved" snackbar offers once a run of downloads ends. */
sealed interface CompletionAction {
    /** Several files: sharing only the last one would look like sharing the batch. */
    data object ViewSaved : CompletionAction
    /** One file: straight to the only WhatsApp, a choice between both, or the chooser. */
    data class Share(val targets: List<ShareTarget>) : CompletionAction
}

fun completionAction(runSize: Int, targets: List<ShareTarget>): CompletionAction =
    if (runSize > 1) CompletionAction.ViewSaved else CompletionAction.Share(targets)

/** One row per installed WhatsApp with the phone's own icon and name, then Other apps (the Android chooser). */
@Composable
fun ShareTargetRows(targets: List<ShareTarget>, onPick: (ShareTarget?) -> Unit) {
    targets.forEach { target -> ShareRow({ AppIcon(target.packageName) }, target.label) { onPick(target) } }
    ShareRow({ TintedIcon(R.drawable.ic_ms_share) }, stringResource(R.string.share_other_apps)) { onPick(null) }
}

/** WhatsApp, WhatsApp Business and Other apps in a sheet, for a share started outside a file's own sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetsSheet(targets: List<ShareTarget>, onPick: (ShareTarget?) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.s)) {
            Text(stringResource(R.string.share_file), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Space.xl).padding(bottom = Space.s))
            ShareTargetRows(targets) { target ->
                onPick(target)
                scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

@Composable
private fun ShareRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.xl, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The installed app's launcher icon, read off the main thread; decorative, the label names the app. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val size = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    icon?.let { Image(it, null, Modifier.size(24.dp)) } ?: TintedIcon(R.drawable.ic_ms_share)
}
