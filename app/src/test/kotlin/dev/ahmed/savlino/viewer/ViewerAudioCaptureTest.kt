package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlaybackState
import dev.ahmed.savlino.playback.PlayerFactory
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

/** Owner review captures of the music screen and the failure card, synthetic covers only. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class ViewerAudioCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val cover = MediaPictures { _, _, _ ->
        Bitmap.createBitmap(540, 540, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 540f, 540f, Paint().apply {
                shader = LinearGradient(0f, 0f, 540f, 540f, 0xFFE0A458.toInt(), 0xFF7A3E6B.toInt(), Shader.TileMode.CLAMP) })
        }
    }
    private val noCover = MediaPictures { _, _, _ -> null }
    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 214_000, isAudio = true)
    private val video = ViewerItem(Uri.parse("content://media/external/video/media/2"), true, "video/mp4", 100_000)

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

    private fun shoot(name: String, file: String?, item: ViewerItem = song, pictures: MediaPictures = noCover, qualifiers: String = "",
                      fail: Boolean = false) {
        assumeTrue(dir != null)
        RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang$qualifiers-${if (dark) "night" else "notnight"}")
            Robolectric.setupContentProvider(DisplayNames::class.java, "media")
            DisplayNames.name = file
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            val players = mutableListOf<ViewerTestPlayer>()
            val audio = TestAudio().also { it.fail = fail }
            val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ ->
                ViewerTestPlayer().also { it.size = VideoSize(1920, 1080); players += it } },
                sizeOf = { 10L })
            try {
                activity.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback,
                        LocalAudioConnection provides { audio }) {
                        MediaViewer(listOf(item), 0, lang, {}, onShare = {}, onOpenWith = {})
                    }
                } }
                idle(); compose.waitForIdle()
                val play = activity.getString(R.string.viewer_play)
                if (compose.onAllNodesWithContentDescription(play).fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithContentDescription(play).performClick()
                playback.audioState.value = if (fail) PlaybackState.Idle else PlaybackState.Ready
                // A video that stops on a decoding error once it has started.
                if (fail) players.forEach { it.error = PlaybackException("", null, PlaybackException.ERROR_CODE_DECODING_FAILED) }
                idle()
                // 1:12 into the song, so the seek bar shows real progress.
                if (!fail) repeat(7) { compose.onNodeWithContentDescription(activity.getString(R.string.viewer_seek_forward_10)).performClick() }
                // Let the title query land: it runs off the main thread.
                Thread.sleep(200); idle(500); compose.waitForIdle()
                windowViews(activity).forEach { it.requestLayout(); it.invalidate() }
                idle(100)
                save(activity, "$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun playing() = shoot("1-audio-playing", "Morning walk.m4a")
    @Test fun withCover() = shoot("2-audio-cover", "Summer mix 2024.mp3", pictures = cover)
    @Test fun longArabicName() = shoot("3-audio-long-arabic-name", "تسجيل محاضرة الأستاذ في مادة الرياضيات للفصل الثاني - الجزء الأول.m4a")
    @Test fun landscape() = shoot("4-audio-landscape", "Morning walk.m4a", pictures = cover, qualifiers = "-w832dp-h384dp-land")
    @Test fun audioFailure() = shoot("5-failure-audio", "Morning walk.m4a", fail = true)
    @Test fun videoFailure() = shoot("6-failure-video", null, item = video, fail = true)
}
