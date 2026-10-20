package dev.ahmed.savlino

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut

/**
 * One pill input: paste sits inside it while it is empty, clear while it has text. Intake may inspect;
 * Download stays in the options sheet. A copied link is offered as a chip, never applied by itself.
 */
@Composable
internal fun HomeLinkCard(links: LinkViewModel, copiedHost: String? = null, onUseCopied: () -> Unit = {},
                          onDismissCopied: () -> Unit = {}, onPaste: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = LocalReducedMotion.current
    val check = { focus.clearFocus(); keyboard?.hide(); links.checkLink() }
    val paste = { focus.clearFocus(); keyboard?.hide(); onPaste() }
    DisposableEffect(links) { onDispose { links.editorFocused = false } }
    // The placeholder disappears once there is text; this name stays for TalkBack. Entry itself stays LTR.
    val fieldLabel = stringResource(R.string.link_field_label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        OutlinedTextField(value = links.text, onValueChange = links::edit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).onFocusChanged { links.editorFocused = it.isFocused }
                .semantics { contentDescription = fieldLabel },
            shape = CircleShape,
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), null, Modifier.padding(start = Space.xs)) },
            trailingIcon = {
                if (links.text.isEmpty()) {
                    FilledIconButton(onClick = paste, modifier = Modifier.padding(end = Space.xs).size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary)) {
                        Icon(painterResource(R.drawable.ic_paste), stringResource(R.string.paste_link), Modifier.size(20.dp))
                    }
                } else IconButton(onClick = { links.edit("") }, modifier = Modifier.padding(end = Space.xs)) {
                    Icon(Icons.Default.Close, stringResource(R.string.clear_link))
                }
            },
            placeholder = { Text(stringResource(R.string.link_field_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (links.text.isNotEmpty()) check() }),
            singleLine = true, isError = links.invalid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline),
            supportingText = if (links.invalid) { { Text(stringResource(R.string.invalid_link),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } } else null)
        // Keep the last host so the chip still has its text while it fades out.
        var shownHost by remember { mutableStateOf(copiedHost) }
        if (copiedHost != null) shownHost = copiedHost
        AnimatedVisibility(links.text.isEmpty() && copiedHost != null,
            enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + expandVertically(easeOut(reduced)),
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT)) + shrinkVertically(easeOut(reduced))) {
            shownHost?.let { host -> CopiedChip(host, onUseCopied, onDismissCopied) }
        }
        // Fade only: the action keeps its full 56dp from the first frame, so it is never a sliver to tap.
        AnimatedVisibility(links.text.isNotEmpty(),
            enter = fadeIn(easeOut(reduced, Motion.FADE_IN)), exit = fadeOut(easeOut(reduced, Motion.FADE_OUT))) {
            Button(onClick = check, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.check_link))
            }
        }
    }
}

/** Shows only the host the person copied (URL stays private); tapping it is the explicit acceptance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopiedChip(host: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        // 40dp pill; M3 still reserves the 48dp touch target around it.
        Surface(onClick = onUse, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 40.dp)) {
            Row(Modifier.padding(start = Space.m, end = Space.l, top = Space.s, bottom = Space.s),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(painterResource(R.drawable.ic_paste), null, Modifier.size(18.dp))
                // Label and host wrap as whole pieces at large text instead of squeezing the host to nothing.
                FlowRow(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(stringResource(R.string.copied_chip), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(stringResource(R.string.separator).trim(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
                        // The host is its own LTR run so a long domain never reorders the Arabic label.
                        Text(NezzelFormat.isolate(host), style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.not_now), Modifier.size(20.dp)) }
    }
}
