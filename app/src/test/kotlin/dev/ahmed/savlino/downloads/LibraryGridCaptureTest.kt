package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import dev.ahmed.savlino.viewer.MediaViewer
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

/** Owner review captures of the Downloads grid and the swipe viewer, synthetic pictures only. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class LibraryGridCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val now = System.currentTimeMillis() / 1000
    private fun item(id: Int, name: String, mime: String, hoursAgo: Int, duration: Long? = null) = SavedMedia(
        uri = Uri.parse("content://media/external_primary/downloads/$id"), filename = name, title = name.substringBeforeLast('.'),
        mimeType = mime, size = 1_400_000L + id * 390_000, addedSeconds = now - hoursAgo * 3600L, width = 1080, height = 1350,
        bitrateKbps = null, durationMillis = duration, relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val items = listOf(
        item(1, "Beach day.jpg", "image/jpeg", 1), item(2, "Street food.mp4", "video/mp4", 2, 48_000),
        item(3, "Mountain view.jpg", "image/jpeg", 3), item(4, "Recipe.mp4", "video/mp4", 4, 135_000),
        item(5, "Morning podcast.m4a", "audio/mp4", 5, 1_820_000), item(6, "City lights.jpg", "image/jpeg", 6),
        item(7, "Garden.jpg", "image/jpeg", 30), item(8, "Birthday.mp4", "video/mp4", 31, 21_000),
        item(9, "Old photo.jpg", "image/jpeg", 32), item(10, "Sunset.jpg", "image/jpeg", 33))

    private val palettes = listOf(0xFF2E7D7A to 0xFFB2DFDB, 0xFF5D4037 to 0xFFFFCC80, 0xFF283593 to 0xFF90CAF9,
        0xFF6A1B9A to 0xFFF8BBD0, 0xFF33691E to 0xFFDCE775, 0xFF37474F to 0xFFB0BEC5, 0xFFBF360C to 0xFFFFE0B2)
    private val pictures = MediaPictures { uri, _, _ ->
        val (from, to) = palettes[(uri.lastPathSegment!!.toInt() - 1).mod(palettes.size)]
        Bitmap.createBitmap(270, 340, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 270f, 340f, Paint().apply {
                shader = LinearGradient(0f, 0f, 270f, 340f, from.toInt(), to.toInt(), Shader.TileMode.CLAMP) })
        }
    }

    private fun idle() = repeat(6) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun save(a: ComponentActivity, name: String) {
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View -> view.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, pics: MediaPictures = pictures, content: @Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    CompositionLocalProvider(LocalMediaPictures provides pics) { content(lang) }
                } }
                idle()
                // Pictures land after the first frame: lay every window out again before drawing.
                windowViews(controller.get()).forEach { it.requestLayout(); it.invalidate() }
                idle()
                save(controller.get(), "$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun grid() {
        assumeTrue(dir != null)
        AppPreferences(RuntimeEnvironment.getApplication()).setLibraryGrid(MediaFilter.ALL.name, true)
        shoot("1-grid") { lang -> DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items)) }
    }

    @Test fun list() {
        assumeTrue(dir != null)
        AppPreferences(RuntimeEnvironment.getApplication()).setLibraryGrid(MediaFilter.ALL.name, false)
        shoot("2-list-toggle") { lang -> DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items)) }
    }

    @Test fun viewerImage() = shoot("3-viewer-image") { lang ->
        val pages = viewerItems(items)
        MediaViewer(pages, 2, lang, {}, {}, {})
    }

    @Test fun viewerVideo() = shoot("4-viewer-video") { lang ->
        val pages = viewerItems(items)
        MediaViewer(pages, 1, lang, {}, {}, {})
    }

    // Robolectric's VideoView freezes the captured frame on the first draw, so video/audio covers never show here.
    @Test fun viewerAudio() = shoot("5-viewer-audio") { lang ->
        val pages = viewerItems(items)
        MediaViewer(pages, 4, lang, {}, {}, {})
    }

    @Test fun viewerAudioNoCover() = shoot("6-viewer-audio-no-cover", MediaPictures { _, _, _ -> null }) { lang ->
        val pages = viewerItems(items)
        MediaViewer(pages, 4, lang, {}, {}, {})
    }
}
