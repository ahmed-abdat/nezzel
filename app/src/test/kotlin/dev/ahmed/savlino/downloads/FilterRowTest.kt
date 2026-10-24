package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-w360dp-h800dp-xxhdpi")
class FilterRowTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private fun media(id: Int, mime: String, path: String = "Download/Nezzel/") = SavedMedia(Uri.parse("content://media/external/downloads/$id"),
        "File $id", "File $id", mime, 100, 100L + id, 720, null, null, relativePath = path)

    @Test fun arabicLargeTextKeepsEveryChipOnOneScrollingRowStartingAtStart() {
        val library = listOf(media(1, "video/mp4"), media(2, "audio/mpeg"), media(3, "image/jpeg"), media(4, "image/jpeg", "Download/Nezzel/Statuses/"))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                NezzelTheme(ThemePref.Light, false) {
                    DownloadsScreen(emptyList(), "ar", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(library))
                }
            }
        }
        val labels = listOf(R.string.filter_all, R.string.filter_videos, R.string.audio_group, R.string.filter_images, R.string.statuses_tab)
            .map { activity.getString(it) }
        val chips = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        chips.forEach { assertEquals("Chips share one line", chips.first().center.y, it.center.y, 1f) }
        chips.forEach { assertTrue("48dp touch target", it.height >= 48 * activity.resources.displayMetrics.density - 1) }
        // RTL: All sits at the start (right), each later chip further toward the end.
        chips.zipWithNext().forEach { (before, after) -> assertTrue(before.left > after.left) }
        val row = compose.onNodeWithTag("filter_row").fetchSemanticsNode()
        assertNotNull(row.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
        controller.pause().stop().destroy()
    }
}
