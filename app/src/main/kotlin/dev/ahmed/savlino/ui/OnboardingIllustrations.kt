package dev.ahmed.savlino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.rememberReducedMotion

/** Static examples, complete from their first frame. No intake, results or job progress. */
@Composable
internal fun TourIllustration(page: Int, description: String, modifier: Modifier, active: Boolean = false) {
    Surface(modifier.clearAndSetSemantics { contentDescription = description; role = Role.Image },
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        val colors = MaterialTheme.colorScheme
        val dark = colors.surface.luminance() < 0.5f
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            // The first page shows where links come from; the rest share a soft brand tint from the top start corner
            if (page == 0) {
                SiteLogoWall(tile = 64.dp)
                Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(colors.surfaceContainerLow.copy(alpha = 0.85f),
                    colors.surfaceContainerLow.copy(alpha = if (dark) 0.5f else 0.3f)))))
            } else Box(Modifier.matchParentSize().background(Brush.linearGradient(
                listOf(colors.primaryContainer.copy(alpha = if (dark) 0.35f else 0.55f), Color.Transparent))))
            // Only decorative examples scale to fit. Instructions outside the art keep normal font scaling.
            val stage = 320.dp
            val scale = minOf(maxWidth / stage, maxHeight / stage, 1f)
            Box(Modifier.requiredSize(stage).graphicsLayer { scaleX = scale; scaleY = scale },
                contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> MediaIllustration()
                    1 -> CopyIllustration()
                    2 -> OptionsIllustration()
                    else -> SavedIllustration(active)
                }
            }
        }
    }
}

@Composable
private fun TourGlyph(glyph: Int, modifier: Modifier = Modifier) {
    Icon(painterResource(glyph), null, modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun NezzelMark(modifier: Modifier = Modifier, ring: Dp = 0.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer, glyph: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Surface(modifier, shape = CircleShape, color = container,
        border = if (ring > 0.dp) BorderStroke(ring, MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)) else null) {
        Icon(painterResource(R.drawable.nezzel_launcher_foreground), null,
            Modifier.fillMaxSize(), tint = glyph)
    }
}

@Composable
private fun MediaIllustration() {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(36.dp)) {
        NezzelMark(Modifier.size(112.dp).testTag("tour_mark"), ring = 6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            listOf(R.drawable.ic_movie to "tour_video", R.drawable.ic_music to "tour_audio",
                R.drawable.ic_image to "tour_photo").forEach { (glyph, tag) ->
                Surface(Modifier.size(56.dp).testTag(tag), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Box(contentAlignment = Alignment.Center) { TourGlyph(glyph) }
                }
            }
        }
    }
}

/** A small piece of the real interface, floating on the tinted card. */
@Composable
private fun SnapshotCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.width(280.dp), shape = RoundedCornerShape(20.dp),
        color = if (colors.surface.luminance() < 0.5f) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f))) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s), content = content)
    }
}

/** A grey bar standing in for a title we don't invent. */
@Composable
private fun Placeholder(width: Dp, height: Dp = 10.dp) {
    Box(Modifier.width(width).height(height).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape))
}

@Composable
private fun CopyIllustration() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SnapshotCard {
            listOf(Triple(SiteLogos.youtube, R.string.onboarding_copy_action, true),
                Triple(SiteLogos.tiktok, R.string.onboarding_share_action, false)).forEachIndexed { index, (logo, label, copy) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    LogoTile(logo, 40.dp)
                    if (copy) TourGlyph(R.drawable.ic_paste, Modifier.size(20.dp))
                    else Icon(Icons.Filled.Share, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    NezzelMark(Modifier.size(36.dp))
                }
            }
        }
        // The link field as Home draws it: a pill with the link kept left to right
        Surface(Modifier.width(280.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                TourGlyph(R.drawable.ic_link, Modifier.size(20.dp))
                Text(stringResource(R.string.onboarding_example_url), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
}

@Composable
private fun OptionsIllustration() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(stringResource(R.string.onboarding_example), Modifier.padding(horizontal = Space.m, vertical = Space.xs),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        // Real tier names and hints, never a size: the example must not invent numbers
        SnapshotCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LogoTile(SiteLogos.youtube, 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Placeholder(140.dp); Placeholder(90.dp, 8.dp) }
            }
            OptionRow(stringResource(R.string.quality_title, stringResource(R.string.quality_full_hd), "1080p"),
                stringResource(R.string.quality_full_hd_hint), selected = true)
            OptionRow(stringResource(R.string.quality_title, stringResource(R.string.quality_hd), "720p"),
                stringResource(R.string.quality_hd_hint), selected = false)
            OptionRow(stringResource(R.string.audio_only), null, selected = false)
        }
    }
}

@Composable
private fun OptionRow(title: String, hint: String?, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(if (selected) colors.primaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
        .padding(horizontal = Space.s, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(Modifier.size(20.dp).then(if (selected) Modifier.background(colors.primary, CircleShape)
            else Modifier.border(2.dp, colors.outline, CircleShape)), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SavedIllustration(active: Boolean) {
    // The check is drawn from the first frame and pops once each time this page settles
    val reduced = rememberReducedMotion()
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active, reduced) {
        if (active && !reduced) { pop.snapTo(0.5f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)) }
    }
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        SnapshotCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LogoTile(SiteLogos.tiktok, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Placeholder(120.dp)
                    Text(stringResource(R.string.download_saved), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                Surface(Modifier.size(36.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, shape = CircleShape, color = colors.primary) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(22.dp).testTag("tour_saved_check"), tint = colors.onPrimary)
                    }
                }
            }
        }
        // The Downloads tab as the navigation bar shows it when selected
        Surface(shape = CircleShape, color = colors.secondaryContainer) {
            Row(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp).testTag("tour_downloads"),
                    tint = colors.onSecondaryContainer)
                Text(stringResource(R.string.downloads), style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
            }
        }
    }
}
