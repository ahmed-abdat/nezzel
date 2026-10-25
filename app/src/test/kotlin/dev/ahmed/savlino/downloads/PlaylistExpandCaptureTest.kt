package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.jobs.BatchState
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.windowViews
import dev.ahmed.savlino.roots
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.compositionContext
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

/**
 * Playlist row in Downloading and its videos sheet (half and full), en/ar × light/dark. Set NEZZEL_CAPTURE_DIR to write PNGs.
 * SDK29 renders the sheet's own window; it is composited over the activity with the scrim.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class PlaylistExpandCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    /** Every window at its own place: the sheet's window is full size and draws its own scrim. */
    private fun save(activity: ComponentActivity, name: String) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(activity).filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }.forEach { it.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val members = listOf(DownloadProgress("job-c", "Walking the old harbour wall.mp4", DownloadPhase.DOWNLOADING,
        bytes = 18_000_000, total = dev.ahmed.savlino.domain.Size.Exact(43_000_000)))

    @OptIn(ExperimentalMaterial3Api::class)
    private fun shoot(name: String, language: String, dark: Boolean, kind: String) {
        RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
        AppLocale.choose(RuntimeEnvironment.getApplication(), language)
        // A frame clock of our own, advanced 16 ms per frame, so the sheet's slide-in (and expand) animation completes.
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        var frameNanos = 0L
        fun settle(rounds: Int = 90) = repeat(rounds) {
            Snapshot.sendApplyNotifications(); frameNanos += 16_000_000; clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            windowViews(controller.get()).flatMap(::roots).forEach { it.measureAndLayoutForTest() }
        }
        try {
            controller.get().window.decorView.compositionContext = recomposer
            controller.visible()
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            DownloadsScreen(members, language, {}, {}, {}, {}, {}, null, {}, {},
                                playlists = listOf(playlistMixedFixture()), libraryLoad = LibraryLoadState.Ready(emptyList()))
                            if (kind != "row") {
                                val batch = if (kind == "long") longPlaylist(240, title = "Recorded lessons · دروس مسجلة، الفصل الأول كاملًا") { it % 37 == 5 }
                                    else playlistMixedFixture()
                                val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                                if (kind == "full") LaunchedEffect(Unit) {
                                    snapshotFlow { state.currentValue }.first { it == SheetValue.PartiallyExpanded }
                                    state.expand()
                                }
                                PlaylistVideosSheet(batch, if (kind == "long") emptyList() else members + playlistMixedJobs.drop(1), language, {}, {}, state = state)
                            }
                        }
                    }
                }
            }
            settle(); save(controller.get(), name)
        } finally { controller.pause().stop().destroy(); recomposer.cancel(); scope.cancel() }
    }

    @Test fun rowInTheList() {
        assumeTrue(dir != null)
        shoot("playlist-row-ar-light", "ar", false, "row")
        shoot("playlist-row-ar-dark", "ar", true, "row")
        shoot("playlist-row-en-light", "en", false, "row")
    }
    @Test fun sheetHalfAndFull() {
        assumeTrue(dir != null)
        for ((language, dark) in listOf("ar" to false, "ar" to true, "en" to false)) for (kind in listOf("half", "full"))
            shoot("playlist-sheet-$kind-$language-${if (dark) "dark" else "light"}", language, dark, kind)
    }
    @Test fun longPlaylistSheet() {
        assumeTrue(dir != null)
        shoot("playlist-sheet-long-ar-light", "ar", false, "long")
        shoot("playlist-sheet-long-en-light", "en", false, "long")
    }
}
