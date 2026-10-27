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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.AudioConnection
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlaybackState
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.nav.TabContent
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

/** Owner review captures of music shrinking into the bar above the tabs. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class MiniPlayerCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val cover = MediaPictures { _, _, size ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFFE0A458.toInt(), 0xFF7A3E6B.toInt(), Shader.TileMode.CLAMP) })
        }
    }
    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 214_000, isAudio = true)

    private fun idle(millis: Long = 250) = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 4))
        compose.mainClock.advanceTimeBy(millis / 4)
    }
    private fun frame(a: ComponentActivity): Bitmap {
        windowViews(a).forEach { it.requestLayout(); it.invalidate() }
        idle(100)
        val root = a.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(a).filter { it.isAttachedToWindow && it.width > 0 }.forEach { view: View -> view.draw(canvas) }
        return bitmap
    }
    private fun save(bitmap: Bitmap, name: String) {
        val file = File(dir!!, "$name.png").also { it.parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Opens the song over the Downloads tab, starts it, then runs [shots] with the host to pose it. */
    private fun scene(lang: String, dark: Boolean, file: String, shots: (ComponentActivity, ViewerHostState) -> Unit) {
        RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
        Robolectric.setupContentProvider(DisplayNames::class.java, "media")
        DisplayNames.name = file
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val audio = object : AudioConnection {
            override fun open(item: ViewerItem, onPlayer: (Player) -> Unit, onFailure: () -> Unit) =
                onPlayer(ViewerTestPlayer().also { it.setMediaItem(MediaItem.fromUri(item.uri)); it.prepare() })
            override fun close() {}
        }
        val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
        val host = ViewerHostState()
        try {
            activity.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                ViewerHost(host) {
                    NavigationScaffold("Downloads", { emptyList() }, lang, {}) {
                        TabContent("Downloads") {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                            CompositionLocalProvider(LocalMediaPictures provides cover, LocalPlaybackEnvironment provides playback,
                                LocalAudioConnection provides { audio }) {
                                MediaViewer(listOf(song), 0, lang, {}, onShare = {}, onOpenWith = {})
                            }
                        }
                    }
                }
            } }
            idle(); compose.waitForIdle()
            val play = activity.getString(R.string.viewer_play)
            if (compose.onAllNodesWithContentDescription(play).fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription(play).performClick()
            playback.audioState.value = PlaybackState.Ready
            Thread.sleep(200); idle(500); compose.waitForIdle()
            shots(activity, host)
        } finally { controller.pause().stop().destroy() }
    }
    private fun pose(host: ViewerHostState, p: Float) {
        compose.runOnIdle { host.size.dispatchRawDelta(p * host.travel - host.size.offset) }
        idle(50); compose.waitForIdle()
    }

    @Test fun stages() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) scene(lang, dark, "Summer mix 2024.mp3") { activity, host ->
            val tone = if (dark) "dark" else "light"
            for (p in listOf(0f, .5f, 1f)) { pose(host, p); save(frame(activity), "${(p * 100).toInt()}-$lang-$tone") }
        }
    }

    /** The bar's row riding the sheet: each step of the drag, in both languages. */
    @Test fun barRidesTheSheet() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) scene(lang, false, "Summer mix 2024.mp3") { activity, host ->
            for (p in listOf(0f, .15f, .3f, .5f, .7f, .8f, .9f, 1f)) { pose(host, p); save(frame(activity), "ride-$lang-${(p * 100).toInt()}") }
        }
    }

    /** A long name in the settled bar: before it scrolls, then twice while it scrolls. */
    @Test fun marqueeStrip() {
        assumeTrue(dir != null)
        for ((lang, name) in listOf("en" to "Summer evening mix live from the rooftop 2024.mp3", "ar" to "أغنية الصيف الطويلة جدا من الحفلة المباشرة.mp3"))
            scene(lang, false, name) { activity, host ->
                pose(host, 1f)
                val frames = listOf(500L, 4500L, 3000L).map { idle(it); frame(activity) }
                val crop = frames.map { Bitmap.createBitmap(it, 0, it.height * 5 / 8, it.width, it.height * 3 / 8) }
                val strip = Bitmap.createBitmap(crop[0].width, crop.sumOf { it.height } + 24 * 2, Bitmap.Config.ARGB_8888)
                Canvas(strip).apply { drawColor(0xFF808080.toInt()); crop.forEachIndexed { i, f -> drawBitmap(f, 0f, (i * (f.height + 24)).toFloat(), null) } }
                save(strip, "marquee-$lang")
            }
    }

    /** Six steps of the drag side by side, for reading the hand-off at a glance. */
    @Test fun motionStrip() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) scene(lang, dark, "Morning walk.m4a") { activity, host ->
            val frames = listOf(0f, .25f, .5f, .75f, .9f, 1f).map { pose(host, it); frame(activity) }
            val gap = 24
            val strip = Bitmap.createBitmap(frames.sumOf { it.width } + gap * (frames.size - 1), frames[0].height, Bitmap.Config.ARGB_8888)
            Canvas(strip).apply {
                drawColor(0xFF808080.toInt())
                frames.forEachIndexed { i, f -> drawBitmap(f, (i * (f.width + gap)).toFloat(), 0f, null) }
            }
            save(strip, "motion-strip-$lang-${if (dark) "dark" else "light"}")
        }
    }

    /** A slow drag down and back up, one frame per step, for a clip. */
    @Test fun slowDrag() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) scene(lang, false, "Morning walk.m4a") { activity, host ->
            val steps = 48
            val path = (0..steps).map { it / steps.toFloat() } + List(8) { 1f } + (steps downTo 0).map { it / steps.toFloat() } + List(8) { 0f }
            path.forEachIndexed { i, p -> pose(host, p); save(frame(activity), "clip-$lang/%03d".format(i)) }
        }
    }
}
