package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.downloads.LocalJobArtwork
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
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

/** Owner review: Home idle / one download / a batch / just finished (saved moment, then the file), ar + en light. Set NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class HomeNoShiftCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun item(id: Int, name: String) = SavedMedia(Uri.parse("content://nezzel.fixture/thumb/$id"), name, name.substringBeforeLast('.'),
        "video/mp4", 40_000_000L + id * 9_000_000L, 1_790_000_000L - id * 3600L, 1080, null, 120_000L + id * 41_000L, width = 1920)
    private val library = listOf("Big Buck Bunny", "Sintel", "Spring", "Tears of Steel", "Cosmos Laundromat", "Charge", "Wing It",
        "Coffee Run", "Agent 327", "Elephants Dream").mapIndexed { i, name -> item(i, "$name.mp4") }
    private val landed = item(11, "Caminandes Llamigos.mp4").copy(addedSeconds = 1_800_000_000L, size = 100_000_000)
    private val running = DownloadProgress("r1", "Caminandes Llamigos.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000,
        total = Size.Exact(100_000_000), bytesPerSecond = 3_100_000.0, remainingTransferSeconds = 12, stalled = false, qualityHeight = 1080)
    private val finished = running.copy(phase = DownloadPhase.COMPLETED, publishedUri = landed.uri)
    private val batch = listOf(running, DownloadProgress("w1", "Glass Half.mp4", DownloadPhase.PREPARING, waiting = true),
        DownloadProgress("w2", "Hero.mp4", DownloadPhase.PREPARING, waiting = true))
    private val artwork: suspend (String) -> Bitmap? = { id ->
        val (from, to) = when (id) { "r1" -> 0xFF00897B to 0xFFFFD54F; "w1" -> 0xFF5E35B1 to 0xFF80DEEA; else -> 0xFFD84315 to 0xFFFFF59D }
        Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 320f, 200f, Paint().apply { shader = LinearGradient(0f, 0f, 320f, 200f, from.toInt(), to.toInt(), Shader.TileMode.CLAMP) })
        }
    }

    private fun idle() = repeat(8) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun ComponentActivity.capture(file: File) {
        val root = window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun captureStates() {
        assumeTrue(dir != null)
        for (lang in listOf("ar", "en")) {
            RuntimeEnvironment.setQualifiers("+$lang-notnight")
            Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
            var jobs by mutableStateOf(emptyList<DownloadProgress>())
            var media by mutableStateOf(library)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            try {
                activity.setContent {
                    val vm = remember { LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply { automaticOptions = false } }
                    val state = remember(media) { LibraryState(LibraryLoadState.Ready(media), {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
                    CompositionLocalProvider(LocalLayoutDirection provides if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalJobArtwork provides artwork) {
                        NezzelTheme(ThemePref.Light, false) {
                            NavigationScaffold("Home", jobs, lang, {}) { TabContent("Home") { HomeScreen(vm, jobs, state, lang, {}, {}, {}, {}) } }
                        }
                    }
                }
                fun shot(name: String) { idle(); activity.capture(File(dir, "$name-$lang-light.png")) }
                shot("1-idle")
                jobs = listOf(running); shot("2-one-download")
                jobs = batch; shot("3-batch")
                jobs = listOf(running); idle()
                jobs = listOf(finished); shot("4a-just-finished")
                media = listOf(landed) + library; shot("4b-file-landed")
            } finally { controller.pause().stop().destroy() }
        }
    }
}
