package dev.ahmed.savlino.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * The bottom stack as one family (owner 2026-10-27, Home stack B): the paste field, the music bar and the tabs share
 * one shape, colour, shadow and width. A fixed radius rather than a circle, so tabs grown by large text keep their labels.
 */
object Dock {
    val CORNER = 32.dp
    val SHAPE = RoundedCornerShape(CORNER)
    val SHADOW = 1.dp
    /** From the screen edge, the same for all three. */
    val INSET = Space.m
    /** Between two of them. */
    val GAP = Space.s
    val color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
}
