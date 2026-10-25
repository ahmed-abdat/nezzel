package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The tab underline is drawn under the selected word (not its count and dot slots), in English and in Arabic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class DownloadsTabIndicatorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test @Config(qualifiers = "+en") fun underlineFollowsTheWordInEnglish() = check(LayoutDirection.Ltr, "en")
    @Test @Config(qualifiers = "+ar") fun underlineFollowsTheWordInArabic() = check(LayoutDirection.Rtl, "ar")

    @Test @Config(qualifiers = "+en") fun doubleFontUnderlineFollowsTheEnglishWord() = check(LayoutDirection.Ltr, "en", 2f)
    @Test @Config(qualifiers = "+ar") fun doubleFontUnderlineFollowsTheArabicWord() = check(LayoutDirection.Rtl, "ar", 2f)
    @Test @Config(qualifiers = "+fr") fun doubleFontUnderlineFollowsTheFrenchWord() = check(LayoutDirection.Ltr, "fr", 2f)

    @Test @Config(qualifiers = "+en") fun normalEnglishLabelAndCountAreCentered() = centered(LayoutDirection.Ltr, "en")
    @Test @Config(qualifiers = "+ar") fun normalArabicLabelAndCountAreCentered() = centered(LayoutDirection.Rtl, "ar")

    private fun centered(direction: LayoutDirection, language: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        androidx.compose.ui.platform.LocalDensity.current.density, 1f)) {
                    NezzelTheme(ThemePref.Light, false) {
                        DownloadsTabRow(DownloadsTab.RUNNING, 3, false, language) {}
                    }
                }
            }
            compose.waitForIdle()
            val tab = compose.onNodeWithTag("tab_running").fetchSemanticsNode().boundsInRoot
            val label = bounds("tab_running_label")
            val count = compose.onNodeWithTag("tab_running_count", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val groupCenter = (minOf(label.left, count.left) + maxOf(label.right, count.right)) / 2f
            assertEquals("Label and count centered in the tab", tab.center.x, groupCenter, 3f)
            for (tag in listOf("tab_running_label", "tab_saved_label")) {
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                assertEquals("Text centered in its label", node.boundsInRoot.center.x, bounds(tag).center.x, 3f)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun check(direction: LayoutDirection, language: String, fontScale: Float = 1f) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            var primary = 0
            val jobs = listOf(DownloadProgress("r", "clip.mp4", DownloadPhase.DOWNLOADING, bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(10)))
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) {
                    NezzelTheme(ThemePref.Light, false) {
                        primary = MaterialTheme.colorScheme.primary.toArgb()
                        DownloadsScreen(jobs, language, {}, {}, {}, {}, {}, null, {}, {})
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("tab_running").assertIsSelected()
            underline(controller.get(), primary).let { assertUnder(it, bounds("tab_running_label"), "Downloading") }
            compose.onNodeWithTag("tab_saved").performClick()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            underline(controller.get(), primary).let { assertUnder(it, bounds("tab_saved_label"), "Saved") }
        } finally { controller.pause().stop().destroy() }
    }

    private fun bounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val text = layouts.single()
        val left = (0 until text.lineCount).minOf(text::getLineLeft)
        val right = (0 until text.lineCount).maxOf(text::getLineRight)
        return Rect(node.positionInRoot.x + left, node.boundsInRoot.top, node.positionInRoot.x + right, node.boundsInRoot.bottom)
    }

    /** Primary-coloured pixels on the tab row's last pixel line: (first x, last x). */
    private fun underline(activity: ComponentActivity, primary: Int): Pair<Int, Int> {
        val row = compose.onNodeWithTag("tab_running").fetchSemanticsNode().boundsInRoot
        val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val y = row.bottom.toInt() - 1
        val xs = (0 until bitmap.width).filter { close(bitmap.getPixel(it, y), primary) }
        bitmap.recycle()
        assertTrue("an underline is drawn", xs.isNotEmpty())
        return xs.first() to xs.last()
    }

    private fun close(a: Int, b: Int) = listOf(16, 8, 0).all { abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 12 }

    private fun assertUnder(line: Pair<Int, Int>, label: Rect, name: String) {
        // Rounded top corners leave the last pixel line full width; 3 px of antialiasing slack at xxhdpi.
        assertEquals("$name underline start", label.left, line.first.toFloat(), 3f)
        assertEquals("$name underline end", label.right, line.second + 1f, 3f)
    }
}
