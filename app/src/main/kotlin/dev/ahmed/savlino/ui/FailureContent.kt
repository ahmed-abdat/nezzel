package dev.ahmed.savlino.ui

import android.content.ClipboardManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.entry.plainText
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

/**
 * One failure layout for video and image sheets: an error tile in place of the preview skeleton (nothing
 * is loading any more), the truthful title/body for the failure type, then the next step. Try again only
 * when trying again can help; "Paste another link" whenever the link itself is the problem; [secondary] (an engine
 * update) sits beside Close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailureContent(@StringRes title: Int, @StringRes body: Int?, host: String?, onRetry: (() -> Unit)?,
    onPasteAnother: ((String?) -> Unit)?, onDismiss: () -> Unit, secondary: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    Column(Modifier.testTag("failure_content"), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(Space.inline), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                host?.let {
                    Text(NezzelFormat.isolate(it), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        // Main actions share one row; the quieter ones (engine update, Close) share the row below (owner 2026-10-21).
        val paste = onPasteAnother?.let { pasteAnother ->
            // Explicit tap while Nezzel's sheet has focus: the only time the clipboard is read here.
            { pasteAnother(context.getSystemService(ClipboardManager::class.java)?.plainText()) }
        }
        if (onRetry != null || paste != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val main = Modifier.weight(1f).heightIn(min = 48.dp)
            if (onRetry != null) Button(onClick = onRetry, modifier = main) { ActionLabel(R.string.try_again) }
            if (paste != null) {
                if (onRetry == null) Button(onClick = paste, modifier = main) { ActionLabel(R.string.paste_another_link) }
                else OutlinedButton(onClick = paste, modifier = main) { ActionLabel(R.string.paste_another_link) }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            secondary?.invoke()
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        }
    }
}

@Composable
private fun ActionLabel(@StringRes text: Int) = Text(stringResource(text), textAlign = TextAlign.Center)
