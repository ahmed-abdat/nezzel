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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.VideoSize
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlayerFactory
import android.media.AudioManager
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.hideTextureViews
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

/** Owner review captures of the pull down and the 2× hold, synthetic pictures only. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class ViewerPullCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val pictures = MediaPictures { _, _, _ ->
        Bitmap.createBitmap(270, 270, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 270f, 270f, Paint().apply {
                shader = LinearGradient(0f, 0f, 270f, 270f, 0xFF2E7D7A.toInt(), 0xFFB2DFDB.toInt(), Shader.TileMode.CLAMP) })
        }
    }
    private val video = ViewerItem(Uri.parse("content://media/external/video/2"), true, "video/mp4", 100_000)
    private val photo = ViewerItem(Uri.parse("content://media/external/images/media/3"), false, "image/jpeg")

    private fun idle(millis: Long = 250) = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 4))
        compose.mainClock.advanceTimeBy(millis / 4)
    }

    private fun save(a: ComponentActivity, name: String) {
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View -> hideTextureViews(view); view.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The viewer over a plain list, so a pull shows what is behind it. */
    private fun shoot(name: String, item: ViewerItem, act: ComposeTestRule.(ComponentActivity) -> Unit) {
        assumeTrue(dir != null)
        val app = RuntimeEnvironment.getApplication()
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ ->
                ViewerTestPlayer().also { it.size = VideoSize(1920, 1080) } }, sizeOf = { 10L })
            try {
                activity.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    ViewerHost {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(24.dp)) {
                                repeat(8) { Text("clip_${it + 1}.mp4", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                        CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback) {
                            MediaViewer(listOf(item), 0, lang, {}, onShare = {}, onOpenWith = {})
                        }
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

    private fun ComposeTestRule.viewer() = onNodeWithTag(VIEWER_TAG)
    /** A finger a quarter of the way down the screen, still held. */
    private fun ComposeTestRule.midPull() {
        viewer().performTouchInput { down(Offset(centerX, height * .3f)); repeat(12) { moveBy(Offset(0f, height * .25f / 12)) } }
        idle(50)
    }

    @Test fun photoMidPull() = shoot("1-photo-mid-pull", photo) { midPull() }

    @Test fun videoMidPull() = shoot("2-video-mid-pull", video) { midPull() }

    @Test fun videoHeldAtDoubleSpeed() = shoot("3-video-hold-2x", video) {
        viewer().performTouchInput { down(Offset(centerX, height * .3f)); advanceEventTime(700); moveBy(Offset(0f, .01f)) }
        idle(400); waitForIdle()
    }

    @Test fun fullscreenMidPull() = shoot("4-fullscreen-mid-pull", video) { a ->
        onNodeWithContentDescription(a.getString(R.string.viewer_fullscreen)).performClick(); idle(); waitForIdle()
        midPull()
    }
}
