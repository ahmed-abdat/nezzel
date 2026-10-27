package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.VideoSize
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlayerFactory
import android.media.AudioManager
import dev.ahmed.savlino.ui.theme.NezzelTheme
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

/** Owner review captures of the player controls, synthetic pictures only. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class ViewerControlsCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val pictures = MediaPictures { _, _, _ ->
        Bitmap.createBitmap(270, 270, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 270f, 270f, Paint().apply {
                shader = LinearGradient(0f, 0f, 270f, 270f, 0xFF2E7D7A.toInt(), 0xFFB2DFDB.toInt(), Shader.TileMode.CLAMP) })
        }
    }
    private val video = ViewerItem(Uri.parse("content://media/external/video/2"), true, "video/mp4", 100_000)

    private fun idle(millis: Long = 250) = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 4))
        compose.mainClock.advanceTimeBy(millis / 4)
    }

    private fun save(a: ComponentActivity, name: String) {
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View -> view.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, item: ViewerItem = video, qualifiers: String = "", act: ComposeTestRule.(ComponentActivity) -> Unit = {}) {
        assumeTrue(dir != null)
        val app = RuntimeEnvironment.getApplication()
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang$qualifiers-${if (dark) "night" else "notnight"}")
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ ->
                ViewerTestPlayer().also { it.size = VideoSize(1920, 1080) } }, sizeOf = { 10L })
            try {
                activity.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback) {
                        MediaViewer(listOf(item), 0, lang, {}, onShare = {}, onOpenWith = {})
                    }
                } }
                idle(); compose.waitForIdle()
                compose.act(activity)
                windowViews(activity).forEach { it.requestLayout(); it.invalidate() }
                idle(100)
                save(activity, "$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun ComposeTestRule.node(a: ComponentActivity, id: Int) = onNodeWithContentDescription(a.getString(id))
    private fun ComposeTestRule.page(a: ComponentActivity) = onNodeWithContentDescription(a.getString(R.string.viewer_video_n, "1", "1"))
    /** Play from 0:32 of 1:40 and pause the auto-hide by touching, so the frame shows real progress. */
    private fun ComposeTestRule.at32(a: ComponentActivity) {
        node(a, R.string.viewer_seek_forward_10).performClick(); node(a, R.string.viewer_seek_forward_10).performClick()
        node(a, R.string.viewer_seek_forward_10).performClick(); idle(); waitForIdle()
    }

    @Test fun videoControls() = shoot("1-video-controls") { at32(it) }

    @Test fun videoHidden() = shoot("2-video-hidden") { a ->
        at32(a); page(a).performTouchInput { click(Offset(centerX, height * .3f)) }; idle(400); waitForIdle()
    }

    @Test fun skipFeedback() = shoot("3-double-tap-forward") { a ->
        at32(a); page(a).performTouchInput { click(Offset(centerX, height * .3f)) }; idle(400)
        page(a).performTouchInput { doubleClick(Offset(width * .8f, height * .3f)) }; idle(150)
    }

    @Test fun speedMenu() = shoot("4-speed-menu") { a ->
        at32(a); node(a, R.string.viewer_speed).performClick(); idle(); waitForIdle()
        onNodeWithText(a.getString(R.string.viewer_speed_value, ViewerSpeed.number(1.5f, a.resources.configuration.locales[0].language)), substring = true).performClick()
        idle(); node(a, R.string.viewer_speed).performClick(); idle(); waitForIdle()
    }

    @Test fun volumePanel() = shoot("5-volume") { a -> at32(a); node(a, R.string.viewer_volume).performClick(); idle(); waitForIdle() }

    @Test fun fullscreen() = shoot("6-fullscreen-landscape", qualifiers = "-w832dp-h384dp-land") { a ->
        at32(a); node(a, R.string.viewer_fullscreen).performClick(); idle(); waitForIdle()
    }
}
