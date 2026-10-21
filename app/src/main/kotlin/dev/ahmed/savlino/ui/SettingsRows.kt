package dev.ahmed.savlino.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText

/** Rows of one group share one continuous rounded surface: one container level, no tiles, no dividers. */
private val GroupShape = RoundedCornerShape(20.dp)

@Composable
internal fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        title?.let {
            Text(it, Modifier.padding(horizontal = Space.l).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(MaterialTheme.colorScheme.surfaceContainer), content = content)
    }
}

/** A plain 24dp glyph, muted by default; teal is kept for switches and the one main action. */
@Composable
internal fun TintedIcon(@DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
}

/**
 * One row inside a [SettingsGroup]. [modifier] carries the click or toggle so its ripple covers the whole row.
 * [note] is a second supporting line (a status or an error) that can be announced politely.
 */
@Composable
internal fun SettingsRow(title: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, body: String? = null,
    note: String? = null, noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, liveNote: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, titleColor: Color = Color.Unspecified,
    trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp)
        .padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        // Large text needs the width more than the decoration does.
        if (!isLargeText()) icon?.let { TintedIcon(it, tint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            note?.let {
                Text(it, Modifier.semantics { if (liveNote) liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = noteColor)
            }
        }
        trailing?.invoke(this)
    }
}

/** A row that opens something: optional current [value], then a mirrored chevron. */
@Composable
internal fun SettingsNavRow(title: String, @DrawableRes icon: Int?, body: String? = null, value: String? = null,
    note: String? = null, noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, liveNote: Boolean = false,
    enabled: Boolean = true, chevron: Boolean = true, onClick: () -> Unit) {
    SettingsRow(title, Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick), icon, body, note, noteColor, liveNote) {
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The whole row is one Switch target, so tapping the description toggles too; the switch itself only draws. */
@Composable
internal fun SwitchSetting(title: String, body: String?, checked: Boolean, @DrawableRes icon: Int? = null, onChange: (Boolean) -> Unit) {
    SettingsRow(title, Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange), icon, body) {
        Switch(checked, onCheckedChange = null)
    }
}

/** Language and theme pickers: choosing applies at once and closes, like a system single-choice dialog. */
@Composable
internal fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                        .selectable(value == selected, role = Role.RadioButton) { onDismiss(); if (value != selected) onSelect(value) }
                        .padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        RadioButton(value == selected, onClick = null)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        })
}
