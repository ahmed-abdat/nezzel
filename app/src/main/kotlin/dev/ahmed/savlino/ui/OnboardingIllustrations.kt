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
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import dev.ahmed.savlino.ui.nav.rememberTabLoop
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
        // Every page floats its drawing on the page itself, like Home: the snapshots are cards already, no card around them
        color = Color.Transparent) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            // The first page shows where links come from
            if (page == 0) {
                // Same turning rings as Home and App update, sized to the space so every site shows; the media row sits below
                Column(Modifier.fillMaxSize().padding(bottom = Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        SiteLogoArc(maxWidth, maxHeight, tile = 36.dp, mark = 88.dp)
                        NezzelMark(Modifier.size(88.dp).testTag("tour_mark"), ring = 5.dp)
                    }
                    MediaKinds()
                }
                return@BoxWithConstraints
            }
            // Only decorative examples scale to fit. Instructions outside the art keep normal font scaling.
            val stage = 320.dp
            val scale = minOf(maxWidth / stage, maxHeight / stage, 1f)
            Box(Modifier.requiredSize(stage).graphicsLayer { scaleX = scale; scaleY = scale },
                contentAlignment = Alignment.Center) {
                when (page) {
                    1 -> CopyIllustration(active)
                    2 -> OptionsIllustration(active)
                    3 -> SavedIllustration(active)
                    else -> StatusesIllustration(active)
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
private fun MediaKinds() {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        listOf(R.drawable.ic_movie to "tour_video", R.drawable.ic_music to "tour_audio",
            R.drawable.ic_image to "tour_photo").forEach { (glyph, tag) ->
            Surface(Modifier.size(52.dp).testTag(tag), shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Box(contentAlignment = Alignment.Center) { TourGlyph(glyph) }
            }
        }
    }
}

/** A small piece of the real interface, floating on the page. */
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
private fun CopyIllustration(active: Boolean) {
    // The link types into the field, then each row sends it on: its arrow nudges toward the mark, which answers
    val story = rememberStory(active, 5200)
    val toEnd = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1f else 1f
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
                    val beat = { val p = story.value; kotlin.math.sin(Math.PI * if (index == 0) span(p, 0.32f, 0.5f) else span(p, 0.56f, 0.74f)).toFloat() }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp).graphicsLayer {
                        translationX = beat() * 6.dp.toPx() * toEnd },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    NezzelMark(Modifier.size(36.dp).graphicsLayer { val b = 1f + 0.14f * beat(); scaleX = b; scaleY = b })
                }
            }
        }
        // The link field as Home draws it: a pill with the link kept left to right
        Surface(Modifier.width(280.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                TourGlyph(R.drawable.ic_link, Modifier.size(20.dp))
                Text(stringResource(R.string.onboarding_example_url), Modifier.weight(1f).drawWithContent {
                        val p = story.value
                        val typed = outCubic(span(p, 0.04f, 0.3f))
                        clipRect(right = size.width * typed) { this@drawWithContent.drawContent() }
                    }.graphicsLayer { alpha = 1f - span(story.value, 0.92f, 1f) },
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
}

@Composable
private fun OptionsIllustration(active: Boolean) {
    // The choice steps up from Audio only to HD to Full HD, so it reads as yours to pick
    val story = rememberStory(active, 6000)
    val picked by remember { derivedStateOf { story.value.let { if (it < 0.3f) 2 else if (it < 0.55f) 1 else 0 } } }
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
                stringResource(R.string.quality_full_hd_hint), selected = picked == 0)
            OptionRow(stringResource(R.string.quality_title, stringResource(R.string.quality_hd), "720p"),
                stringResource(R.string.quality_hd_hint), selected = picked == 1)
            OptionRow(stringResource(R.string.audio_only), null, selected = picked == 2)
        }
    }
}

