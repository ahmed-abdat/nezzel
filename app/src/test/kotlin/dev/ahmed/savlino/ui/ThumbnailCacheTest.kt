package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.ahmed.savlino.downloads.DownloadsScreen
import dev.ahmed.savlino.downloads.HomeRecents
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One byte-sized picture cache for Home recents, Downloads and Statuses: tab switches and fresh rows never decode again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class ThumbnailCacheTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun picture(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test fun budgetIsASixteenthOfTheHeapClassAndEvictsByBytes() {
        assertEquals(8 * 1024 * 1024, ThumbnailCache.budget(128))
        assertEquals(16 * 1024 * 1024, ThumbnailCache.budget(256))
        val cache = ThumbnailCache(3 * 100 * 100 * 4 * 8)
        // Bigger than an eighth of the budget (a viewer page): never kept.
        cache.put("big", picture(400, 400))
        assertNull(cache["big"])
        repeat(8) { cache.put("t$it", picture(100, 100)) }
        repeat(8) { assertNotNull(cache["t$it"]) }
        repeat(18) { cache.put("u$it", picture(100, 100)) }
        // 24 slots of 40 KB: the oldest went first.
        assertNull(cache["t0"])
        assertNotNull(cache["u17"])
    }

    @Test fun switchingTabsBackAndForthAndRecomposingNeverRunsTheLoaderAgain() {
        val media = (1..4).map { n ->
            SavedMedia(Uri.parse("content://media/external/downloads/$n"), "Clip $n.mp4", "Clip $n", "video/mp4", 1_000_000L,
                1_700_000_000L - n * 60, 1280, 720, 30_000)
        }
        val loads = mutableListOf<String>()
        val pictures = MediaPictures { uri, _, size -> synchronized(loads) { loads += "$uri@$size" }; picture(32, 18) }
        val cache = ThumbnailCache(ThumbnailCache.budget(64))
        var route by mutableStateOf("Home")
        // A stopped job keeps Downloading · Saved, so the sub-tab switch is covered too.
        val stopped = listOf(DownloadProgress("f", "late.mp4", DownloadPhase.FAILED, retryable = true))
        @Composable fun Screen() = CompositionLocalProvider(LocalThumbnailCache provides cache, LocalMediaPictures provides pictures) {
            TabContent(route) { destination ->
                when (destination) {
                    "Home" -> HomeRecents(media, "en", {}) {}
                    "Downloads" -> DownloadsScreen(stopped, "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media))
                    else -> androidx.compose.material3.Text(destination)
                }
            }
        }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { Screen() } }
            compose.waitForIdle()
            // The recents row shows the cards that fit (two of four at this width).
            compose.waitUntil(5000) { synchronized(loads) { loads.isNotEmpty() } }
            compose.waitForIdle()
            compose.runOnIdle { route = "Downloads" }
            compose.waitForIdle()
            compose.onNodeWithTag("saved_list").assertExists()
            compose.waitUntil(5000) { synchronized(loads) { loads.size } >= media.size }
            val afterFirstVisits = synchronized(loads) { loads.toList() }
            assertEquals("every picture decoded once", afterFirstVisits.toSet().size, afterFirstVisits.size)
            repeat(3) {
                for (destination in listOf("Home", "Statuses", "Downloads", "Home", "Downloads")) {
                    compose.runOnIdle { route = destination }
                    compose.waitForIdle()
                }
                // Downloading and back: the saved list composes again but draws from memory.
                compose.onNodeWithTag("tab_running").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("tab_saved").performClick()
                compose.waitForIdle()
            }
            compose.runOnIdle { assertEquals("no loader re-ran on a switch", afterFirstVisits, synchronized(loads) { loads.toList() }) }
            // A brand-new composition (recreated screen) also finds every picture in the shared cache.
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { Screen() } }
            compose.waitForIdle()
            compose.runOnIdle { route = "Home" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("a fresh composition draws from memory", afterFirstVisits, synchronized(loads) { loads.toList() }) }
        } finally { controller.pause().stop().destroy() }
    }
}
