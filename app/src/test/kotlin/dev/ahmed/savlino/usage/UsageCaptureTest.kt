package dev.ahmed.savlino.usage

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.AdvancedSettings
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design review captures of Settings → Advanced with "Help improve Nezzel" on and off. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w384dp-h832dp-xxhdpi")
class UsageCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun shoot(on: Boolean) {
        assumeTrue(dir != null)
        val variants = listOf("en", "ar", "fr").flatMap { lang -> listOf(Triple(lang, false, 1f), Triple(lang, true, 1f)) } +
            Triple("ar", false, 1.3f)
        for ((lang, dark, font) in variants) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(font)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        AdvancedSettings(null, {}, null, automaticOptions = true, onClearLinks = {}, parallel = 2,
                            language = lang, helpImprove = on, back = {})
                    }
                }
                idle()
                val root = controller.get().window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                dir!!.mkdirs()
                val name = "advanced-${if (on) "on" else "off"}-$lang-${if (dark) "dark" else "light"}${if (font > 1f) "-font130" else ""}"
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
        }
    }

    @Test fun on() = shoot(true)
    @Test fun off() = shoot(false)
}
