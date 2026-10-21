package dev.ahmed.savlino.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.SiteLogoArc
import dev.ahmed.savlino.ui.SiteLogoWall

/** A tilted wall of the sites Nezzel saves from, fading into the page, under the Nezzel mark. Purely decorative. */
@Composable
internal fun UpdateHero(compact: Boolean, top: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    BoxWithConstraints(modifier.clipToBounds().background(colors.background).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        // Tiles only around the mark and below the title; the page colour shows through the middle
        if (compact) SiteLogoWall(Modifier.offset(y = top / 2))
        else SiteLogoArc(maxWidth, maxHeight, markShift = top / 2, topClear = top)
        if (dark) Box(Modifier.matchParentSize().background(colors.background.copy(alpha = 0.25f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to colors.background)))
        Box(Modifier.padding(top = top).size(if (compact) 56.dp else 104.dp)) {
            // A surface ring lifts the mark off the wall without relying on elevation shadows
            // Light mode tints the disc: a white one disappears into the page now the middle is clear
            val disc = if (dark) colors.surfaceContainerHigh else colors.primaryContainer
            Surface(Modifier.fillMaxSize(), shape = CircleShape, color = disc,
                border = BorderStroke(if (compact) 3.dp else 6.dp, disc.copy(alpha = 0.6f))) {
                Icon(painterResource(R.drawable.nezzel_launcher_foreground), null,
                    Modifier.fillMaxSize(), tint = colors.primary)
            }
            Surface(Modifier.align(Alignment.BottomEnd).size(if (compact) 24.dp else 34.dp), shape = CircleShape, color = colors.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(if (compact) 16.dp else 20.dp),
                        tint = colors.onPrimary)
                }
            }
        }
    }
}
