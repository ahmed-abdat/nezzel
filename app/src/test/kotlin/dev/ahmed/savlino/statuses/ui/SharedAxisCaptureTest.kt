package dev.ahmed.savlino.statuses.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.SettingsScreen
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.statuses.FakeStatusLibrary
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.APP
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.BUSINESS
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.MAIN
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.SECOND
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.mixed
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Frame strips of the shared axis and of a predictive back drag. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class SharedAxisCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    private class Frame(val label: String, val bitmap: Bitmap)

    private fun grab(activity: ComponentActivity, label: String): Frame {
        val view = activity.window.decorView
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        return Frame(label, Bitmap.createScaledBitmap(full, view.width / 2, view.height / 2, true))
    }

    /** Frames side by side, each under its label, so the owner reads the motion left to right. */
    private fun strip(name: String, frames: List<Frame>) {
        val w = frames[0].bitmap.width; val h = frames[0].bitmap.height; val gap = 12; val head = 56
        val out = Bitmap.createBitmap(frames.size * w + (frames.size - 1) * gap, h + head, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(Color.rgb(128, 128, 128)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f }
        frames.forEachIndexed { i, f ->
            val x = i * (w + gap).toFloat()
            canvas.drawText(f.label, x + 12f, 40f, paint)
            canvas.drawBitmap(f.bitmap, x, head.toFloat(), null)
        }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Opens the deeper page and frames the shared axis, then drags back to .25/.5/.75, cancels, and frames that too. */
    private class Scene(val content: @Composable (String) -> Unit, val open: (ComponentActivity) -> Unit)

    private fun shoot(name: String, scene: () -> Scene) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            val suffix = "$lang-${if (dark) "dark" else "light"}"
            try {
                val (content, open) = scene().let { it.content to it.open }
                activity.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val axis = mutableListOf(grab(activity, "before"))
                open(activity)
                var at = 0L
                for (ms in listOf(16L, 48L, 96L, 160L, 300L)) {
                    compose.mainClock.advanceTimeBy(ms - at); at = ms
                    axis += grab(activity, "$ms ms")
                }
                compose.mainClock.advanceTimeBy(500)
                strip("$name-axis-$suffix", axis)

                val back = activity.onBackPressedDispatcher
                compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 1200f, 0f, BackEventCompat.EDGE_LEFT)) }
                compose.mainClock.advanceTimeByFrame()
                val drag = mutableListOf<Frame>()
                for (p in listOf(.1f, .25f, .5f, .75f)) {
                    compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(p * 1152f, 1200f, p, BackEventCompat.EDGE_LEFT)) }
                    repeat(6) { compose.mainClock.advanceTimeByFrame() }
                    drag += grab(activity, "back $p")
                }
                compose.runOnUiThread { back.dispatchOnBackCancelled() }
                compose.mainClock.advanceTimeBy(500)
                drag += grab(activity, "cancelled")
                strip("$name-back-$suffix", drag)
            } finally {
                compose.mainClock.autoAdvance = true
                controller.pause().stop().destroy(); scope.coroutineContext[Job]?.cancelChildren()
            }
        }
    }

    @Test fun statusesAccount() = shoot("u8-axis-statuses") {
        val fake = FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 9).take(5) +
            mixed(FakeStatusLibrary.THIRD, offset = 18).drop(2).take(3), apps = listOf(APP, BUSINESS)))
        val state = StatusesState(fake, scope).apply { refresh(); setActive(true) }
        Scene({ lang ->
            CompositionLocalProvider(LocalStatusMedia provides FakeStatusMedia()) {
                NavigationScaffold("Statuses", { emptyList() }, lang, {}) { StatusesScreen(fake, lang, state) }
            }
        }) { compose.runOnUiThread { state.open(MAIN); Snapshot.sendApplyNotifications() } }
    }

    @Test fun settingsAdvanced() = shoot("u8-axis-settings") {
        Scene({ lang ->
            SettingsScreen(LinkViewModel(SavedStateHandle()), lang, ThemePref.System, false, true, {}, {}, {}, {}, {}, {}, onClearLinks = {})
        }) { activity -> compose.onNodeWithText(activity.getString(R.string.settings_advanced)).performSemanticsAction(SemanticsActions.OnClick) }
    }
}
