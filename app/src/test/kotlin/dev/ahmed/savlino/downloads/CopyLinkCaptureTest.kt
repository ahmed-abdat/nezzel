package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.SettingsScreen
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.LinkViewModel
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

/** Design review captures of Copy link and Clear link history. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class CopyLinkCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Sunset timelapse.mp4", title = "Sunset timelapse", mimeType = "video/mp4", size = 48_200_000,
        addedSeconds = 100, width = 1920, height = 1080, bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private val copy = LinkCopy(find = { "https://www.youtube.com/watch?v=abc" }, copy = {})

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun click(a: ComponentActivity, label: String) {
        val node = semanticsNodes(a, idleMillis = 250).last {
            it.config.getOrNull(SemanticsActions.OnClick) != null && (texts(descendants(it)).contains(label) ||
                descendants(it).any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) })
        }
        node.config[SemanticsActions.OnClick].action?.invoke()
        idle()
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

    // The sheet window does not open under the API 35 JVM runtime; API 29 matches the UI tests.
    @Test @Config(sdk = [29]) fun savedFileSheet() = shoot("sheet", { a -> click(a, a.getString(R.string.saved_more, video.title)) }) { lang ->
        CompositionLocalProvider(LocalLinkCopy provides copy) {
            DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
        }
    }

    @Test fun settingsPrivacy() = shoot("settings", {}) { lang -> settings(lang) }

    @Test fun clearDialog() = shoot("clear-dialog", { a -> click(a, a.getString(R.string.clear_link_history)) }) { lang -> settings(lang) }

    @androidx.compose.runtime.Composable private fun settings(lang: String) {
        val vm = androidx.compose.runtime.remember { LinkViewModel(SavedStateHandle()) }
        SettingsScreen(vm, lang, ThemePref.System, false, true, {}, {}, {}, {}, {}, {}, updates = null, onClearLinks = {})
    }
}
