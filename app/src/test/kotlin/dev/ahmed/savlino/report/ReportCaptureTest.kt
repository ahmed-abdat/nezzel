package dev.ahmed.savlino.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsSheetContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.windowViews
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design review captures of the failure report sheet and its Sent/failed outcomes. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Sheet windows do not open under the API 35 JVM runtime; API 29 matches the UI tests.
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class ReportCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val link = "https://www.example.com/watch?v=abc123"
    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun click(a: ComponentActivity, label: String) {
        val node = semanticsNodes(a, idleMillis = 250).last {
            it.config.getOrNull(SemanticsActions.OnClick) != null && texts(descendants(it)).contains(label)
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
            if (view !== root && view.height < root.height) canvas.drawColor(0x52000000)
            val x = if (view.width < root.width) (root.width - view.width) / 2f else 0f
            val y = if (view.height < root.height) (root.height - view.height) / 2f else 0f
            canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
        }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, steps: (ComponentActivity) -> Unit = {}, content: @Composable (String) -> Unit) {
        assumeTrue(dir != null)
        val variants = listOf("en", "ar", "fr").flatMap { lang -> listOf(Triple(lang, false, 1f), Triple(lang, true, 1f)) } +
            Triple("ar", false, 1.3f)
        for ((lang, dark, font) in variants) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(font)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                idle(); steps(controller.get())
                save(controller.get(), "$name-$lang-${if (dark) "dark" else "light"}${if (font > 1f) "-font130" else ""}")
            } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
        }
    }

    private fun failed(sent: Boolean) = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Failure(DownloadError.Failed)),
        currentEngineVersion = { "2026.10.01" }, sendReport = { true }).also {
        it.edit(link); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        if (sent) { it.sendFailureReport("0.5.0", 35); shadowOf(Looper.getMainLooper()).idle() }
    }

    @Composable private fun sheetScene() {
        val vm = remember { failed(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        // Opened after the first frame, like a tap: a sheet shown in the first composition never animates in on the JVM.
        DisposableEffect(Unit) { onDispose { open = false } }
        if (open) FailureReportSheet({ vm.failureReport("0.5.0", 35) }, {}, {})
    }

    private var open by mutableStateOf(false)

    @Test fun sheet() = shoot("sheet", { open = true; idle() }) { sheetScene() }

    /** The failure card after Send, with the snackbar the app shows over the options sheet. */
    @Test fun sent() = shoot("sent") { lang ->
        val vm = remember { failed(true) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box {
                OptionsSheetContent(vm, lang) {}
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(stringResource(R.string.report_sent_snackbar)) }
            }
        }
    }

    @Test fun sendFailed() = shoot("send-failed") { lang ->
        val vm = remember { failed(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box {
                OptionsSheetContent(vm, lang) {}
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(stringResource(R.string.report_failed_snackbar)) }
            }
        }
    }
}
