package dev.ahmed.savlino.ui.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Tab bar labels at large text: one line each, never a word broken in two, all four at one size.
 * Real font metrics (native graphics) and the configuration's font scale, so Android 14's nonlinear scaling applies.
 * With NEZZEL_CAPTURE_DIR set it also saves the screen for design review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xxhdpi")
class TabBarLargeTextTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun englishAtNormalText() = bar(1f)
    @Test fun englishAtLargeText() = bar(1.3f)
    @Test fun englishAtDoubleText() = bar(2f)
    @Test fun englishAtDoubleTextDark() = bar(2f, dark = true)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-xxhdpi") fun arabicAtNormalText() = bar(1f, rtl = true)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-xxhdpi") fun arabicAtLargeText() = bar(1.3f, rtl = true)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-xxhdpi") fun arabicAtDoubleText() = bar(2f, rtl = true)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-xxhdpi") fun arabicAtDoubleTextDark() = bar(2f, rtl = true, dark = true)
    // The window the bug was seen in on the phone.
    @Test @Config(qualifiers = "ar-w400dp-h880dp-xxhdpi") fun arabicAtDoubleTextOnAWiderPhone() = bar(2f, rtl = true)
    // A small phone: the labels shrink further but stay whole and legible.
    @Test @Config(qualifiers = "en-w320dp-h640dp-xxhdpi") fun englishAtNormalTextOnASmallPhone() = bar(1f)
    @Test @Config(qualifiers = "en-w320dp-h640dp-xxhdpi") fun englishAtLargeTextOnASmallPhone() = bar(1.3f)
    @Test @Config(qualifiers = "en-w320dp-h640dp-xxhdpi") fun englishAtDoubleTextOnASmallPhone() = bar(2f)
    @Test @Config(qualifiers = "ar-w320dp-h640dp-xxhdpi") fun arabicAtNormalTextOnASmallPhone() = bar(1f, rtl = true)
    @Test @Config(qualifiers = "ar-w320dp-h640dp-xxhdpi") fun arabicAtLargeTextOnASmallPhone() = bar(1.3f, rtl = true)
    @Test @Config(qualifiers = "ar-w320dp-h640dp-xxhdpi") fun arabicAtDoubleTextOnASmallPhone() = bar(2f, rtl = true)

    private fun bar(fontScale: Float, rtl: Boolean = false, dark: Boolean = false) {
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        try {
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        var route by remember { mutableStateOf("Home") }
                        NavigationScaffold(route, { emptyList() }, if (rtl) "ar" else "en", { route = it }) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
            val labels = listOf(R.string.home, R.string.shortcut_downloads_short, R.string.statuses_tab, R.string.settings).map(activity::getString)
            assertEquals("The bar sees the configuration's font scale", fontScale, activity.resources.configuration.fontScale, .001f)
            // Home selected (bold), then the last tab: each time every label is checked in the weight on show.
            checkLabels(labels, labels.first(), fontScale)
            (System.getenv("NEZZEL_CAPTURE_DIR") ?: "build/reports/tabbar-bigtext").let { dir ->
                compose.runOnIdle {
                    val root = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    // The bar and a little above it is what the review is about.
                    val crop = Bitmap.createBitmap(bitmap, 0, root.height - root.height / 4, root.width, root.height / 4)
                    val name = "${if (rtl) "ar" else "en"}-${if (dark) "dark" else "light"}-font$fontScale-w${root.width}"
                    File(dir).mkdirs()
                    File(dir, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            compose.onNode(hasText(labels.last()) and hasClickAction()).performClick()
            checkLabels(labels, labels.last(), fontScale)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun checkLabels(labels: List<String>, selected: String, fontScale: Float) {
        val layouts = labels.map { label ->
            // The tab reads its label once, whole.
            val tabNode = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
            assertEquals("$label is read once, whole", listOf(label), tabNode.config[SemanticsProperties.Text].map { it.text })
            // Only the label on show carries semantics, so this is the drawn one.
            val node = compose.onAllNodes(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                useUnmergedTree = true).fetchSemanticsNodes().single()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
            val layout = results.single()
            println("TABBAR $label x$fontScale sel=${label == selected} sp=${screenSp(layout)} box=${layout.size} " +
                "ell=${layout.isLineEllipsized(0)} oh=${layout.didOverflowHeight} c=${layout.layoutInput.constraints}")
            val weight = if (label == selected) FontWeight.Bold else FontWeight.Medium
            assertEquals("$label is read from the label on show", weight, layout.layoutInput.style.fontWeight)
            assertEquals("$label stays on one line at $fontScale", 1, layout.lineCount)
            assertFalse("$label is whole at $fontScale", layout.isLineEllipsized(0))
            assertFalse("$label is not cut at $fontScale", layout.didOverflowHeight)
            // Every glyph inside its tab.
            val tab = tabNode.boundsInRoot
            for (offset in label.indices.filterNot { label[it].isWhitespace() }) {
                val glyph = layout.getBoundingBox(offset).translate(node.positionInRoot)
                assertTrue("$label glyph $offset inside its tab: $glyph / $tab", glyph.left >= tab.left - 1f && glyph.right <= tab.right + 1f)
            }
            layout
        }
        val sizes = layouts.map(::screenSp)
        assertTrue("All four labels share one size at $fontScale: $sizes", sizes.all { abs(it - sizes.first()) < .01f })
        // A 320dp phone is narrow enough that "Downloads" in bold takes 11sp even at normal text.
        if (fontScale == 1f && RuntimeEnvironment.getApplication().resources.configuration.screenWidthDp >= 360) {
            assertEquals("Normal text keeps the label style's size", 12f, sizes.first(), .01f)
        }
        assertTrue("Never below 9sp on screen: $sizes", sizes.first() >= 9f - .01f)
    }

    /**
     * The size the label is drawn at, in sp at font scale 1. The layout handed to semantics carries the node's own style, not
     * the fitted size, so this reads the paint the paragraph was laid out with.
     */
    private fun screenSp(layout: TextLayoutResult): Float {
        fun Any.call(prefix: String): Any = javaClass.methods.first { it.name.startsWith(prefix) && it.parameterCount == 0 }.invoke(this)!!
        val info = (layout.multiParagraph.intrinsics.call("getInfoList") as List<*>).single()!!
        val paint = info.call("getIntrinsics").call("getTextPaint") as android.graphics.Paint
        return paint.textSize / layout.layoutInput.density.density
    }
}
