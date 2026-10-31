package dev.ahmed.savlino.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.ui.nav.rememberTabLoop
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlin.math.cos
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
    val reddit = SiteLogo(R.drawable.logo_reddit, white, Color(0xFFFF4500), 56.dp)
    val pinterest = SiteLogo(R.drawable.logo_pinterest, white, Color(0xFFE60023), 62.dp)
    val vimeo = SiteLogo(R.drawable.logo_vimeo, SolidColor(Color(0xFF1AB7EA)), Color.White, 48.dp)
    val soundcloud = SiteLogo(R.drawable.logo_soundcloud, Brush.verticalGradient(listOf(Color(0xFFFF8800), Color(0xFFFF3300))), Color.White, 56.dp)
    // Order decides where each tile falls on the wall: WhatsApp (Statuses) lands on clear cells, not under the mark
    val all = listOf(youtube, facebook, instagram, whatsapp, tiktok, reddit, telegram, x, snapchat, pinterest, soundcloud, vimeo)
}

// The six most used sites ride the inner ring, the rest the outer one
private val arcOrder = with(SiteLogos) { listOf(youtube, whatsapp, tiktok, instagram, facebook, snapchat, x, telegram, reddit, pinterest, soundcloud, vimeo) }

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
                // Each row starts seven tiles on, so no site sits next to or above itself
                repeat(6) { column -> LogoTile(SiteLogos.all[(row * 7 + column) % SiteLogos.all.size], tile) }
            }
        }
    }
}

/**
 * The site tiles on two rings around the mark (centre shifted down by [markShift]): six on an inner ellipse, six on a
 * wider outer one, staggered half a step so no two tiles meet. Both rings turn together, slowly, so the spacing stays
 * even while they move; tiles stay upright and fade under a [topClear] band. Still when animations are off. Decorative.
 */
@Composable
internal fun SiteLogoArc(width: Dp, height: Dp, modifier: Modifier = Modifier, tile: Dp = 58.dp, markShift: Dp = 0.dp,
    topClear: Dp = 0.dp, @Suppress("UNUSED_PARAMETER") clear: DpOffset = DpOffset(100.dp, 82.dp)) {
    val half = (height.value - markShift.value) / 2
    val size = minOf(tile.value, half * 0.36f).dp
    // Sized so the rings never touch while they turn and the side tiles stay whole: the inner ring clears the 104 dp mark
    val innerY = maxOf(half * 0.55f, 52f + size.value / 2 + 6f)
    val innerX = innerY * 1.31f
    val outerY = half * 0.80f
    val outerX = minOf(width.value / 2 - size.value * 0.55f, innerX * 1.4f)
    val turn = rememberTabLoop(0f, 1f, tween(90_000, easing = LinearEasing), RepeatMode.Restart, rest = 0f, enabled = !rememberReducedMotion())
    Box(modifier.requiredSize(width, height), contentAlignment = Alignment.Center) {
        arcOrder.forEachIndexed { index, logo ->
            val outer = index >= 6
            val start = (index % 6) * 60.0 + if (outer) 30.0 else 0.0
            Box(Modifier.size(size).graphicsLayer {
                val angle = Math.toRadians(start + turn.value * 360.0 - 90.0)
                val x = (if (outer) outerX else innerX) * cos(angle).toFloat()
                val y = (if (outer) outerY else innerY) * sin(angle).toFloat() + markShift.value
                translationX = x.dp.toPx(); translationY = y.dp.toPx()
                // A touch of depth: tiles nearer the bottom come forward, the ones at the back sit smaller
                val depth = 0.95f + 0.05f * sin(angle).toFloat()
                scaleX = depth; scaleY = depth
                rotationZ = if (index % 2 == 0) -8f else 8f
                if (topClear.value > 0f) alpha = ((y - size.value / 2 + height.value / 2 - topClear.value) / size.value).coerceIn(0f, 1f)
            }) {
                // A soft lift like a launcher icon, so white tiles stand off a light page
                LogoTile(logo, size, Modifier.shadow(3.dp, RoundedCornerShape(size * 0.275f), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f)))
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
