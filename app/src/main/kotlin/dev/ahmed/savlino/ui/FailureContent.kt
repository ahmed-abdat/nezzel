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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
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
 * update) and the optional report action sit beside Close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailureContent(@StringRes title: Int, @StringRes body: Int?, host: String?, onRetry: (() -> Unit)?,
    onPasteAnother: ((String?) -> Unit)?, onDismiss: () -> Unit, secondary: (@Composable () -> Unit)? = null,
    onReport: (() -> Unit)? = null) {
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
        if (onRetry != null || paste != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Two equal halves only while every label fits on one line in its half (phone: "Paste another link" wrapped
            // and grew taller than Try again); larger text or a longer translation stacks them at full width instead.
            val labels = listOfNotNull(onRetry?.let { stringResource(R.string.try_again) },
                paste?.let { stringResource(R.string.paste_another_link) })
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge
            val oneRow = with(LocalDensity.current) {
                val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp()
                widest + ActionPadding * 2 <= (maxWidth - Space.s * (labels.size - 1)) / labels.size
            }
            val main = Modifier.heightIn(min = 48.dp)
            val actions = @Composable { modifier: Modifier ->
                if (onRetry != null) Button(onClick = onRetry, modifier = modifier.then(main), contentPadding = ActionPaddingValues) {
                    ActionLabel(R.string.try_again)
                }
                if (paste != null) {
                    if (onRetry == null) Button(onClick = paste, modifier = modifier.then(main), contentPadding = ActionPaddingValues) {
                        ActionLabel(R.string.paste_another_link)
                    } else OutlinedButton(onClick = paste, modifier = modifier.then(main), contentPadding = ActionPaddingValues) {
                        ActionLabel(R.string.paste_another_link)
                    }
                }
            }
            if (oneRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) { actions(Modifier.weight(1f)) }
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) { actions(Modifier.fillMaxWidth()) }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            secondary?.invoke()
            if (onReport != null) TextButton(onClick = onReport, modifier = Modifier.heightIn(min = 48.dp)) {
                // WhatsApp's own mark in its brand green (Simple Icons, decorative): the button opens WhatsApp.
                Icon(painterResource(R.drawable.logo_whatsapp), contentDescription = null, tint = Color(0xFF25D366),
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(R.string.report_link))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        }
    }
}

// 16dp sides (Material's 24dp left "Paste another link" ~130dp in a half-width button on a 411dp phone).
private val ActionPadding = Space.l
private val ActionPaddingValues = PaddingValues(horizontal = ActionPadding, vertical = Space.s)

@Composable
private fun ActionLabel(@StringRes text: Int) = Text(stringResource(text), textAlign = TextAlign.Center)