@Composable
private fun OptionRow(title: String, hint: String?, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f), tween(280), label = "option")
    Row(Modifier.fillMaxWidth().background(fill, RoundedCornerShape(12.dp))
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

/**
 * One slow story per page, played only while that page is settled with animations on. Every other time it holds at
 * [DONE], the finished picture, so each page is complete from its first frame. Each story fades its last beat out
 * before it loops, so the restart never jumps.
 */
@Composable
private fun rememberStory(active: Boolean, millis: Int): State<Float> {
    // The loop starts from the finished picture the page arrived with, so settling on a page never jumps to an empty frame
    val loop = rememberTabLoop(0f, 1f, tween(millis, easing = LinearEasing), rest = 0f, enabled = active && !rememberReducedMotion())
    return remember(loop) { derivedStateOf { (loop.value + DONE) % 1f } }
}

private const val DONE = 0.8f
private fun span(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)
private fun outCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
/** Ease out with a small overshoot, for the checks landing. */
private fun outBack(t: Float): Float { val u = t - 1f; return 1f + 2.70158f * u * u * u + 1.70158f * u * u }

/** A tab pill as the navigation bar draws it when selected. */
@Composable
private fun TabPill(glyph: Int, label: Int, tag: String, lift: () -> Float = { 0f }) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.graphicsLayer { translationY = -lift() * 6.dp.toPx() }, shape = CircleShape, color = colors.secondaryContainer) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(painterResource(glyph), null, Modifier.size(20.dp).testTag(tag), tint = colors.onSecondaryContainer)
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
        }
    }
}

@Composable
private fun SavedIllustration(active: Boolean) {
    // A ring fills around the file, the check lands, then the Downloads tab nods: that is where it went
    val story = rememberStory(active, 4800)
    val gone = { 1f - span(story.value, 0.92f, 1f) }
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        SnapshotCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LogoTile(SiteLogos.tiktok, 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Placeholder(120.dp)
                    Text(stringResource(R.string.download_saved), Modifier.graphicsLayer { alpha = span(story.value, 0.46f, 0.6f) * gone() },
                        style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                val ring = colors.primary
                val track = colors.outlineVariant
                Box(Modifier.size(44.dp).drawBehind {
                    val p = story.value
                    val fade = 1f - span(p, 0.5f, 0.62f)
                    if (fade > 0f) {
                        val stroke = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        val inset = 2.dp.toPx()
                        val arc = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                        val at = androidx.compose.ui.geometry.Offset(inset, inset)
                        drawArc(track, 0f, 360f, false, at, arc, alpha = fade, style = stroke)
                        drawArc(ring, -90f, 360f * outCubic(span(p, 0.04f, 0.44f)), false, at, arc, alpha = fade, style = stroke)
                    }
                }, contentAlignment = Alignment.Center) {
                    Surface(Modifier.size(36.dp).graphicsLayer {
                        val s = outBack(span(story.value, 0.44f, 0.6f)); scaleX = s; scaleY = s; alpha = gone() }, shape = CircleShape, color = colors.primary) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, Modifier.size(22.dp).testTag("tour_saved_check"), tint = colors.onPrimary)
                        }
                    }
                }
            }
        }
        TabPill(R.drawable.ic_download, R.string.downloads, "tour_downloads") {
            kotlin.math.sin(Math.PI * span(story.value, 0.62f, 0.76f)).toFloat() }
    }
}

@Composable
private fun StatusesIllustration(active: Boolean) {
    // The newest statuses come in one by one, then the one you keep gets its check
    val story = rememberStory(active, 5200)
    val gone = { 1f - span(story.value, 0.92f, 1f) }
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        // One account as the Statuses tab lists it: the app, then its newest statuses; no invented names or pictures
        SnapshotCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                LogoTile(SiteLogos.whatsapp, 40.dp, Modifier.testTag("tour_whatsapp"))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Placeholder(110.dp); Placeholder(70.dp, 8.dp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(colors.tertiaryContainer, colors.primaryContainer, colors.secondaryContainer).forEachIndexed { index, tint ->
                    Box(Modifier.weight(1f).aspectRatio(0.8f).graphicsLayer {
                        val a = outCubic(span(story.value, 0.04f + index * 0.09f, 0.24f + index * 0.09f))
                        alpha = a * gone(); val s = 0.86f + 0.14f * a; scaleX = s; scaleY = s }.background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.45f))),
                        RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        when (index) {
                            0 -> Icon(painterResource(R.drawable.ic_movie), null, Modifier.size(24.dp), tint = colors.onTertiaryContainer)
                            1 -> Icon(painterResource(R.drawable.ic_image), null, Modifier.size(24.dp), tint = colors.onPrimaryContainer)
                            else -> Surface(Modifier.size(36.dp).graphicsLayer {
                                    val s = outBack(span(story.value, 0.46f, 0.6f)); scaleX = s; scaleY = s },
                                shape = CircleShape, color = colors.primary) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(22.dp).testTag("tour_status_saved"), tint = colors.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        TabPill(R.drawable.ic_status_ring, R.string.statuses_tab, "tour_statuses") {
            kotlin.math.sin(Math.PI * span(story.value, 0.62f, 0.76f)).toFloat() }
    }
}
