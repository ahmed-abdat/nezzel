package dev.ahmed.savlino.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/** M3 4/8dp grid. Call sites use the role names below where one fits, raw steps otherwise. */
object Space {
    val xxs = 2.dp // text pair inside a dense row only
    val xs = 4.dp; val s = 8.dp; val m = 12.dp
    val l = 16.dp; val xl = 24.dp; val xxl = 32.dp

    val gutter = l            // screen margin, compact width
    val item = s              // between list items
    val rowH = l              // list row / container horizontal padding
    val inline = m            // tile, thumbnail or radio ↔ text
    val textPair = xxs        // title ↔ supporting line in a row
}

/** Widens a list item by the list's gutter on both sides, so a scrolling row runs to the screen edges. */
fun Modifier.bleedGutter() = layout { measurable, constraints ->
    val extra = (Space.gutter * 2).roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
    val placeable = measurable.measure(wide)
    layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width, placeable.height) { placeable.place(-extra / 2, 0) }
}
