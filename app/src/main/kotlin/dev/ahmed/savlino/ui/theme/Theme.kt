package dev.ahmed.savlino.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.locale.isDark

/** Wallpaper colors (Android 12+) follow any wallpaper; error roles stay Nezzel's so failures always read as errors, and the page matches its surface. */
fun wallpaperScheme(candidate: ColorScheme, dark: Boolean): ColorScheme {
    val fallback = if (dark) NezzelDarkColors else NezzelLightColors
    // Screens are one layer: sticky headers paint `surface` over the `background` page, so the two must match on every phone.
    return candidate.copy(background = candidate.surface, onBackground = candidate.onSurface,
        error = fallback.error, onError = fallback.onError,
        errorContainer = fallback.errorContainer, onErrorContainer = fallback.onErrorContainer,
        scrim = fallback.scrim)
}

/**
 * Accent roles from the wallpaper tonal palettes (`shade(palette, shade)`, palette 1–3). Some phones (Samsung One UI)
 * recolor the palettes but leave Android 14's role colors at their default blue, which Material3 reads from API 34.
 */
fun paletteAccents(scheme: ColorScheme, dark: Boolean, shade: (Int, Int) -> Color): ColorScheme {
    // Tones 40/100/90/10 (light) and 80/20/30/90 (dark), as Material3 maps them on Android 12–13.
    fun role(p: Int, light: Int, night: Int) = shade(p, if (dark) night else light)
    return scheme.copy(
        primary = role(1, 600, 200), onPrimary = role(1, 0, 800),
        primaryContainer = role(1, 100, 700), onPrimaryContainer = role(1, 900, 100),
        inversePrimary = role(1, 200, 600), surfaceTint = role(1, 600, 200),
        secondary = role(2, 600, 200), onSecondary = role(2, 0, 800),
        secondaryContainer = role(2, 100, 700), onSecondaryContainer = role(2, 900, 100),
        tertiary = role(3, 600, 200), onTertiary = role(3, 0, 800),
        tertiaryContainer = role(3, 100, 700), onTertiaryContainer = role(3, 900, 100))
}

@RequiresApi(31)
private fun systemShade(context: Context, palette: Int, shade: Int): Color {
    val ids = when (palette) {
        1 -> intArrayOf(android.R.color.system_accent1_0, android.R.color.system_accent1_100, android.R.color.system_accent1_200,
            android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900)
        2 -> intArrayOf(android.R.color.system_accent2_0, android.R.color.system_accent2_100, android.R.color.system_accent2_200,
            android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900)
        else -> intArrayOf(android.R.color.system_accent3_0, android.R.color.system_accent3_100, android.R.color.system_accent3_200,
            android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900)
    }
    return Color(context.getColor(ids[listOf(0, 100, 200, 600, 700, 800, 900).indexOf(shade)]))
}

/** Large-text layouts (stacked rows, wrapped metrics) start at this system font scale. */
@Composable
fun isLargeText(): Boolean = LocalConfiguration.current.fontScale >= 1.5f

@Composable
fun NezzelTheme(theme: ThemePref, matchWallpaper: Boolean, content: @Composable () -> Unit) {
    val dark = theme.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val candidate = if (Build.VERSION.SDK_INT >= 31 && matchWallpaper) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        paletteAccents(dynamic, dark) { palette, shade -> systemShade(context, palette, shade) }
    } else null
    val colors = candidate?.let { wallpaperScheme(it, dark) } ?: if (dark) NezzelDarkColors else NezzelLightColors
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    // BOM 2025.06.01 supplies Material3 1.3.2; Expressive MotionScheme is not available.
    MaterialTheme(colorScheme = colors, typography = if (arabic) NezzelTypographyAr else NezzelTypographyEn,
        shapes = NezzelShapes, content = content)
}
