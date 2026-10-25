package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import dev.ahmed.savlino.windowViews
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Downloads v2 review captures: nothing running / one running (ar-light, en-dark) and the Home recents card. Set NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DownloadsV2CaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    // The compose rule owns the effect clock: advance it too, or loads and the underline never run.
    private fun idle() {
        repeat(6) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)); compose.mainClock.advanceTimeBy(250) }
        compose.waitForIdle()
    }
    private fun media(id: Int, name: String, mime: String, height: Int?, size: Long) = SavedMedia(
        Uri.parse("content://media/external_primary/downloads/$id"), name, name.substringBeforeLast('.'), mime, size,
        System.currentTimeMillis() / 1000 - id * 3600, height, null, if (mime.startsWith("image")) null else 84_000,
        relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val library = listOf(media(1, "Morning by the sea.mp4", "video/mp4", 1080, 164_000_000),
        media(2, "رحلة إلى الشاطئ.mp4", "video/mp4", 720, 48_000_000), media(3, "Garden.jpg", "image/jpeg", 1440, 2_400_000),
        media(4, "Evening call.mp3", "audio/mpeg", null, 4_200_000), media(5, "Old harbour.mp4", "video/mp4", 480, 12_000_000))
    private val running = listOf(DownloadProgress("r1", "A quiet afternoon.mp4", DownloadPhase.DOWNLOADING,
        bytes = 42_000_000, total = Size.Exact(100_000_000)))
    private val palettes = listOf(0xFF2E7D7A to 0xFFB2DFDB, 0xFF5D4037 to 0xFFFFCC80, 0xFF283593 to 0xFF90CAF9,
        0xFF6A1B9A to 0xFFF8BBD0, 0xFF33691E to 0xFFDCE775)
    private val pictures = MediaPictures { uri, _, _ ->
        val (from, to) = palettes[(uri.lastPathSegment!!.toInt() - 1).mod(palettes.size)]
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 320f, 180f, Paint().apply {
                shader = LinearGradient(0f, 0f, 320f, 180f, from.toInt(), to.toInt(), Shader.TileMode.CLAMP) })
        }
    }

    private fun save(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(activity).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view -> view.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shoot(name: String, after: () -> Unit = {}, content: @Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for ((language, dark) in listOf("ar" to false, "en" to true)) {
            RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalMediaPictures provides pictures) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(language) }
                    }
                }
                idle()
                after()
                idle()
                windowViews(controller.get()).forEach { it.requestLayout(); it.invalidate() }
                idle()
                save(controller.get(), "$name-$language-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    /** Nothing in Downloading: no tab row, the saved files straight away. */
    @Test fun nothingRunning() = shoot("nothing-running") { language ->
        DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(library))
    }

    /** One download: Downloading · Saved come back, opened on Downloading, underline under the word. */
    @Test fun oneRunning() = shoot("one-running") { language ->
        DownloadsScreen(running, language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(library))
    }

    /** One download, Saved picked: the underline sits under "Saved". */
    @Test fun oneRunningSaved() = shoot("one-running-saved", after = { compose.onNodeWithTag("tab_saved").performClick() }) { language ->
        DownloadsScreen(running, language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(library))
    }

    /** Home recents: the "1080p · 164 MB" line is whole at both ends. */
    @Test fun homeRecents() = shoot("home-recents") { language ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(top = 24.dp)) { HomeRecents(library, language, {}) {} }
        }
    }

    /** Runs last (name order): every capture above on one sheet, a third of the size. */
    @Test fun zOverview() {
        assumeTrue(dir != null)
        val names = listOf("nothing-running", "one-running", "one-running-saved", "home-recents")
        val cell = 384 to 832
        val label = 56
        val sheet = Bitmap.createBitmap(cell.first * 8 + 9 * 16, cell.second + label + 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(0xFFFFFFFF.toInt()) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = 0xFF1B1B1B.toInt() }
        var x = 16
        for (name in names) for (variant in listOf("ar-light", "en-dark")) {
            val file = File(dir, "$name-$variant.png")
            BitmapFactory.decodeFile(file.path)?.let { shot ->
                canvas.drawText("$name · $variant", x.toFloat(), 38f, text)
                canvas.drawBitmap(shot, null, Rect(x, label, x + cell.first, label + cell.second), Paint(Paint.FILTER_BITMAP_FLAG))
                shot.recycle()
            }
            x += cell.first + 16
        }
        File(dir, "overview.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
