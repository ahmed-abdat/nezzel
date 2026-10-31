package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.PlaylistBatch
import dev.ahmed.savlino.jobs.BatchItemRecord
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import dev.ahmed.savlino.windowViews
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

/** Arabic light review renders. Opt in with -PnezzelCapture=true and NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "ar-w384dp-h832dp-notnight-xxhdpi")
class HomeActionsCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val media = (1..10).map { id ->
        val title = if (id == 1) "رحلة إلى البحر" else "مقطع $id"
        SavedMedia(Uri.parse("content://media/external_primary/downloads/$id"), "$title.mp4", title,
            "video/mp4", 12_000_000, 1_790_000_000L - id * 3600, 720, null, 84_000, width = 1280)
    }
    private val pictures = MediaPictures { _, _, _ ->
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF5D9B93.toInt()) }
    }

    private fun idle() {
        repeat(6) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.mainClock.advanceTimeBy(250)
        }
        compose.waitForIdle()
    }

    private fun shoot(name: String, after: () -> Unit = {}, content: @Composable () -> Unit) {
        assumeTrue(dir != null)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalMediaPictures provides pictures) {
                    NezzelTheme(ThemePref.Light, false) { Surface(Modifier.fillMaxSize()) { content() } }
                }
            }
            idle(); after(); idle()
            windowViews(controller.get()).forEach { it.requestLayout(); it.invalidate() }
            idle()
            val root = controller.get().window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            windowViews(controller.get()).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view ->
                if (view !== root) canvas.drawColor(0x52000000)
                val x = if (view.width < root.width) (root.width - view.width) / 2f else 0f
                val y = if (view.height < root.height) (root.height - view.height).toFloat() else 0f
                canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
            }
            dir!!.mkdirs()
            File(dir, "$name-ar-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    @Composable private fun home() {
        Column(Modifier.padding(top = 24.dp)) { HomeRecents(media, "ar", {}) {} }
    }

    @Test fun earlierActions() = shoot("home-earlier-actions") { home() }

    @Test fun recentLongPressMenu() = shoot("home-recent-menu", after = {
        compose.onNodeWithText("رحلة إلى البحر", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.share_file)).assertIsDisplayed()
    }) { home() }

    @Test fun failedQualityAction() = shoot("failed-change-quality", after = {
        compose.onNodeWithTag("tab_running").performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.change_quality)).assertIsDisplayed()
    }) {
        CompositionLocalProvider(LocalFailedQuality provides FailedQualityLink(eligible = { true }, open = {})) {
            DownloadsScreen(listOf(DownloadProgress("quality", "رحلة إلى البحر.mp4", DownloadPhase.FAILED,
                error = DownloadError.Network, retryable = true)), "ar", {}, {}, {}, {}, {}, null, {}, {},
                oldDownloads = OldDownloadsOffer(false, false, {}, {}))
        }
    }

    @Composable private fun failedRow(error: DownloadError, retryable: Boolean) =
        DownloadsScreen(listOf(DownloadProgress("paused", "رحلة إلى البحر.mp4", DownloadPhase.FAILED,
            error = error, retryable = retryable)), "ar", {}, {}, {}, {}, {}, null, {}, {},
            oldDownloads = OldDownloadsOffer(false, false, {}, {}))

    @Test fun pausedExpiredRow() = shoot("paused-expired", after = {
        compose.onNodeWithTag("tab_running").performClick()
    }) { failedRow(DownloadError.PausedExpired, true) }

    @Test fun pausedExpiredAfterSevenDays() = shoot("paused-expired-7-days", after = {
        compose.onNodeWithTag("tab_running").performClick()
    }) { failedRow(DownloadError.FormatExpired, false) }

    @Test fun playlistFallbackLine() = shoot("playlist-fallback") {
        val base = playlistFixture()
        val batch = PlaylistBatch(base.record, base.items.map { item -> BatchItemRecord(item.batchId, item.position,
            item.itemId, item.title, item.state, item.jobId, item.error, item.retryable, item.position == 0, item.hasSource) })
        Column(Modifier.padding(top = 24.dp)) { PlaylistBatchRow(batch, "ar", {}, {}) }
    }
}
