package dev.ahmed.savlino.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.ahmed.savlino.R

@OptIn(ExperimentalTextApi::class)
private val Arabic = FontFamily(listOf(400, 500, 600, 700).map { weight ->
    Font(R.font.noto_sans_arabic, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

private fun typography(arabic: Boolean): Typography {
    fun style(size: Int, weight: Int, enHeight: Int, arHeight: Int, tracking: Double = 0.0) = TextStyle(
        fontFamily = if (arabic) Arabic else FontFamily.Default,
        fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = (if (arabic) arHeight else enHeight).sp,
        letterSpacing = (if (arabic) 0.0 else tracking).sp, fontFeatureSettings = "tnum",
        // Noto Sans Arabic's ascent/descent (~2em) exceed these line heights; the default trim would let a single line
        // grow to the font's own height (a 28sp line measured 36dp on device). Trim.None keeps every line at its token.
        lineHeightStyle = if (arabic) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) else null,
        platformStyle = if (arabic) PlatformTextStyle(includeFontPadding = false) else null)
    return Typography(
        displayLarge = style(57, 400, 64, 76), displayMedium = style(45, 400, 52, 64),
        displaySmall = style(36, 400, 44, 52),
        headlineLarge = style(32, 400, 40, 48), headlineMedium = style(28, 400, 36, 44),
        headlineSmall = style(24, 400, 32, 38), titleLarge = style(22, 500, 28, 34),
        titleMedium = style(16, 500, 24, 28, .15), titleSmall = style(14, 500, 20, 24, .1),
        bodyLarge = style(16, 400, 24, 28, .5), bodyMedium = style(14, 400, 20, 24, .25),
        bodySmall = style(12, 400, 16, 20, .4), labelLarge = style(14, 500, 20, 24, .1),
        labelMedium = style(12, 500, 16, 20, .5), labelSmall = style(11, 500, 16, 18, .5))
}
val NezzelTypographyEn = typography(false)
val NezzelTypographyAr = typography(true)
