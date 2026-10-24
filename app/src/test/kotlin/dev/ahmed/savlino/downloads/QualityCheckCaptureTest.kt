package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.ResultCheck
import dev.ahmed.savlino.jobs.CheckWarning
import dev.ahmed.savlino.jobs.DownloadNotifications
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.BetterQualityNote
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

/** Design review captures of the result check and Look for better quality. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class QualityCheckCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Sunset timelapse.mp4", title = "Sunset timelapse", mimeType = "video/mp4", size = 48_200_000,
        addedSeconds = 100, width = 640, height = 360, bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private val warnings = listOf(
        CheckWarning("a", "Sunset timelapse.mp4", video.uri, ResultCheck.Problem.NO_SOUND, canRetry = true),
        CheckWarning("b", "Concert clip with a very long name.mp4", null, ResultCheck.Problem.DAMAGED, canRetry = false))

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun click(a: ComponentActivity, label: String) {
        semanticsNodes(a, idleMillis = 250).last {
            it.config.getOrNull(SemanticsActions.OnClick) != null && (texts(descendants(it)).contains(label) ||
                descendants(it).any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) })
        }.config[SemanticsActions.OnClick].action?.invoke()
        idle()
    }

    private fun save(a: ComponentActivity, name: String) {
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View ->
            if (view !== root) canvas.drawColor(0x52000000)
            val x = if (view.width < root.width) (root.width - view.width) / 2f else 0f
            val y = if (view.height < root.height) (root.height - view.height).toFloat() else 0f
            canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
        }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, steps: (ComponentActivity) -> Unit = {}, content: @Composable (String) -> Unit) {
        assumeTrue(dir != null)
        val variants = listOf("en" to false, "en" to true, "ar" to false, "ar" to true, "fr" to false, "fr" to true)
        for ((lang, dark) in variants) for (scale in listOf(1f, 2f)) {
            if (scale == 2f && dark) continue
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    Surface(Modifier.fillMaxSize()) { content(lang) } } }
                idle(); steps(controller.get())
                save(controller.get(), "$name-$lang-${if (dark) "dark" else "light"}${if (scale > 1f) "-200" else ""}")
            } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
        }
    }

    @Test fun needsALook() = shoot("needs-look") { lang ->
        DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)),
            warnings = warnings, onTryAgain = {}, onDismissWarning = {})
    }

    @Test fun sheetRow() = shoot("sheet", { a -> click(a, a.getString(R.string.saved_more, video.title)) }) { lang ->
        CompositionLocalProvider(LocalBetterQuality provides BetterQualityLink(eligible = { true }, open = {})) {
            DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
        }
    }

    @Test fun optionsNote() = shoot("options-note") { lang ->
        Column(Modifier.padding(16.dp)) { BetterQualityNote(360, lang) }
    }

    /** What the default SnackbarHost draws for the app's arguments (the host's enter animation does not settle on the JVM). */
    @Composable private fun snack(lang: String, check: ResultCheck) {
        val context = LocalContext.current
        val visuals = if (check is ResultCheck.Problem) Visuals(context.getString(DownloadNotifications.checkProblem(check)),
            context.getString(R.string.try_again), true)
        else {
            val saved = context.getString(R.string.saved_snackbar, NezzelFormat.isolate("Download/Nezzel"))
            Visuals(DownloadNotifications.checkLine(check)?.let { saved + "\n" + context.getString(it) } ?: saved,
                context.getString(R.string.open_file), false)
        }
        val data = object : SnackbarData {
            override val visuals = visuals
            override fun performAction() {}
            override fun dismiss() {}
        }
        Box(Modifier.fillMaxSize()) { Snackbar(data, Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
    }
    private class Visuals(override val message: String, override val actionLabel: String?, override val withDismissAction: Boolean,
        override val duration: SnackbarDuration = SnackbarDuration.Indefinite) : SnackbarVisuals

    @Test fun snackVideo() = shoot("snack-video") { snack(it, ResultCheck.Verified(true, true)) }
    @Test fun snackAudio() = shoot("snack-audio") { snack(it, ResultCheck.Verified(false, true)) }
    @Test fun snackSilent() = shoot("snack-silent") { snack(it, ResultCheck.NoSoundExpected) }
    @Test fun snackProblem() = shoot("snack-problem") { snack(it, ResultCheck.Problem.NO_SOUND) }
}
