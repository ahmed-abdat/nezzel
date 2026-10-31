package dev.ahmed.savlino

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.ahmed.savlino.ui.theme.Dock
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.easeOut
import dev.ahmed.savlino.ui.theme.motionSpec

/**
 * Home's dock, in thumb reach above the tab bar: the copied-link card, then one pill input with Paste inside it
 * while it is empty and clear while it has text. Intake may inspect; Download stays in the options sheet.
 * A copied link is offered as a card, never applied by itself.
 */
@Composable
internal fun HomeLinkCard(links: LinkViewModel, copiedHost: String? = null, onUseCopied: () -> Unit = {},
                          onDismissCopied: () -> Unit = {}, onPaste: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val check = { focus.clearFocus(); keyboard?.hide(); links.checkLink() }
    // Words that aren't a link become a YouTube search; a link keeps View options.
    val words = links.searchWords
    val search = { focus.clearFocus(); keyboard?.hide(); links.openSearch() }
    val paste = { focus.clearFocus(); keyboard?.hide(); onPaste() }
    DisposableEffect(links) { onDispose { links.editorFocused = false } }
    // The placeholder disappears once there is text; this name stays for TalkBack. Entry itself stays LTR.
    val fieldLabel = stringResource(if (words != null) R.string.search_field_label else R.string.link_field_label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // Keep the last host so the card still has its text while it fades out.
        var shownHost by remember { mutableStateOf(copiedHost) }
        if (copiedHost != null) shownHost = copiedHost
        // Fade and rise only: no animated height, the dock settles in one layout pass.
        AnimatedVisibility(links.text.isEmpty() && copiedHost != null,
            enter = fadeIn(easeOut(reduced, Motion.FADE_IN)) + slideInVertically(easeOut(reduced)) { it / 3 },
            exit = fadeOut(easeOut(reduced, Motion.FADE_OUT))) {
            shownHost?.let { host -> CopiedChip(host, onUseCopied, onDismissCopied) }
        }
        OutlinedTextField(value = links.text, onValueChange = links::edit,
            // Same shape as the dock, and flat: the tab bar below is the one that casts a shadow.
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .onFocusChanged { links.editorFocused = it.isFocused }.semantics { contentDescription = fieldLabel },
            shape = Dock.SHAPE,
            leadingIcon = {
                if (words != null) Icon(Icons.Default.Search, null, Modifier.padding(start = Space.s))
                else Icon(painterResource(R.drawable.ic_link), null, Modifier.padding(start = Space.s))
            },
            trailingIcon = {
                if (links.text.isEmpty()) PasteButton(paste, Modifier.padding(end = Space.s))
                else IconButton(onClick = { links.edit("") }, modifier = Modifier.padding(end = Space.xs)) {
                    Icon(Icons.Default.Close, stringResource(R.string.clear_link))
                }
            },
            placeholder = { Text(stringResource(R.string.link_field_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            // A link starts with a Latin letter, so it stays LTR; Arabic words read RTL.
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (words != null) search() else if (links.text.isNotEmpty()) check() }),
            singleLine = true, isError = links.invalid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Dock.color,
                unfocusedContainerColor = Dock.color,
                errorContainerColor = Dock.color,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = Color.Transparent,
                unfocusedLeadingIconColor = colors.onSurfaceVariant))
        // Under the field, so the error does not sit inside the pill.
        if (links.invalid) Text(stringResource(R.string.invalid_link), Modifier.padding(horizontal = Space.l)
            .semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = MaterialTheme.typography.bodySmall)
        // Fade only: the action keeps its full 56dp from the first frame, so it is never a sliver to tap.
        AnimatedVisibility(links.text.isNotEmpty(),
            enter = fadeIn(easeOut(reduced, Motion.FADE_IN)), exit = fadeOut(easeOut(reduced, Motion.FADE_OUT))) {
            if (words != null) Button(onClick = search, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                // The words are their own first-strong run, so Arabic words in the English label (or the reverse) keep their order.
                Text(stringResource(R.string.search_youtube_for, NezzelFormat.firstStrong(words)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Button(onClick = check, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.check_link))
            }
        }
    }
}

/** The dock's one strong action. It dips to 96% while pressed (a spring read in the draw phase), not a ripple alone. */
@Composable
private fun PasteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .96f else 1f, motionSpec(LocalReducedMotion.current, Motion.Press), label = "press")
    val label = stringResource(R.string.paste_link)
    Button(onClick = onClick, interactionSource = interaction, shape = CircleShape,
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .semantics { contentDescription = label },
        contentPadding = PaddingValues(start = Space.l, end = Space.l + Space.xs)) {
        Icon(painterResource(R.drawable.ic_paste), null, Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
        Text(stringResource(R.string.paste), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Shows only the host the person copied (URL stays private); tapping it is the explicit acceptance. */
@Composable
private fun CopiedChip(host: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onUse, shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(start = Space.m, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_link), null, Modifier.size(20.dp), tint = colors.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.m, vertical = Space.s)) {
                Text(stringResource(R.string.copied_chip), style = MaterialTheme.typography.titleSmall)
                // The host is its own LTR run so a long domain never reorders the Arabic label.
                Text(NezzelFormat.isolate(host), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    color = colors.onSecondaryContainer.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.not_now), Modifier.size(20.dp)) }
        }
    }
}
