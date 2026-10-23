package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.time.Duration

/** Design review capture of the mobile-data note on the options sheet. Runs only with NEZZEL_CAPTURE_DIR; synthetic rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class DataNoteCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    @Test fun optionsOnMobileData() {
        assumeTrue(dir != null)
        val shots = listOf(1f, 1.3f).flatMap { scale -> listOf("en", "ar").flatMap { lang -> listOf(false, true).map { Triple(scale, lang, it) } } } +
            Triple(1f, "fr", false)
        for ((scale, lang, dark) in shots) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                val cm = controller.get().getSystemService(ConnectivityManager::class.java)
                val metered = ShadowNetworkCapabilities.newInstance().also {
                    shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, metered)
                controller.get().setContent {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        Surface { OptionsSheetContent(remember { readyLinks() }, lang) {} }
                    }
                }
                repeat(4) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                val view = controller.get().window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                dir!!.mkdirs()
                val name = "data-note-$lang-${if (dark) "dark" else "light"}${if (scale > 1f) "-130" else ""}.png"
                File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
        }
    }

    private fun readyLinks(): LinkViewModel {
        val url = "https://example.org/watch?v=one"
        fun v(id: String, height: Int, mb: Long) = Format(id, "mp4", true, false, videoCodec = "avc1", width = height * 16 / 9,
            height = height, size = Size.Estimated(mb * 1_000_000), audioFormatId = "a", requiresMerge = true)
        val rows = listOf(v("1080", 1080, 412), v("720", 720, 236), v("360", 360, 78),
            Format("a", "m4a", false, true, audioCodec = "mp4a", bitrateKbps = 128, size = Size.Exact(31_000_000)))
        val presets = listOf(Preset.BEST to "1080", Preset.BALANCED to "720", Preset.SMALL to "360", Preset.AUDIO_M4A to "a")
            .map { PresetChoice(it.first, it.second) }
        val inspection = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "v1", rows, presets,
            title = "Full match highlights, second half", uploader = "Sports club", durationSeconds = 1830)
        return LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)), currentEngineVersion = { "v1" }).apply {
            edit(url); checkLink(); shadowOf(Looper.getMainLooper()).idle(); selectPreset(Preset.BEST)
        }
    }
}
