package dev.ahmed.savlino.downloads

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.shortcuts.NezzelWidgetProvider
import dev.ahmed.savlino.shortcuts.WidgetUiState
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.ui.theme.Space
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

/** Design review captures of 2–3 downloads at once. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class ParallelCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val first = DownloadProgress("first", "Sunset timelapse.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000,
        total = Size.Exact(100_000_000), qualityHeight = 1080, bytesPerSecond = 2_400_000.0, remainingTransferSeconds = 24, stalled = false)
    private val second = DownloadProgress("second", "Street food tour.mp4", DownloadPhase.DOWNLOADING, bytes = 12_000_000,
        total = Size.Exact(60_000_000), qualityHeight = 720, bytesPerSecond = 1_100_000.0, remainingTransferSeconds = 44, stalled = false)
    private val queued = DownloadProgress("queued", "Mountain bike trail.mp4", DownloadPhase.PREPARING, total = Size.Exact(80_000_000), waiting = true)
    private val failed = DownloadProgress("failed", "Live session.m4a", DownloadPhase.FAILED, mimeType = "audio/mp4",
        error = DownloadError.Network, retryable = true)
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Harbour at night.mp4", title = "Harbour at night", mimeType = "video/mp4", size = 48_200_000,
        addedSeconds = 100, width = 1920, height = 1080, bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")

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

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                idle()
                save(controller.get().window.decorView, "$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun downloads() = shoot("downloads") { lang ->
        CompositionLocalProvider(LocalLinkCopy provides LinkCopy(find = { null }, copy = {})) {
            DownloadsScreen(listOf(first, failed, second, queued), lang, {}, {}, {}, {}, {}, null, {}, {},
                libraryLoad = LibraryLoadState.Ready(listOf(video)))
        }
    }

    @Test fun homeStrip() = shoot("home") { lang ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(Space.gutter)) {
            DownloadStrip(listOf(first, second), lang) {}
        }
    }

    @Test fun homeStripWithQueue() = shoot("home-queue") { lang ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(Space.gutter)) {
            DownloadStrip(listOf(first, failed, second, queued), lang) {}
        }
    }

    @Test fun widget() {
        assumeTrue(dir != null)
        val state = WidgetUiState.from(listOf(first, second, queued))
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(lang)); setLayoutDirection(locale)
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val context = app.createConfigurationContext(config)
            val density = context.resources.displayMetrics.density
            // A home screen 4×1 cell row, on a plain wallpaper tone.
            val host = FrameLayout(context).apply { setBackgroundColor(if (dark) 0xFF1B1F1E.toInt() else 0xFFDDE4E1.toInt()) }
            val pad = (16 * density).toInt()
            host.setPadding(pad, pad, pad, pad)
            host.addView(NezzelWidgetProvider.views(context, state, false).apply(context, host))
            val width = (360 * density).toInt(); val height = (72 * density).toInt() + 2 * pad
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            save(host, "widget-$lang-${if (dark) "dark" else "light"}")
        }
    }
}
