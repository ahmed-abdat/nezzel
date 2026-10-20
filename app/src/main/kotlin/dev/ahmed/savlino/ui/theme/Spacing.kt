package dev.ahmed.savlino.ui.theme

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
