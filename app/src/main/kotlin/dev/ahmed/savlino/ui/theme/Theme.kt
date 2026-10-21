package dev.ahmed.savlino.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.locale.isDark

/** Wallpaper colors (Android 12+) follow any wallpaper; error roles stay Nezzel's so failures always read as errors. */
fun wallpaperScheme(candidate: ColorScheme, dark: Boolean): ColorScheme {
    val fallback = if (dark) NezzelDarkColors else NezzelLightColors
    return candidate.copy(error = fallback.error, onError = fallback.onError,
        errorContainer = fallback.errorContainer, onErrorContainer = fallback.onErrorContainer,
        scrim = fallback.scrim)
}

/** Large-text layouts (stacked rows, wrapped metrics) start at this system font scale. */
@Composable
fun isLargeText(): Boolean = LocalConfiguration.current.fontScale >= 1.5f

@Composable
fun NezzelTheme(theme: ThemePref, matchWallpaper: Boolean, content: @Composable () -> Unit) {
    val dark = theme.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val candidate = if (Build.VERSION.SDK_INT >= 31 && matchWallpaper) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val colors = candidate?.let { wallpaperScheme(it, dark) } ?: if (dark) NezzelDarkColors else NezzelLightColors
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    // BOM 2025.06.01 supplies Material3 1.3.2; Expressive MotionScheme is not available.
    MaterialTheme(colorScheme = colors, typography = if (arabic) NezzelTypographyAr else NezzelTypographyEn,
        shapes = NezzelShapes, content = content)
}
