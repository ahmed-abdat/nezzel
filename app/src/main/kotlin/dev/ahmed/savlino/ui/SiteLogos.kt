package dev.ahmed.savlino.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
import dev.ahmed.savlino.ui.nav.rememberTabGlide
import dev.ahmed.savlino.ui.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/** A site mark drawn the way its app icon looks: [ink] glyph on a [tile], with optional offset [echo] copies or an [outline] behind it. */
internal class SiteLogo(@DrawableRes val glyph: Int, val tile: Brush, val ink: Color, val glyphSize: Dp = 46.dp,
    val echo: List<Pair<Color, Offset>> = emptyList(), val outline: Color? = null) {
    /** Near-black tiles (X, TikTok, Threads, Dailymotion) get a faint light edge so they don't vanish on the dark theme. */
    val dark = tile == black || (tile as? SolidColor)?.value?.let { it.red + it.green + it.blue < 0.2f } == true
}

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
    val bluesky = SiteLogo(R.drawable.logo_bluesky, white, Color(0xFF0085FF), 52.dp)
    val threads = SiteLogo(R.drawable.logo_threads, black, Color.White, 44.dp)
    // LinkedIn's mark is its own blue square; drawn edge to edge it becomes the tile.
    val linkedin = SiteLogo(R.drawable.logo_linkedin, white, Color(0xFF0A66C2), 80.dp)
    val dailymotion = SiteLogo(R.drawable.logo_dailymotion, SolidColor(Color(0xFF0D0D0D)), Color.White, 46.dp)
    val twitch = SiteLogo(R.drawable.logo_twitch, SolidColor(Color(0xFF9146FF)), Color.White, 44.dp)
    // Order decides where each tile falls on the wall: WhatsApp (Statuses) lands on clear cells, not under the mark
    val all = listOf(youtube, facebook, instagram, whatsapp, tiktok, reddit, telegram, x, snapchat, pinterest, soundcloud, vimeo,
        bluesky, threads, linkedin, dailymotion, twitch)
}

// The six most used sites ride the inner ring, the rest the outer one
private val arcOrder = with(SiteLogos) { listOf(youtube, whatsapp, tiktok, instagram, facebook, snapchat,
    x, threads, telegram, linkedin, reddit, bluesky, pinterest, twitch, soundcloud, dailymotion, vimeo) }
private const val INNER = 6
/** The ring's entrance plays once per process, not on every return to Home. */
private var arcShown = false

/** Starts at twice the average speed (a 90 s turn's pace for a sixth of a turn in 30 s) and slows to rest. */
private val GlideEasing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.5f, 0.5f, 1f)

/** A tilted wall of every site tile, larger than its parent so it bleeds off the edges. Purely decorative. */
@Composable
internal fun SiteLogoWall(modifier: Modifier = Modifier, tile: Dp = 80.dp) {
    // One slow drift of about half a tile each time the page shows, then it rests; still when animations are off
    val drift = rememberTabGlide(1f, tween(14_000, easing = FastOutSlowInEasing), sway = true, enabled = !rememberReducedMotion())
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
 * The site tiles on two rings around the mark (centre shifted down by [markShift]): six on an inner ellipse, the rest
 * spread evenly on a wider outer one, offset half a step so no two tiles meet. Both rings turn together, slowly, so the spacing stays
 * even while they move; tiles stay upright and fade under a [topClear] band. Still when animations are off. Decorative.
 */
@Composable
internal fun SiteLogoArc(width: Dp, height: Dp, modifier: Modifier = Modifier, tile: Dp = 58.dp, markShift: Dp = 0.dp, mark: Dp = 104.dp,
    topClear: Dp = 0.dp, @Suppress("UNUSED_PARAMETER") clear: DpOffset = DpOffset(100.dp, 82.dp)) {
    val half = (height.value - markShift.value) / 2
    // Seventeen tiles: a little smaller than a 12-tile ring so the outer ring can sit a full tile beyond the inner one
    val size = minOf(tile.value, half * 0.30f).dp
    // Sized so the rings never touch while they turn and the side tiles stay whole: the inner ring clears the [mark]
    val innerY = maxOf(half * 0.55f, mark.value / 2 + size.value / 2 + 6f)
    val innerX = innerY * 1.31f
    // At least a tile and a quarter beyond the inner ring on every side; a narrow box crops side tiles rather than overlap them
    val outerY = maxOf(half * 0.86f, innerY + size.value * 1.25f)
    val outerX = maxOf(innerX + size.value * 1.3f, minOf(width.value / 2 - size.value * 0.55f, innerX * 1.5f))
    val reduced = rememberReducedMotion()
    // A sixth of a turn each time the page shows, starting at the old steady pace and easing to a stop: alive on arrival,
    // then still, so an open Home costs no frames
    val turn = rememberTabGlide(1f / 6, tween(30_000, easing = GlideEasing), enabled = !reduced)
    // First sight only (once per process): the tiles start hidden behind the mark and fan out one after another, inner ring first.
    val entrance = remember { Animatable(if (reduced || arcShown) 1f else 0f) }
    LaunchedEffect(Unit) {
        arcShown = true
        if (entrance.value == 1f) return@LaunchedEffect
        // Wait out the first frames: a cold start drops them, and the fan-out would be half over before it shows
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        kotlinx.coroutines.delay(120)
        entrance.animateTo(1f, tween(1100, easing = LinearEasing))
    }
    Box(modifier.requiredSize(width, height), contentAlignment = Alignment.Center) {
        arcOrder.forEachIndexed { index, logo ->
            val outer = index >= INNER
            val outerCount = arcOrder.size - INNER
            val start = if (outer) (index - INNER) * 360.0 / outerCount + 180.0 / outerCount else index * 360.0 / INNER
            Box(Modifier.size(size).graphicsLayer {
                val angle = Math.toRadians(start + turn.value * 360.0 - 90.0)
                val x = (if (outer) outerX else innerX) * cos(angle).toFloat()
                val y = (if (outer) outerY else innerY) * sin(angle).toFloat() + markShift.value
                // Each tile's own 0..1 share of the entrance, eased out so it settles softly
                val step = ((entrance.value - index * 0.025f) / 0.55f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
                val reach = step
                translationX = (x * reach).dp.toPx(); translationY = (y * reach + markShift.value * (1f - reach)).dp.toPx()
                // A touch of depth: tiles nearer the bottom come forward, the ones at the back sit smaller
                val depth = (0.95f + 0.05f * sin(angle).toFloat()) * (0.5f + 0.5f * step)
                scaleX = depth; scaleY = depth
                rotationZ = if (index % 2 == 0) -8f else 8f
                alpha = minOf(1f, step * 3f) * if (topClear.value > 0f) ((y - size.value / 2 + height.value / 2 - topClear.value) / size.value).coerceIn(0f, 1f) else 1f
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
    val shape = RoundedCornerShape(size * 0.275f)
    Box(modifier.size(size).background(logo.tile, shape)
        .then(if (logo.dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape) else Modifier), contentAlignment = Alignment.Center) {
        logo.echo.forEach { (color, shift) ->
            Image(painterResource(logo.glyph), null, Modifier.size(logo.glyphSize * scale).offset((shift.x * scale).dp, (shift.y * scale).dp),
                colorFilter = ColorFilter.tint(color))
        }
        logo.outline?.let { Image(painterResource(logo.glyph), null, Modifier.size((logo.glyphSize + 5.dp) * scale), colorFilter = ColorFilter.tint(it)) }
        Image(painterResource(logo.glyph), null, Modifier.size(logo.glyphSize * scale), colorFilter = ColorFilter.tint(logo.ink))
    }
}
