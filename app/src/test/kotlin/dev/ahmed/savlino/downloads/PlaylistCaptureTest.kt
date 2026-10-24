package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.BatchState
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.windowViews
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

/** 16 review captures. Set NEZZEL_CAPTURE_DIR; SDK29 is required for sheet popup rendering. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class PlaylistCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val engine = object : EngineAdapter by FakeEngine() {
        override suspend fun inspect(source: LinkRevision, operationId: String) = InspectResult.Success(Inspection(source, "fixture",
            listOf(Format("18", "mp4", true, true, height = 720)), listOf(PresetChoice(Preset.BALANCED, "18")), title = "A walk along the coast"))
        override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) = PlaylistResult.Success(
            PlaylistPage("Coastal walks · جولات على الساحل", listOf(
                PlaylistItem("abcdefghijk", "https://youtube.com/watch?v=abcdefghijk", "Morning by the sea", 242),
                PlaylistItem("bcdefghijkl", "https://youtube.com/watch?v=bcdefghijkl", "A quiet afternoon · مساء هادئ", 380),
                PlaylistItem("cdefghijklm", "https://youtube.com/watch?v=cdefghijklm", "Coastal live stream", null, unavailable = PlaylistUnavailable.Live),
                PlaylistItem("defghijklmn", "https://youtube.com/watch?v=defghijklmn", "Private video", null, unavailable = PlaylistUnavailable.Private)), 4, null))
    }
    private fun idle() = repeat(6) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
    private fun save(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(activity).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view ->
            if (view !== root) canvas.drawColor(0x52000000)
            val x = if (view.width < root.width) (root.width - view.width) / 2f else 0f
            // Bottom sheets anchor to the bottom; composite the scrim and popup over the activity.
            val y = if (view.height < root.height) (root.height - view.height).toFloat() else 0f
            canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
        }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shoot(kind: String) {
        assumeTrue(dir != null)
        for (language in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val links = LinkViewModel(SavedStateHandle(), engine = engine)
            val playlist = PlaylistViewModel(engine, scope, { it.id == "bcdefghijkl" }, { _, _, _ -> true })
            try {
                if (kind == "options") links.paste("https://youtube.com/watch?v=abcdefghijk&list=PLabcdefghij")
                if (kind == "sheet") playlist.open(LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1))
                idle()
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                                when (kind) {
                                    "options" -> OptionsScreen(links, language, links::dismissOptions)
                                    "sheet" -> PlaylistSheet(playlist, language, playlist::close)
                                    else -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        PlaylistBatchCard(playlistFixture(if (kind == "failures") BatchState.COMPLETED else BatchState.ACTIVE,
                                            failed = kind == "failures"), language, {})
                                    }
                                }
                            }
                        }
                    }
                }
                idle(); save(controller.get(), "playlist-$kind-$language-${if (dark) "dark" else "light"}")
            } finally { links.dismissOptions(); playlist.close(); scope.cancel(); controller.pause().stop().destroy() }
        }
    }
    @Test fun optionsRow() = shoot("options")
    @Test fun playlistSheet() = shoot("sheet")
    @Test fun runningBatch() = shoot("running")
    @Test fun doneWithFailures() = shoot("failures")
}
