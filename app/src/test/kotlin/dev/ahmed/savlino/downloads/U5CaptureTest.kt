package dev.ahmed.savlino.downloads

import android.content.Intent
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.AdvancedSettings
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.roots
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.sharing.FileShare
import dev.ahmed.savlino.sharing.FileSharer
import dev.ahmed.savlino.sharing.LocalFileSharer
import dev.ahmed.savlino.sharing.ShareTarget
import dev.ahmed.savlino.sharing.ShareTargetsSheet
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsSheetContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.windowViews
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
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

/** U5 review captures (en/ar × light/dark, ar at 200%): paused, Saved moment, share, empty Paste, clear history, Options action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class U5CaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val context get() = RuntimeEnvironment.getApplication()
    // Each activity gets its own Recomposer and frame clock: the shared window Recomposer gets no frames after the first,
    // so later state (a job ending, a sheet opening, qualities landing) would never draw.
    private var clock = androidx.compose.runtime.BroadcastFrameClock()
    private var activity: ComponentActivity? = null
    private var frameNanos = 0L
    /** [times] × 250 ms of 16 ms frames. */
    private fun idle(times: Int = 6) = repeat(times * 16) {
        Snapshot.sendApplyNotifications(); frameNanos += 16_000_000; clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        activity?.let { a -> windowViews(a).flatMap(::roots).forEach { it.measureAndLayoutForTest() } }
    }
    private val video = SavedMedia(Uri.parse("content://media/external_primary/downloads/1"), "Morning by the sea.mp4",
        "Morning by the sea", "video/mp4", 4_200_000L, System.currentTimeMillis() / 1000 - 60, 720, null, 84_000,
        relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val running = DownloadProgress("r1", "Night market.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000, total = Size.Exact(100_000_000))
    private val paused = DownloadProgress("p1", "رحلة إلى الشاطئ.mp4", DownloadPhase.DOWNLOADING, paused = true,
        bytes = 18_000_000, total = Size.Exact(100_000_000), hasPartial = true)
    private val finishing = DownloadProgress("s1", "Morning by the sea.mp4", DownloadPhase.DOWNLOADING, bytes = 96_000_000, total = Size.Exact(100_000_000))

    private fun click(a: ComponentActivity, label: String) {
        semanticsNodes(a, idleMillis = 250).last {
            it.config.getOrNull(SemanticsActions.OnClick) != null && (texts(descendants(it)).contains(label) ||
                descendants(it).any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) })
        }.config[SemanticsActions.OnClick].action?.invoke()
        idle()
    }

    private fun install(pkg: String, label: String) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; enabled = true; nonLocalizedLabel = label }
        })
        val send = android.content.ComponentName(pkg, "$pkg.Send")
        pm.addActivityIfNotPresent(send)
        pm.addIntentFilterForActivity(send, android.content.IntentFilter(Intent.ACTION_SEND, "video/*").apply { addCategory(Intent.CATEGORY_DEFAULT) })
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
        bitmap.recycle()
    }

    private fun shoot(name: String, steps: (ComponentActivity, String) -> Unit = { _, _ -> }, content: @Composable (String) -> Unit) {
        assumeTrue(dir != null)
        val variants = listOf(Triple("en", false, 1f), Triple("en", true, 1f), Triple("ar", false, 1f), Triple("ar", true, 1f), Triple("ar", false, 2f))
        for ((lang, dark, scale) in variants) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            clock = androidx.compose.runtime.BroadcastFrameClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
            val recomposer = Recomposer(scope.coroutineContext)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
            controller.get().window.decorView.compositionContext = recomposer
            controller.visible()
            activity = controller.get()
            try {
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { Surface(Modifier.fillMaxSize()) { content(lang) } }
                    }
                }
                idle(); steps(controller.get(), lang)
                save(controller.get(), "$name-$lang-${if (dark) "dark" else "light"}${if (scale > 1f) "-200" else ""}")
            } finally {
                controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f)
                activity = null; recomposer.cancel(); scope.cancel()
            }
        }
    }

    /** Running and paused rows: the paused one is still (no pulse) and says Paused. */
    @Test fun runningAndPaused() = shoot("u5-running-paused") { lang ->
        DownloadsScreen(listOf(running, paused), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
    }

    /** A job seen live ends through `completed` (the coordinator drops it from its list): the row flashes Saved. */
    private val jobs = mutableStateOf(listOf<DownloadProgress>())
    private val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
    @Test fun savedMoment() = shoot("u5-saved-moment", { _, _ ->
        jobs.value = listOf(running)
        completed.tryEmit(finishing.copy(phase = DownloadPhase.COMPLETED, bytes = 100_000_000))
        // Shorter than Motion.SAVED_DWELL, so the moment is still on screen.
        idle(3)
    }) { lang ->
        LaunchedEffect(Unit) { jobs.value = listOf(finishing, running) }
        DownloadsScreen(jobs.value, lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)),
            completed = completed)
    }

    /** The saved-file sheet with both WhatsApp apps installed: their own names, then Other apps (JVM shows the share glyph). */
    @Test fun savedSheetShare() {
        install(FileShare.WHATSAPP, "WhatsApp")
        install(FileShare.WHATSAPP_BUSINESS, "WA Business")
        shoot("u5-saved-sheet", { a, _ ->
            click(a, a.getString(R.string.saved_more, video.title))
            idle(8)
        }) { lang ->
            val activity = LocalContext.current
            val scope = rememberCoroutineScope()
            val sharer = remember { FileSharer(activity, UsageEvents.None, scope, { }) }
            CompositionLocalProvider(LocalFileSharer provides sharer) {
                DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
            }
        }
    }

    /** The "Saved" snackbar's WhatsApp with both apps installed opens this choice. */
    @Test fun shareChoiceSheet() = shoot("u5-share-choice", { _, _ -> idle(8) }) {
        ShareTargetsSheet(listOf(ShareTarget(FileShare.WHATSAPP, "WhatsApp"), ShareTarget(FileShare.WHATSAPP_BUSINESS, "WA Business")), {}, {})
    }

    /** What the default SnackbarHost draws for the app's arguments (its enter animation does not settle on the JVM). */
    @Composable private fun snack(message: String, action: String?) {
        val data = object : SnackbarData {
            override val visuals = object : SnackbarVisuals {
                override val message = message
                override val actionLabel = action
                override val withDismissAction = false
                override val duration = SnackbarDuration.Long
            }
            override fun performAction() {}
            override fun dismiss() {}
        }
        Box(Modifier.fillMaxSize()) { Snackbar(data, Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
    }
    @Composable private fun savedLine(): String {
        val context = LocalContext.current
        val saved = context.getString(R.string.saved_snackbar, NezzelFormat.isolate("Download/Nezzel"))
        return DownloadNotifications.checkLine(ResultCheck.Verified(true, true))?.let { saved + "\n" + context.getString(it) } ?: saved
    }

    /** One file saved, one WhatsApp installed: the action carries the app's own name. */
    @Test fun snackWhatsApp() = shoot("u5-snack-whatsapp") { snack(savedLine(), "WhatsApp") }
    /** One file saved, both installed: the action says WhatsApp and opens the choice. */
    @Test fun snackBoth() = shoot("u5-snack-both") { snack(savedLine(), LocalContext.current.getString(R.string.share_whatsapp)) }
    /** One file saved, no WhatsApp: Share opens the chooser. */
    @Test fun snackShare() = shoot("u5-snack-share") { snack(savedLine(), LocalContext.current.getString(R.string.share_file)) }
    /** Several files saved in one run: View files. */
    @Test fun snackBatch() = shoot("u5-snack-batch") { snack(savedLine(), LocalContext.current.getString(R.string.view_saved_files)) }
    /** Paste with nothing copied. */
    @Test fun snackEmptyPaste() = shoot("u5-snack-empty-paste") { snack(LocalContext.current.getString(R.string.paste_empty), null) }
    /** The file was moved or deleted before sharing. */
    @Test fun snackFileGone() = shoot("u5-snack-file-gone") { snack(LocalContext.current.getString(R.string.share_file_unavailable), null) }

    /** Clear link history while it runs: the row is disabled and says Clearing. */
    @Test fun clearBusy() = shoot("u5-clear-busy") { lang ->
        AdvancedSettings(null, {}, null, onClearLinks = {}, clearingLinks = true, language = lang, back = {})
    }
    /** And when it is done. */
    @Test fun clearDone() = shoot("u5-clear-done") { lang ->
        Box {
            AdvancedSettings(null, {}, null, onClearLinks = {}, language = lang, back = {})
            snack(LocalContext.current.getString(R.string.clear_link_history_done), null)
        }
    }

    /** Options while checking (placeholder) and once ready (Download): the action sits in the same place. */
    private fun options(name: String, ready: Boolean) {
        val gate = CompletableDeferred<Unit>()
        if (ready) gate.complete(Unit)
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                gate.await()
                return InspectResult.Success(Inspection(source, "fixture-v1",
                    listOf(Format("v720", "mp4", true, true, height = 720, size = Size.Exact(42_000_000)),
                        Format("v360", "mp4", true, true, height = 360, size = Size.Exact(12_000_000)),
                        Format("a", "m4a", false, true, size = Size.Exact(3_000_000))),
                    listOf(PresetChoice(Preset.BALANCED, "v720"), PresetChoice(Preset.SMALL, "v360"), PresetChoice(Preset.AUDIO_M4A, "a"))))
            }
        }
        shoot(name, { _, _ -> idle(8) }) { lang ->
            val links = remember { LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "fixture-v1" }) }
            LaunchedEffect(Unit) { links.paste("https://example.com/video") }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { OptionsSheetContent(links, lang, {}) }
        }
    }
    @Test fun optionsChecking() = options("u5-options-checking", ready = false)
    @Test fun optionsReady() = options("u5-options-ready", ready = true)
}
