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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.FixtureThumbnails
import dev.ahmed.savlino.HomeScreen
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.AudioConnection
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlaybackState
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.windowViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/** Home's bottom stack as one family (paste field, music bar, tabs), and music shrinking into it with no grey frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class HomeStackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val cover = MediaPictures { _, _, size ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFFE0A458.toInt(), 0xFF7A3E6B.toInt(), Shader.TileMode.CLAMP) })
        }
    }
    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 214_000, isAudio = true)
    private fun media(id: Int, name: String) = SavedMedia(Uri.parse("content://nezzel.fixture/thumb/$id"), name, name.substringBeforeLast('.'),
        "video/mp4", 81_700_000, 1_790_000_000L - id * 3600L, 1080, null, 654_000, 1920)
    private val library = listOf(media(0, "Big Buck Bunny.mp4"), media(1, "Sintel.mp4"), media(2, "Spring.mp4"), media(3, "Wing It.mp4"))

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

    /** Home with its library, the song opened over it and playing; [shots] poses it through the host. */
    private fun scene(lang: String, dark: Boolean, fontScale: Float = 1f, shots: (ComponentActivity, ViewerHostState) -> Unit) {
        RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
        Robolectric.setupContentProvider(DisplayNames::class.java, "media")
        Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
        DisplayNames.name = "Morning walk.m4a"
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
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        ViewerHost(host) {
                            val links = remember { LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply { automaticOptions = false } }
                            val state = remember { LibraryState(LibraryLoadState.Ready(library), {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
                            NavigationScaffold("Home", { emptyList() }, lang, {}) {
                                TabContent("Home") {
                                    HomeScreen(links, { emptyList() }, state, lang, {}, {}, {}, {})
                                    CompositionLocalProvider(LocalMediaPictures provides cover, LocalPlaybackEnvironment provides playback,
                                        LocalAudioConnection provides { audio }) {
                                        MediaViewer(listOf(song), 0, lang, {}, onShare = {}, onOpenWith = {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
    private fun bounds(activity: ComponentActivity): Triple<Rect, Rect, Rect> {
        val paste = compose.onNodeWithContentDescription(activity.getString(R.string.link_field_label)).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag(MINI_PLAYER_TAG).fetchSemanticsNode().boundsInRoot
        // The tabs' container: its cells plus its own 4dp padding.
        val cells = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes().map { it.boundsInRoot }
        val pad = 4 * activity.resources.displayMetrics.density
        val tabs = Rect(cells.minOf { it.left } - pad, cells.minOf { it.top } - pad, cells.maxOf { it.right } + pad, cells.maxOf { it.bottom } + pad)
        return Triple(paste, bar, tabs)
    }

    private fun oneFamily(lang: String) = scene(lang, false) { activity, host ->
        pose(host, 1f)
        assertTrue(host.collapsed)
        val (paste, bar, tabs) = bounds(activity)
        for (other in listOf(bar, tabs)) {
            assertEquals("Same start edge", paste.left, other.left, 1.5f)
            assertEquals("Same end edge", paste.right, other.right, 1.5f)
        }
        assertEquals("Paste, music and tabs are evenly apart", bar.top - paste.bottom, tabs.top - bar.bottom, 1.5f)
    }
    @Test fun pasteMusicAndTabsShareWidthAndGaps() = oneFamily("en")
    @Test fun pasteMusicAndTabsShareWidthAndGapsInArabic() = oneFamily("ar")

    /** Dark, and Arabic at 2x text: the stack keeps one width and its order, nothing leaves the screen, every target is at least 48dp
     * (the bar is one TalkBack stop; its own buttons are fixed 48dp). */
    private fun fits(lang: String, dark: Boolean, scale: Float) = scene(lang, dark, scale) { activity, host ->
        pose(host, 1f)
        assertTrue(host.collapsed)
        val (paste, bar, tabs) = bounds(activity)
        val dp = activity.resources.displayMetrics.density
        val root = activity.window.decorView
        for (r in listOf(paste, bar, tabs)) {
            assertTrue("$r inside the screen", r.left >= 0f && r.top >= 0f && r.right <= root.width && r.bottom <= root.height)
            assertEquals("Same start edge", paste.left, r.left, 1.5f); assertEquals("Same end edge", paste.right, r.right, 1.5f)
        }
        assertTrue("Paste, music, tabs in order, not overlapping", paste.bottom <= bar.top && bar.bottom <= tabs.top)
        assertEquals("Paste, music and tabs are evenly apart", bar.top - paste.bottom, tabs.top - bar.bottom, 1.5f)
        val targets = listOf(paste, bar) + compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .fetchSemanticsNodes().map { it.boundsInRoot }
        for (t in targets) assertTrue("$t is at least 48dp", t.width >= 48 * dp - 1 && t.height >= 48 * dp - 1)
    }
    @Test fun theStackFitsInDark() = fits("en", true, 1f)
    @Test fun theStackFitsInArabicAtTwiceTheText() = fits("ar", false, 2f)

    /**
     * Over the light bar every pixel of the bar's top band (past the cover) is either the black sheet or the bar itself, at every step
     * down and back up: the sheet draws back into the cover instead of fading through grey.
     */
    @Test fun aLightBarNeverShowsGrey() = scene("en", false) { activity, host ->
        pose(host, 1f)
        val (_, bar, _) = bounds(activity)
        val dp = activity.resources.displayMetrics.density
        val surface = android.graphics.Color.luminance(frame(activity).getPixel(bar.center.x.toInt(), (bar.top + 4 * dp).toInt()))
        assertTrue("The bar is light", surface > .5f)
        // From .8 the name and controls are gone: the sheet is plain black apart from the cover, which stays at the start.
        val steps = (40..50).map { it / 50f }
        for (p in steps + steps.reversed()) {
            pose(host, p)
            val f = frame(activity)
            val y = (bar.top + 4 * dp).toInt()
            val mixed = ((bar.left + 120 * dp).toInt() until (bar.right - 40 * dp).toInt()).count { x ->
                val l = android.graphics.Color.luminance(f.getPixel(x, y))
                l > .06f && kotlin.math.abs(l - surface) > .06f
            }
            assertTrue("p=$p: $mixed grey pixels on the bar", mixed <= 2)
        }
    }

    /** Owner review: Home with music in the bar, and the collapse/expand strips over the light bar. */
    @Test fun captures() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) scene(lang, dark) { activity, host ->
            pose(host, 1f); save(frame(activity), "home-$lang-${if (dark) "dark" else "light"}")
        }
        for (scale in listOf(1.5f, 2f)) scene("ar", false, scale) { activity, host ->
            pose(host, 1f); save(frame(activity), "home-ar-light-text-${scale}x")
        }
        val points = listOf(0f, .25f, .5f, .75f, .85f, .95f, 1f)
        for (lang in listOf("en", "ar")) scene(lang, false) { activity, host ->
            for ((name, path) in listOf("collapse" to points, "expand" to points.reversed())) {
                val frames = path.map { pose(host, it); frame(activity) }
                val gap = 24
                val strip = Bitmap.createBitmap(frames.sumOf { it.width } + gap * (frames.size - 1), frames[0].height, Bitmap.Config.ARGB_8888)
                Canvas(strip).apply {
                    drawColor(0xFF808080.toInt())
                    frames.forEachIndexed { i, f -> drawBitmap(f, (i * (f.width + gap)).toFloat(), 0f, null) }
                }
                save(strip, "$name-strip-$lang-light")
            }
        }
    }
}
