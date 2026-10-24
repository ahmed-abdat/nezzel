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

/** Downloading · Saved review captures (en/ar × light/dark). Set NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class RunningCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private fun idle() = repeat(6) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
    private fun media(id: Int, name: String, mime: String, path: String = "Download/Nezzel/") = SavedMedia(
        Uri.parse("content://media/external_primary/downloads/$id"), name, name.substringBeforeLast('.'), mime, 4_200_000L,
        System.currentTimeMillis() / 1000 - id * 60, 720, null, if (mime.startsWith("video")) 84_000 else null,
        relativePath = path, volumeName = "external_primary")
    private val library = listOf(media(1, "Morning by the sea.mp4", "video/mp4"), media(2, "Status_20261024.jpg", "image/jpeg", "Download/Nezzel/Statuses/"),
        media(3, "Evening call.mp3", "audio/mpeg"), media(4, "Status_20261023.mp4", "video/mp4", "Download/Nezzel/Statuses/"))
    private val jobs = listOf(
        DownloadProgress("r1", "Morning by the sea.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000, total = Size.Exact(100_000_000)),
        DownloadProgress("p1", "رحلة إلى الشاطئ.mp4", DownloadPhase.DOWNLOADING, paused = true, bytes = 18_000_000, total = Size.Exact(100_000_000), hasPartial = true),
        DownloadProgress("w1", "A quiet afternoon.mp4", DownloadPhase.PREPARING, waiting = true),
        DownloadProgress("w2", "Coastal live.mp4", DownloadPhase.PREPARING, waiting = true),
        DownloadProgress("w3", "Old harbour.mp4", DownloadPhase.PREPARING, waiting = true),
        DownloadProgress("f1", "Night market.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = true),
        DownloadProgress("job-b", "A quiet afternoon · مساء هادئ.mp4", DownloadPhase.DOWNLOADING, bytes = 64_000_000, total = Size.Exact(100_000_000)))
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
        for (language in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                val warnings = listOf(CheckWarning("c1", "Morning by the sea.mp4", library[0].uri, ResultCheck.Problem.NO_SOUND, true))
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                            when (kind) {
                                "running" -> DownloadsScreen(jobs, language, {}, {}, {}, {}, {}, null, {}, {},
                                    libraryLoad = LibraryLoadState.Ready(library), playlists = listOf(playlistFixture()), warnings = warnings)
                                "saved" -> DownloadsScreen(jobs.filter { it.phase == DownloadPhase.FAILED }, language, {}, {}, {}, {}, {}, null, {}, {},
                                    libraryLoad = LibraryLoadState.Ready(library), warnings = warnings)
                                else -> DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {},
                                    libraryLoad = LibraryLoadState.Ready(library))
                            }
                        }
                    }
                }
                idle()
                save(controller.get(), "downloads-$kind-$language-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }
    /** Downloading tab: running, paused, a playlist header with its running video, failed, "3 waiting", Needs a look. */
    @Test fun runningTab() = shoot("running")
    /** Only a failure and a warning left: tabs stay with the dot, entry opens Saved; Statuses chip ends the filter row. */
    @Test fun savedTabWithDot() = shoot("saved")
    /** Nothing in Downloading: no tab row, the library alone. */
    @Test fun libraryAlone() = shoot("library")
}
