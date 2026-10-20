package dev.ahmed.savlino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.theme.Space

/** Static examples, complete from their first frame. No intake, results or job progress. */
@Composable
internal fun TourIllustration(page: Int, description: String, modifier: Modifier) {
    Surface(modifier.clearAndSetSemantics { contentDescription = description; role = Role.Image },
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            // Only decorative examples scale to fit. Instructions outside the art keep normal font scaling.
            val stage = 320.dp
            val scale = minOf(maxWidth / stage, maxHeight / stage, 1f)
            Box(Modifier.requiredSize(stage).graphicsLayer { scaleX = scale; scaleY = scale },
                contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> MediaIllustration()
                    1 -> CopyIllustration()
                    2 -> OptionsIllustration()
                    else -> SavedIllustration()
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
internal fun NezzelMark(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(painterResource(R.drawable.nezzel_launcher_foreground), null,
            Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun MediaIllustration() {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(36.dp)) {
        NezzelMark(Modifier.size(104.dp).testTag("tour_mark"))
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

@Composable
private fun CopyIllustration() {
    Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        listOf(R.string.onboarding_copy_action, R.string.onboarding_share_action).forEachIndexed { index, label ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (index == 0) TourGlyph(R.drawable.ic_paste)
                else Icon(Icons.Filled.Share, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                NezzelMark(Modifier.size(48.dp))
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                TourGlyph(R.drawable.ic_link)
                Text(stringResource(R.string.onboarding_example_url), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
}

@Composable
private fun OptionsIllustration() {
    Column(Modifier.width(280.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Text(stringResource(R.string.onboarding_example), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A single preset and a separate photo example; no invented size or simulated media tabs.
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                TourGlyph(R.drawable.ic_movie)
                Text(stringResource(R.string.quality_full_hd), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                TourGlyph(R.drawable.ic_image, Modifier.size(40.dp))
                Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SavedIllustration() {
    Column(Modifier.width(280.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                TourGlyph(R.drawable.ic_movie)
                Text(stringResource(R.string.download_saved), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Icon(Icons.Filled.Check, null, Modifier.size(32.dp).testTag("tour_saved_check"),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        TourGlyph(R.drawable.ic_download, Modifier.size(48.dp).testTag("tour_downloads"))
        Text(stringResource(R.string.downloads), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Decorative static update emblem; offsets follow start/end in RTL. */
@Composable
internal fun UpdateIllustration(compact: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (!compact) {
            listOf(Triple(R.drawable.ic_movie, (-72).dp, (-40).dp),
                Triple(R.drawable.ic_music, 72.dp, (-40).dp),
                Triple(R.drawable.ic_image, 72.dp, 40.dp)).forEach { (glyph, x, y) ->
                Surface(Modifier.offset(x = x, y = y).size(40.dp).alpha(0.6f), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(glyph), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Box(Modifier.size(if (compact) 56.dp else 88.dp)) {
            NezzelMark(Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.BottomEnd).size(32.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
