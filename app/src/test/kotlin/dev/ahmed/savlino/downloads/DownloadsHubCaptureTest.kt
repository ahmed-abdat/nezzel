package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.windowViews
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** «Not saved» first in Downloading: review captures (ar light/dark, en light). Set NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class DownloadsHubCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private fun idle() = repeat(6) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
    private val library = listOf(SavedMedia(Uri.parse("content://media/external_primary/downloads/1"), "Morning by the sea.mp4", "Morning by the sea",
        "video/mp4", 4_200_000L, System.currentTimeMillis() / 1000 - 60, 720, null, 84_000, relativePath = "Download/Nezzel/", volumeName = "external_primary"))
    private val failures = listOf(
        DownloadProgress("f1", "Night market.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = true),
        DownloadProgress("f2", "سوق الليل.mp4", DownloadPhase.FAILED, error = DownloadError.LoginRequired, retryable = false))
    private val moving = listOf(
        DownloadProgress("r1", "Morning by the sea.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000, total = Size.Exact(100_000_000)),
        DownloadProgress("p1", "رحلة إلى الشاطئ.mp4", DownloadPhase.DOWNLOADING, paused = true, bytes = 18_000_000, total = Size.Exact(100_000_000), hasPartial = true),
        DownloadProgress("w1", "A quiet afternoon.mp4", DownloadPhase.PREPARING, waiting = true),
        DownloadProgress("w2", "Coastal live.mp4", DownloadPhase.PREPARING, waiting = true))
    private val failedList = PlaylistBatch(BatchRecord("l1", "رحلات الساحل", Preset.BALANCED, BatchState.COMPLETED, 0, null),
        listOf(BatchItemRecord("l1", 0, "a", "Morning", BatchItemState.DONE, "l1-a", null, false, false),
            BatchItemRecord("l1", 1, "b", "Afternoon", BatchItemState.FAILED, "l1-b", DownloadError.Network, true, false),
            BatchItemRecord("l1", 2, "c", "Dusk", BatchItemState.FAILED, "l1-c", DownloadError.Network, true, false)))
    /** beta24: finished, two videos failed FormatExpired before the automatic flag; one link kept (Retry), one gone (hint). */
    private val staleList = PlaylistBatch(BatchRecord("l2", "رحلات الساحل", Preset.BALANCED, BatchState.COMPLETED, 0, null),
        listOf(BatchItemRecord("l2", 0, "a", "Morning by the sea", BatchItemState.DONE, "l2-a", null, false, false),
            BatchItemRecord("l2", 1, "b", "A quiet afternoon", BatchItemState.FAILED, "l2-b", DownloadError.FormatExpired, false, false, true),
            BatchItemRecord("l2", 2, "c", "Harbour at dusk", BatchItemState.FAILED, "l2-c", DownloadError.FormatExpired, false, false, false)))
    private fun save(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(activity).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view -> view.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shoot(kind: String) {
        assumeTrue(dir != null)
        for ((language, dark) in listOf("ar" to false, "ar" to true, "en" to false)) {
            RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                            val ready = LibraryLoadState.Ready(library)
                            when (kind) {
                                "all" -> DownloadsScreen(failures + moving, language, {}, {}, {}, {}, {}, null, {}, {},
                                    libraryLoad = ready, playlists = listOf(failedList, playlistFixture()))
                                "failures" -> DownloadsScreen(failures, language, {}, {}, {}, {}, {}, null, {}, {},
                                    libraryLoad = ready, playlists = listOf(failedList))
                                "sheet" -> androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                                    PlaylistVideosContent(staleList, emptyList(), language, {}) }
                                else -> DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(emptyList()))
                            }
                        }
                    }
                }
                idle()
                save(controller.get(), "hub-$kind-$language-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }
    /** Not saved (two downloads + a playlist line, Retry all), then running, paused, "2 waiting", then a running playlist. */
    @Test fun failuresRunningWaiting() = shoot("all")
    /** Only failures: Downloading opens with the error dot. */
    @Test fun onlyFailures() = shoot("failures")
    /** Nothing left: no tab row. */
    @Test fun empty() = shoot("empty")
    /** The playlist sheet: a per-video Retry where the link is kept, the paste-again hint where it is gone. */
    @Test fun staleSheet() = shoot("sheet")
}
