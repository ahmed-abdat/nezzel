package dev.ahmed.savlino.viewer

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.playback.PlaybackEnvironment
import dev.ahmed.savlino.playback.PlayerFactory
import dev.ahmed.savlino.sharing.FileShare
import dev.ahmed.savlino.sharing.FileSharer
import dev.ahmed.savlino.sharing.LocalFileSharer
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.windowViews
import org.junit.Assert.*
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

/** U1 owner review: one Arabic/light frame per change, written only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "ar-w384dp-h832dp-notnight-xxhdpi")
class ViewerUxCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val song = ViewerItem(Uri.parse("content://media/external/audio/media/5"), false, "audio/mp4", 100_000, isAudio = true)
    private val photo = ViewerItem(Uri.parse("content://media/external/images/media/7"), false, "image/jpeg")
    private val pictures = MediaPictures { _, _, _ ->
        Bitmap.createBitmap(270, 270, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFB2DFDB.toInt()) }
    }

    private fun idle(millis: Long = 400) = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis / 4))
        compose.mainClock.advanceTimeBy(millis / 4)
    }

    private fun scene(items: List<ViewerItem>, shot: (ComponentActivity, ViewerHostState, List<ViewerTestPlayer>) -> Unit) {
        assumeTrue(dir != null)
        RuntimeEnvironment.setQualifiers("+ar-notnight")
        Robolectric.setupContentProvider(DisplayNames::class.java, "media")
        DisplayNames.name = "المقطع الأول.m4a"
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val host = ViewerHostState()
        val players = mutableListOf<ViewerTestPlayer>()
        val audio = TestAudio(players)
        val playback = PlaybackEnvironment(activity, PlayerFactory { _, _ -> ViewerTestPlayer() }, sizeOf = { 10L })
        try {
            activity.setContent { NezzelTheme(ThemePref.Light, false) {
                ViewerHost(host) {
                    NavigationScaffold("Downloads", { emptyList() }, "ar", {}) {
                        TabContent("Downloads") {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                            val scope = rememberCoroutineScope()
                            val sharer = remember { FileSharer(activity, UsageEvents.None, scope, {}, kotlinx.coroutines.Dispatchers.Unconfined) }
                            CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback,
                                LocalAudioConnection provides { audio }, LocalFileSharer provides sharer) {
                                MediaViewer(items, 0, "ar", {}, onShare = {}, onOpenWith = {})
                            }
                        }
                    }
                }
            } }
            idle(); compose.waitForIdle()
            shot(activity, host, players)
        } finally { controller.pause().stop().destroy() }
    }

    private fun save(activity: ComponentActivity, name: String) {
        windowViews(activity).forEach { it.requestLayout(); it.invalidate() }
        idle(100); compose.waitForIdle()
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windowViews(activity).filter { it.isAttachedToWindow && it.width > 0 }.forEach { it.draw(canvas) }
        File(dir!!, "$name-ar-light.png").also { it.parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun viewerWhatsAppChoices() {
        assumeTrue(dir != null)
        val pm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        for ((pkg, label) in listOf(FileShare.WHATSAPP to "واتساب", FileShare.WHATSAPP_BUSINESS to "واتساب للأعمال")) {
            pm.installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true; nonLocalizedLabel = label }
            })
            val send = ComponentName(pkg, "$pkg.Send")
            pm.addActivityIfNotPresent(send)
            pm.addIntentFilterForActivity(send, IntentFilter(Intent.ACTION_SEND, "image/jpeg").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
        scene(listOf(photo)) { activity, _, _ ->
            compose.onNodeWithContentDescription(activity.getString(R.string.share_file)).performClick()
            idle(); compose.waitForIdle()
            compose.onNodeWithText("واتساب").assertIsDisplayed()
            compose.onNodeWithText("واتساب للأعمال").assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.share_other_apps)).assertIsDisplayed()
            save(activity, "u1-viewer-share")
        }
    }

    @Test fun nextAudioKeepsPlayingInTheMiniPlayer() {
        val next = song.copy(uri = Uri.parse("content://media/external/audio/media/6"))
        scene(listOf(song, next)) { activity, host, players ->
            compose.onNodeWithContentDescription(activity.getString(R.string.viewer_minimize)).performClick()
            idle(600); compose.waitForIdle()
            assertTrue(host.collapsed)
            DisplayNames.name = "المقطع الثاني.m4a"
            compose.runOnIdle { players.single().inner.end() }
            idle(); compose.waitForIdle()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(activity.getString(R.string.mini_playing, "المقطع الثاني"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(next.uri, host.mini!!.item.uri)
            assertEquals(2, players.size)
            assertTrue(players.last().isPlaying)
            assertTrue(host.collapsed)
            save(activity, "u1-audio-advanced-mini")
        }
    }
}
