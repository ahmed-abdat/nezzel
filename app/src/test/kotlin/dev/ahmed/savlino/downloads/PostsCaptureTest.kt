package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.locale.AppPreferences
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

/** Design review capture of grouped post photos in Downloads. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class PostsCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private fun item(id: Int, name: String, mime: String, added: Long, path: String = "Download/Nezzel/") = SavedMedia(
        uri = Uri.parse("content://media/external_primary/downloads/$id"), filename = name, title = name.substringBeforeLast('.'),
        mimeType = mime, size = 1_400_000L + id * 90_000, addedSeconds = added, width = 1080, height = 1350, bitrateKbps = null,
        durationMillis = if (mime.startsWith("video")) 21000 else null, relativePath = path, volumeName = "external_primary")
    private val items = listOf(
        item(1, "Beach day_01.jpg", "image/jpeg", 1000), item(2, "Beach day_02.jpg", "image/jpeg", 1003),
        item(3, "Beach day_03.mp4", "video/mp4", 1008), item(4, "Sunset timelapse.mp4", "video/mp4", 900),
        item(5, "Status 1.jpg", "image/jpeg", 1100, "Download/Nezzel/Statuses/"))

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    /** Sheets and dialogs live in their own windows: draw every window over the activity. */
    private fun save(a: ComponentActivity, name: String) {
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View ->
            // The JVM does not place dialog windows: center them over a scrim like the system does.
            if (view !== root) canvas.drawColor(0x52000000)
            val x = if (view.width < root.width) (root.width - view.width) / 2f else 0f
            val y = if (view.height < root.height) (root.height - view.height) / 2f else 0f
            canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
        }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, steps: (ComponentActivity) -> Unit, content: @androidx.compose.runtime.Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                idle(); steps(controller.get())
                save(controller.get(), "$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun librarySortMenu() {
        assumeTrue(dir != null)
        AppPreferences(RuntimeEnvironment.getApplication()).savedSort = SavedSort.NEWEST
        shoot("library-sort", steps = { a ->
            val label = a.getString(R.string.saved_sort_accessibility, a.getString(R.string.saved_sort_newest))
            val button = semanticsNodes(a).single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            check(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            idle(); semanticsNodes(a)
        }) { lang ->
            DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items))
        }
    }

    @Test fun libraryRenameDialog() = shoot("library-rename", steps = { a -> idle(); semanticsNodes(a) }) { lang ->
        DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items))
        SavedRenameDialog(items.first(), onDismiss = {}, onRename = { null }, onComplete = {})
    }

    @Test fun groupedPost() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items))
                } }
                idle(); save(controller.get(), "posts-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }
}
