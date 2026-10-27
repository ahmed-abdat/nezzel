package dev.ahmed.savlino.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.nav.rememberTabLoop
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A site mark drawn the way its app icon looks: [ink] glyph on a [tile], with optional offset [echo] copies or an [outline] behind it. */
internal class SiteLogo(@DrawableRes val glyph: Int, val tile: Brush, val ink: Color, val glyphSize: Dp = 46.dp,
    val echo: List<Pair<Color, Offset>> = emptyList(), val outline: Color? = null)

private val white = SolidColor(Color.White)
private val black = SolidColor(Color.Black)

// Simple Icons paths (CC0) in each site's own colours; decorative, never a claim of partnership.
// Instagram keeps only the warm end of its gradient: the app never shows purple.
internal object SiteLogos {
    val youtube = SiteLogo(R.drawable.logo_youtube, white, Color(0xFFFF0000), 54.dp)
    val whatsapp = SiteLogo(R.drawable.logo_whatsapp, SolidColor(Color(0xFF25D366)), Color.White)
    val instagram = SiteLogo(R.drawable.logo_instagram, Brush.linearGradient(listOf(Color(0xFFFFC21A), Color(0xFFFF6A00), Color(0xFFFF0069)),
        start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f)), Color.White)
    val facebook = SiteLogo(R.drawable.logo_facebook, white, Color(0xFF0866FF), 54.dp)
    val tiktok = SiteLogo(R.drawable.logo_tiktok, black, Color.White, 44.dp,
        listOf(Color(0xFF25F4EE) to Offset(-1.5f, -1.5f), Color(0xFFFE2C55) to Offset(1.5f, 1.5f)))
    val telegram = SiteLogo(R.drawable.logo_telegram, white, Color(0xFF26A5E4), 54.dp)
    val x = SiteLogo(R.drawable.logo_x, black, Color.White, 40.dp)
    val snapchat = SiteLogo(R.drawable.logo_snapchat, SolidColor(Color(0xFFFFFC00)), Color.White, 46.dp, outline = Color.Black)
    // Order decides where each tile falls on the wall: WhatsApp (Statuses) lands on clear cells, not under the mark
    val all = listOf(youtube, facebook, instagram, whatsapp, tiktok, telegram, x, snapchat)
}

// The arc has room for about seven logos: the most used sites take the closest spots, WhatsApp beside the mark for Statuses
private val arcOrder = with(SiteLogos) { listOf(youtube, whatsapp, tiktok, instagram, facebook, snapchat, x, telegram) }

/** A tilted wall of every site tile, larger than its parent so it bleeds off the edges. Purely decorative. */
@Composable
internal fun SiteLogoWall(modifier: Modifier = Modifier, tile: Dp = 80.dp) {
    // A slow drift of about half a tile and back keeps the wall alive; still when animations are off
    val drift = rememberTabLoop(0f, 1f, tween(14_000, easing = FastOutSlowInEasing), RepeatMode.Reverse, rest = 0f, enabled = !rememberReducedMotion())
    Column(modifier.requiredSize(tile * 7.5f).graphicsLayer {
        rotationZ = -24f
        val shift = drift.value * tile.toPx() * 0.6f
        translationX = shift; translationY = -shift * 0.45f
    },
        verticalArrangement = Arrangement.spacedBy(tile / 5, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(5) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(tile / 5)) {
                repeat(6) { column -> LogoTile(SiteLogos.all[(row * 6 + column) % SiteLogos.all.size], tile) }
            }
        }
    }
}

/**
 * Site tiles on a tilted grid that keeps clear of [clear] (the mark, as an ellipse around the centre shifted
 * down by [markShift]) and of a [topClear] band under the top bar title. The tiles nearest the mark carry real
 * logos; blank tiles in soft theme colours fill the rest. Purely decorative.
 */
@Composable
internal fun SiteLogoArc(width: Dp, height: Dp, modifier: Modifier = Modifier, tile: Dp = 58.dp, markShift: Dp = 0.dp,
    topClear: Dp = 0.dp, clear: DpOffset = DpOffset(100.dp, 82.dp)) {
    val colors = MaterialTheme.colorScheme
    val blanks = listOf(colors.primaryContainer.copy(alpha = 0.7f), colors.surfaceContainerHighest,
        colors.tertiaryContainer.copy(alpha = 0.7f), colors.surfaceContainerLowest)
    val cells = remember(width, height, tile, markShift, topClear, clear) {
        val pitch = tile.value * 1.3f
        val angle = Math.toRadians(-24.0)
        (-5..5).flatMap { row -> (-6..6).map { column ->
            val x = column * pitch; val y = row * pitch
            (x * cos(angle) - y * sin(angle)).toFloat() to (x * sin(angle) + y * cos(angle)).toFloat()
        } }.filter { (x, y) ->
            val dx = x / clear.x.value; val dy = (y - markShift.value) / clear.y.value
            dx * dx + dy * dy > 1f && y - tile.value / 2 > -height.value / 2 + topClear.value &&
                kotlin.math.abs(x) < width.value / 2 + tile.value && y < height.value / 2 + tile.value
        // Logos go to the closest tiles that sit above the fade; lower tiles stay blank
        }.sortedBy { (x, y) -> hypot(x, y - markShift.value) +
            if (y - markShift.value > tile.value * 1.6f || kotlin.math.abs(x) > width.value / 2 - tile.value * 0.8f) 1000f else 0f }
    }
    val drift = rememberTabLoop(-1f, 1f, tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse, rest = 0f, enabled = !rememberReducedMotion())
    Box(modifier.requiredSize(width, height), contentAlignment = Alignment.Center) {
        cells.forEachIndexed { index, (x, y) ->
            val logo = arcOrder.getOrNull(index)
            // Alternate tiles float in opposite directions by a few dp, so the arc breathes without crowding the mark
            val sway = if (index % 2 == 0) 1f else -1f
            Box(Modifier.offset(x.dp, y.dp).graphicsLayer {
                rotationZ = -24f
                translationY = drift.value * sway * 3.dp.toPx()
            }) {
                if (logo != null) LogoTile(logo, tile)
                else Box(Modifier.size(tile).background(blanks[index % blanks.size], RoundedCornerShape(tile * 0.275f)))
            }
        }
    }
}

@Composable
internal fun LogoTile(logo: SiteLogo, size: Dp = 80.dp, modifier: Modifier = Modifier) {
    // Glyph sizes are tuned for an 80 dp tile and scale with it
    val scale = size / 80.dp
    Box(modifier.size(size).background(logo.tile, RoundedCornerShape(size * 0.275f)), contentAlignment = Alignment.Center) {
        logo.echo.forEach { (color, shift) ->
            Image(painterResource(logo.glyph), null, Modifier.size(logo.glyphSize * scale).offset((shift.x * scale).dp, (shift.y * scale).dp),
                colorFilter = ColorFilter.tint(color))
        }
        logo.outline?.let { Image(painterResource(logo.glyph), null, Modifier.size((logo.glyphSize + 5.dp) * scale), colorFilter = ColorFilter.tint(it)) }
        Image(painterResource(logo.glyph), null, Modifier.size(logo.glyphSize * scale), colorFilter = ColorFilter.tint(logo.ink))
    }
}
