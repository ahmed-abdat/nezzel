package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
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

/** Design review captures of the Pause all / Retry all header. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class BatchCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val running = listOf(
        DownloadProgress("first", "Sunset timelapse.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000, total = Size.Exact(100_000_000),
            qualityHeight = 1080, bytesPerSecond = 2_400_000.0, remainingTransferSeconds = 24, stalled = false),
        DownloadProgress("second", "Street food tour.mp4", DownloadPhase.DOWNLOADING, bytes = 12_000_000, total = Size.Exact(60_000_000),
            qualityHeight = 720, bytesPerSecond = 1_100_000.0, remainingTransferSeconds = 44, stalled = false),
        DownloadProgress("third", "Mountain bike trail.mp4", DownloadPhase.PREPARING, total = Size.Exact(80_000_000), waiting = true))
    private val failed = listOf("Live session.m4a", "Harbour at night.mp4", "Cooking class.mp4").mapIndexed { i, name ->
        DownloadProgress("failed-$i", name, DownloadPhase.FAILED, mimeType = if (i == 0) "audio/mp4" else "video/mp4",
            error = DownloadError.Network, retryable = true)
    }

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, jobs: List<DownloadProgress>) {
        assumeTrue(dir != null)
        for ((lang, dark, scale) in listOf(Triple("en", false, 1f), Triple("ar", true, 1f), Triple("fr", false, 1f), Triple("en", false, 1.3f))) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    CompositionLocalProvider(LocalLinkCopy provides LinkCopy(find = { null }, copy = {})) {
                        DownloadsScreen(jobs, lang, {}, {}, {}, {}, {}, null, {}, {})
                    }
                } }
                idle()
                save(controller.get().window.decorView, "$name-$lang-${if (dark) "dark" else "light"}${if (scale != 1f) "-font$scale" else ""}")
            } finally { controller.pause().stop().destroy() }
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun pauseAll() = shoot("pause-all", running)
    @Test fun retryAll() = shoot("retry-all", failed)
}
