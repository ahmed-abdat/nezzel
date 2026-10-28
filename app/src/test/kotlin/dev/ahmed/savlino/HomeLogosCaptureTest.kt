package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OnboardingScreen
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
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

/** Design review of the site-logo collage: Home first run and onboarding page 1, en + ar, light + dark, small phone. Set NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class HomeLogosCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun idle() = repeat(8) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun ComponentActivity.capture(name: String) {
        val root = window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun collage() {
        assumeTrue(dir != null)
        // Animations off: the drift holds still for a stable picture (the reduced-motion path)
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val shots = listOf("en", "ar").flatMap { lang -> listOf(false, true).map { Triple(lang, it, 1f) } } +
            Triple("ar", false, 1.3f) + Triple("en", false, 1.3f)
        for ((lang, dark, scale) in shots) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(scale)
            val suffix = "$lang-${if (dark) "dark" else "light"}${if (scale > 1f) "-130" else ""}"
            for (screen in listOf("home", "onboarding")) {
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
                val activity = controller.get()
                try {
                    activity.setContent {
                        CompositionLocalProvider(LocalLayoutDirection provides if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                                if (screen == "onboarding") OnboardingScreen {}
                                else {
                                    val vm = remember { LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply { automaticOptions = false } }
                                    val state = remember { LibraryState(LibraryLoadState.Ready(emptyList()), {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
                                    NavigationScaffold("Home", { emptyList() }, lang, {}) {
                                        TabContent("Home") { HomeScreen(vm, { emptyList() }, state, lang, {}, {}, {}, {}) }
                                    }
                                }
                            }
                        }
                    }
                    idle()
                    activity.capture("$screen-$suffix")
                } finally { controller.pause().stop().destroy() }
            }
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
