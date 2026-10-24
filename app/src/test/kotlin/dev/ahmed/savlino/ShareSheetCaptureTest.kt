package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.compositionContext
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import kotlinx.coroutines.*
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

/** Design review captures of the share sheet over another app. Runs only with NEZZEL_CAPTURE_DIR. Sheets open only at sdk 29 in the JVM. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class ShareSheetCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val url = "https://youtu.be/one"

    private class Engine(val hang: Boolean) : EngineAdapter by FakeEngine() {
        override val isAvailable = true
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            if (hang) awaitCancellation()
            fun fmt(id: String, h: Int, mb: Long) = Format(id, "mp4", true, true, height = h, size = Size.Exact(mb * 1_000_000))
            return InspectResult.Success(Inspection(source, "2026.08.19", listOf(fmt("1080", 1080, 68), fmt("720", 720, 32), fmt("360", 360, 9)),
                listOf(PresetChoice(Preset.BEST, "1080"), PresetChoice(Preset.BALANCED, "720"), PresetChoice(Preset.SMALL, "360")),
                title = "Sunset over the dunes, Nouakchott"))
        }
    }

    private enum class State { Preview, Checking, Options, Saved, Started }

    private fun shoot(state: State, lang: String, dark: Boolean, fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
        RuntimeEnvironment.setFontScale(fontScale)
        AppLocale.choose(app, lang)
        AppPreferences(app).apply { theme = if (dark) ThemePref.Dark else ThemePref.Light; automaticOptions = state != State.Preview }
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(app)
        if (state == State.Saved) saveEarlier(store)
        app.coordinatorOverride = DownloadCoordinator(app, Engine(hang = state == State.Checking), store,
            workManager = { error("No background scheduler in capture") }, savedFileExists = { true })
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val hint = if (lang == "ar") "غروب الشمس فوق الكثبان" else "Sunset over the dunes"
        val intent = Intent(app, ShareSheetActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "$hint $url")
        val controller = Robolectric.buildActivity(ShareSheetActivity::class.java, intent)
        try {
            controller.create().start().postCreate(null).resume()
            val activity = controller.get()
            activity.window.decorView.compositionContext = recomposer
            controller.visible()
            // Frame times advance 16 ms per frame so the sheet's slide-in animation completes.
            var frameNanos = 0L
            fun settle(rounds: Int = 90) = repeat(rounds) {
                Snapshot.sendApplyNotifications(); frameNanos += 16_000_000; clock.sendFrame(frameNanos)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                windowViews(activity).flatMap(::roots).forEach { it.measureAndLayoutForTest() }
            }
            settle()
            if (state == State.Started) {
                activity.links.requestDownload()
                runBlocking { withTimeout(5000) { while (activity.links.acceptedJob == null) { settle(1); delay(5) } } }
                settle(20)
            }
            save(activity, "share-${state.name.lowercase()}-$lang-${if (dark) "dark" else "light"}${if (fontScale > 1f) "-large" else ""}")
        } finally {
            runBlocking { withTimeout(5000) {
                while (app.scope.coroutineContext[Job]!!.children.any()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            } }
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
            recomposer.cancel(); scope.cancel(); store.close(); app.coordinatorOverride = null
        }
    }

    private fun saveEarlier(store: JobStore) {
        val source = LinkRevision(LinkInput.parse(url)!!, 1)
        val format = Format("720", "mp4", true, true, height = 720)
        val command = (DownloadCommand.confirm("earlier", Inspection(source, "2026.08.19", listOf(format)), source, "720", "clip")
            as ConfirmResult.Confirmed).command
        val uri = Uri.parse("content://media/external/downloads/7")
        store.enqueue(command); store.startTransfer("earlier"); store.markStaged("earlier")
        store.recordPending("earlier", PendingPublication(uri, "clip.mp4")); store.complete("earlier", PublishedFile(uri, "clip.mp4"))
    }

    /** The app it was shared from (a plain stand-in), then every window: the sheet draws its own scrim. */
    private fun save(activity: ShareSheetActivity, name: String) {
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val night = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_YES != 0
        canvas.drawColor(if (night) Color.rgb(24, 24, 27) else Color.rgb(242, 242, 245))
        val bar = android.graphics.Paint().apply { color = if (night) Color.rgb(52, 52, 58) else Color.rgb(222, 222, 228) }
        for (i in 0 until 6) canvas.drawRoundRect(48f, 220f + i * 300f, decor.width - 48f, 470f + i * 300f, 36f, 36f, bar)
        for (view in windowViews(activity)) if (view.width > 0 && view.height > 0) view.draw(canvas)
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun captures() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar", "fr")) for (dark in listOf(false, true)) for (state in State.entries) shoot(state, lang, dark)
        for (lang in listOf("en", "ar", "fr")) for (state in listOf(State.Options, State.Preview)) shoot(state, lang, false, 2f)
    }
}
