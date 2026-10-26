package dev.ahmed.savlino.statuses.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.statuses.FakeStatusLibrary
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.APP
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.BUSINESS
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.MAIN
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.SECOND
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.mixed
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusGrantResult
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.statuses.StatusSaveResult
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
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

/** Owner review captures of the Statuses tab with synthetic fixtures. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class StatusesCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private var probe: StatusesState? = null
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private fun idle() = repeat(6) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun save(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.width, view.height)
        idle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, library: () -> FakeStatusLibrary?, fonts: Boolean = false, dialog: Boolean = false,
                      u3: Boolean = false, setup: suspend StatusesState.() -> Unit = {}) {
        assumeTrue(dir != null)
        // Save-flow review set: EN and AR in both themes, Arabic at twice the text size.
        val variants = if (u3) listOf("en", "ar").flatMap { lang -> listOf(Triple(lang, false, 1f), Triple(lang, true, 1f)) } +
            listOf(Triple("ar", false, 2f))
        else listOf("en", "ar", "fr").flatMap { lang -> listOf(Triple(lang, false, 1f), Triple(lang, true, 1f)) } +
            (if (fonts) listOf("en", "ar", "fr").map { Triple(it, false, 1.3f) } else emptyList())
        for ((lang, dark, scale) in variants) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                val fake = library()
                // Load and set up before the first frame: later coroutine writes do not reliably redraw in a reused sandbox.
                val state = StatusesState(fake, scope).also { probe = it }
                state.refresh()
                state.setActive(true)
                scope.launch { state.setup() }
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    CompositionLocalProvider(LocalStatusMedia provides FakeStatusMedia()) {
                        NavigationScaffold("Statuses", emptyList(), lang, {}) { StatusesScreen(fake, lang, state) }
                    }
                } }
                idle()
                // State loaded by coroutines lands after the first frame: force a fresh layout and frame before drawing.
                controller.get().window.decorView.apply { requestLayout(); invalidate() }
                idle()
                val view = if (dialog) topWindow() else controller.get().window.decorView
                save(view, "$name-$lang-${if (dark) "dark" else "light"}${if (scale != 1f) "-font$scale" else ""}")
            } finally { controller.pause().stop().destroy(); scope.coroutineContext[Job]?.cancelChildren() }
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    /** The preview dialog's window: the newest root view the window manager holds. */
    @Suppress("UNCHECKED_CAST")
    private fun topWindow(): View {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.last()
    }

    private fun first(state: StatusesState, n: Int): List<StatusItem> = state.visible.take(n)

    @Test fun notInstalled() = shoot("1-not-installed", { FakeStatusLibrary(StatusAccess.NotInstalled) })
    @Test fun needsGrant() = shoot("2-grant", { FakeStatusLibrary(StatusAccess.NeedsGrant(listOf(APP, BUSINESS))) }, fonts = true)
    @Test fun needsGrantOneApp() = shoot("2-grant-one-app", { FakeStatusLibrary(StatusAccess.NeedsGrant(listOf(APP))) })
    @Test fun wrongFolder() = shoot("3-wrong-folder", { FakeStatusLibrary(StatusAccess.NeedsGrant(listOf(APP)), grant = StatusGrantResult.WrongFolder) }) {
        picked(null)
    }
    @Test fun accessLost() = shoot("4-access-lost", { FakeStatusLibrary(StatusAccess.AccessLost(listOf(APP))) })
    @Test fun empty() = shoot("5-empty", { FakeStatusLibrary(ready(emptyList(), emptyList())) })
    @Test fun grid() = shoot("6-grid", { FakeStatusLibrary(ready()) }, fonts = true)
    private fun threeAccounts() = FakeStatusLibrary(ready(mixed(MAIN) + mixed(SECOND, offset = 9).take(5) +
        mixed(FakeStatusLibrary.THIRD, offset = 18).drop(2).take(3), apps = listOf(APP, BUSINESS)))
    @Test fun accounts() = shoot("7-accounts", ::threeAccounts, fonts = true)
    @Test fun accountOpen() = shoot("7-account-open", ::threeAccounts, fonts = true, setup = { open(MAIN) })
    @Test fun selecting() = shoot("8-selecting", { FakeStatusLibrary(ready()) }, fonts = true) { first(this, 3).forEach(::toggle) }
    @Test fun saving() = shoot("9-saving", { FakeStatusLibrary(ready()).apply { hold = kotlinx.coroutines.CompletableDeferred() } }) {
        first(this, 3).forEach(::toggle)
        save(selectedItems)
    }
    @Test fun savedSnackbar() = shoot("11-saved-snackbar", { FakeStatusLibrary(ready()) }) { save(first(this, 2)) }
    @Test fun previewVideo() = shoot("10-preview-video", { FakeStatusLibrary(ready()) }, dialog = true) { preview = visible[0] }
    @Test fun preview() = shoot("10-preview", { FakeStatusLibrary(ready()) }, dialog = true) { preview = visible[1] }
    @Test fun previewSaved() = shoot("10-preview-saved", { FakeStatusLibrary(ready()) }, dialog = true) { preview = visible[3] }

    // U3 save flow: variant A (Save row under the picture) and B (round Save on the corner); the owner picks one.
    private fun gated(): FakeStatusLibrary = FakeStatusLibrary(ready()).apply {
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = { if (it.uri.lastPathSegment != "status-1") wait.await() }
    }
    private fun partly() = FakeStatusLibrary(ready()).apply {
        results = { if (it.uri.lastPathSegment == "status-3") dev.ahmed.savlino.statuses.StatusSaveResult.Failed
            else dev.ahmed.savlino.statuses.StatusSaveResult.Saved(android.net.Uri.parse("content://media/external/file/1")) }
    }
    @Test fun u3Grid() = shoot("u3-grid", { FakeStatusLibrary(ready()) }, u3 = true)
    @Test fun u3Select() = shoot("u3-select", { FakeStatusLibrary(ready()) }, u3 = true) { enterSelect(); first(this, 3).filter { !it.saved }.forEach(::toggle) }
    @Test fun u3SelectAll() = shoot("u3-select-all", { FakeStatusLibrary(ready()) }, u3 = true) { selectAll() }
    @Test fun u3Saving() = shoot("u3-saving", ::gated, u3 = true) { save(first(this, 3)) }
    @Test fun u3SavingBatch() = shoot("u3-saving-batch", ::gated, u3 = true) { selectAll(); save(selectedItems) }
    @Test fun u3Saved() = shoot("u3-saved", { FakeStatusLibrary(ready()) }, u3 = true) { save(first(this, 1)) }
    @Test fun u3PartialFailure() = shoot("u3-partial", ::partly, u3 = true) { first(this, 3).forEach(::toggle); save(selectedItems) }
    @Test fun u3PreviewSaved() = shoot("u3-preview-after-save", { FakeStatusLibrary(ready()) }, dialog = true, u3 = true) {
        preview = visible[0]; save(listOf(visible[0]))
    }
}
