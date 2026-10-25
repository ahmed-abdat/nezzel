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

    private fun check(direction: LayoutDirection, language: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            var primary = 0
            val jobs = listOf(DownloadProgress("r", "clip.mp4", DownloadPhase.DOWNLOADING, bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(10)))
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
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

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

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
