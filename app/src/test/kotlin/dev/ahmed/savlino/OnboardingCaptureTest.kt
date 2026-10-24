package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.OnboardingScreen
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
import java.io.File
import java.time.Duration

/** Design review captures of the first and last onboarding pages. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class OnboardingCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun next(a: ComponentActivity) {
        val label = a.getString(R.string.onboarding_next)
        semanticsNodes(a, idleMillis = 250).last {
            it.config.getOrNull(SemanticsActions.OnClick) != null && texts(descendants(it)).contains(label)
        }.config[SemanticsActions.OnClick].action?.invoke()
        idle()
    }

    @Test fun pages() {
        assumeTrue(dir != null)
        // Animations off: pages change instantly and the logo wall holds still for a stable picture
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                val a = controller.get()
                a.setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { OnboardingScreen {} } }
                idle()
                for (page in 0..4) {
                    if (page == 0 || page == 4) {
                        val view = a.window.decorView
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        dir!!.mkdirs()
                        File(dir, "onboarding-${page + 1}-$lang-${if (dark) "dark" else "light"}.png").outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    if (page < 4) next(a)
                }
            } finally { controller.pause().stop().destroy() }
        }
    }
}
