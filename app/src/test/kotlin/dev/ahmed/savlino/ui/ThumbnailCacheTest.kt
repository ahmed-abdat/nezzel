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
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    @Test fun replacingAFileAtTheSameUriReloadsItsThumbnail() = kotlinx.coroutines.test.runTest {
        val cache = ThumbnailCache(8_000_000)
        val uri = Uri.parse("content://media/external/downloads/7")
        val old = picture(10, 10).apply { eraseColor(android.graphics.Color.RED) }
        val fresh = picture(10, 10).apply { eraseColor(android.graphics.Color.BLUE) }
        val firstKey = ThumbnailCache.key(uri, 240, 100L, 1_000L)
        assertSame(old, cache.getOrLoad(firstKey) { old })
        assertSame(old, cache.getOrLoad(firstKey) { error("unchanged revision is cached") })
        val nextKey = ThumbnailCache.key(uri, 240, 100L, 2_000L)
        val result = cache.getOrLoad(nextKey) { fresh }
        assertSame(fresh, result)
        assertEquals(android.graphics.Color.BLUE, result!!.getPixel(0, 0))
        assertNotEquals(nextKey, ThumbnailCache.key(uri, 240, 200L, 2_000L))
        assertNotEquals(nextKey, ThumbnailCache.key(uri, 320, 100L, 2_000L))
    }

    @Test fun displayedSavedFileReloadsWhenOnlyItsModificationTimeChanges() {
        var file by mutableStateOf(SavedMedia(Uri.parse("content://media/external/downloads/7"), "Clip.mp4", "Clip",
            "video/mp4", 100L, 1_000L, null, null, null, modifiedMillis = 1_000L))
        val cache = ThumbnailCache(8_000_000)
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val pictures = MediaPictures { _, _, _ -> loads.incrementAndGet(); picture(16, 9) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalThumbnailCache provides cache, LocalMediaPictures provides pictures) {
                    NezzelTheme(ThemePref.Light, false) {
                        dev.ahmed.savlino.downloads.MediaThumbnail(file, androidx.compose.ui.Modifier)
                    }
                }
            }
            compose.waitUntil(5000) { loads.get() == 1 }
            compose.runOnIdle { file = file.copy(modifiedMillis = 2_000L) }
            // This activity is not the rule's own: frames only run when the main looper's clock moves.
            compose.waitUntil(5000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS); loads.get() == 2 }
            compose.runOnIdle { file = file.copy(title = "Edited label") }
            org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS); compose.waitForIdle()
            assertEquals(2, loads.get())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun sameKeyLoadsShareOneRun() = kotlinx.coroutines.test.runTest {
        val cache = ThumbnailCache(8_000_000)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var runs = 0
        val bitmap = picture(10, 10)
        val waiters = List(5) { async { cache.getOrLoad("k") { runs++; gate.await(); bitmap } } }
        testScheduler.runCurrent()
        gate.complete(Unit)
        waiters.forEach { assertSame(bitmap, it.await()) }
        assertEquals(1, runs)
    }

    @Test fun atMostTwoDecodesRunAtOnce() = kotlinx.coroutines.test.runTest {
        val cache = ThumbnailCache(8_000_000)
        var running = 0; var most = 0
        val loads = List(6) { n -> async { cache.getOrLoad("k$n") {
            running++; most = maxOf(most, running); kotlinx.coroutines.delay(100); running--; picture(4, 4)
        } } }
        loads.forEach { assertNotNull(it.await()) }
        assertEquals(ThumbnailCache.DECODES, most)
    }

    @Test fun aRunStopsOnlyWhenEveryWaiterHasLeft() = kotlinx.coroutines.test.runTest {
        val cache = ThumbnailCache(8_000_000)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stopped = 0
        val load: suspend () -> Bitmap? = { try { gate.await(); picture(4, 4) } catch (e: kotlinx.coroutines.CancellationException) { stopped++; throw e } }
        val first = launch { cache.getOrLoad("k", load = load) }
        val second = async { cache.getOrLoad("k", load = load) }
        testScheduler.runCurrent()
        first.cancel(); testScheduler.runCurrent()
        assertEquals("one waiter left, the other still needs it", 0, stopped)
        gate.complete(Unit)
        assertNotNull(second.await())
        val gate2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val a = launch { cache.getOrLoad("j") { try { gate2.await(); null } catch (e: kotlinx.coroutines.CancellationException) { stopped++; throw e } } }
        val b = launch { cache.getOrLoad("j") { error("shares the first run") } }
        testScheduler.runCurrent()
        a.cancel(); b.cancel(); testScheduler.runCurrent()
        assertEquals(1, stopped)
    }

    @Test fun aMissIsRememberedBrieflyUnlessRetried() = kotlinx.coroutines.test.runTest {
        var clock = 0L
        val cache = ThumbnailCache(8_000_000) { clock }
        var runs = 0
        assertNull(cache.getOrLoad("k") { runs++; null })
        assertNull(cache.getOrLoad("k") { runs++; null })
        assertEquals(1, runs)
        assertNull(cache.getOrLoad("k", retryMiss = true) { runs++; null })
        assertEquals(2, runs)
        clock += ThumbnailCache.MISS_MS
        assertNotNull(cache.getOrLoad("k") { runs++; picture(4, 4) })
        assertEquals(3, runs)
    }

    @Test fun aLoaderThatAsksForItsOwnKeyDoesNotWaitForItself() = kotlinx.coroutines.test.runTest {
        // Statuses wraps its own getOrLoad inside rememberCachedThumbnail's.
        val cache = ThumbnailCache(8_000_000)
        val result = kotlinx.coroutines.withTimeout(5_000) { cache.getOrLoad("k") { cache.getOrLoad("k") { picture(4, 4) } } }
        assertNotNull(result)
    }

    @Test fun cancellingTheCallerCancelsTheProviderSignal() = kotlinx.coroutines.test.runTest {
        var signal: android.os.CancellationSignal? = null
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            withCancellationSignal { s -> signal = s; started.complete(Unit); while (!s.isCanceled) Thread.sleep(5) }
        }
        started.await()
        assertFalse(signal!!.isCanceled)
        job.cancel(); job.join()
        assertTrue(signal!!.isCanceled)
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
        // A check warning keeps Downloading · Saved (and opens Saved), so the sub-tab switch is covered too.
        val warnings = listOf(dev.ahmed.savlino.jobs.CheckWarning("w", "late.mp4", null, dev.ahmed.savlino.domain.ResultCheck.Problem.NO_SOUND, true))
        @Composable fun Screen() = CompositionLocalProvider(LocalThumbnailCache provides cache, LocalMediaPictures provides pictures) {
            TabContent(route) { destination ->
                when (destination) {
                    "Home" -> HomeRecents(media, "en", {}) {}
                    "Downloads" -> DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media), warnings = warnings)
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
