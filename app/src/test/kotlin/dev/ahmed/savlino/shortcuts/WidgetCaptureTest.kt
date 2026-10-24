package dev.ahmed.savlino.shortcuts

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Design review captures of the progress widget. Runs only with NEZZEL_CAPTURE_DIR; synthetic jobs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class WidgetCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private fun job(id: String, name: String, bytes: Long, phase: DownloadPhase = DownloadPhase.DOWNLOADING) =
        DownloadProgress(id, name, phase, bytes, Size.Exact(100))
    private val arabic = job("a", "رحلة إلى جبال الأطلس في الشتاء مع العائلة.mp4", 45)
    private val english = job("e", "Sunset timelapse over the old harbour.mp4", 45)

    @Test fun widget() {
        assumeTrue(dir != null)
        val shots = listOf(
            Shot("active-ar", "ar", WidgetUiState.from(listOf(arabic))),
            Shot("active-en", "en", WidgetUiState.from(listOf(english))),
            Shot("paused-ar", "ar", WidgetUiState.from(listOf(arabic.copy(paused = true)))),
            Shot("three-running-ar", "ar", WidgetUiState.from(listOf(job("1", "مقطع.mp4", 20), job("2", "حلقة.mp4", 70), arabic))),
            Shot("preparing-ar", "ar", WidgetUiState.from(listOf(arabic.copy(phase = DownloadPhase.PREPARING)))),
            Shot("idle-ar", "ar", WidgetUiState()),
            Shot("idle-en", "en", WidgetUiState()),
            Shot("idle-fr", "fr", WidgetUiState()),
            Shot("narrow-ar", "ar", WidgetUiState.from(listOf(arabic)), width = 170),
            Shot("narrow-idle-ar", "ar", WidgetUiState(), width = 170),
            Shot("font130-ar", "ar", WidgetUiState.from(listOf(job("2", "حلقة.mp4", 70), arabic)), scale = 1.3f),
            Shot("font130-idle-fr", "fr", WidgetUiState(), scale = 1.3f),
        )
        for (shot in shots) for (dark in listOf(false, true)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(shot.lang)); setLayoutDirection(java.util.Locale.forLanguageTag(shot.lang))
                fontScale = shot.scale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val context = app.createConfigurationContext(config)
            val density = context.resources.displayMetrics.density
            // One home-screen cell row (72dp tall) on a plain wallpaper tone.
            val host = FrameLayout(context).apply { setBackgroundColor(if (dark) 0xFF3B4442.toInt() else 0xFFC9D3CF.toInt()) }
            val pad = (16 * density).toInt()
            host.setPadding(pad, pad, pad, pad)
            host.addView(NezzelWidgetProvider.views(context, shot.state, shot.width < 200).apply(context, host))
            val width = (shot.width * density).toInt() + 2 * pad; val height = (72 * density).toInt() + 2 * pad
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            dir!!.mkdirs()
            File(dir, "widget-${shot.name}-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private data class Shot(val name: String, val lang: String, val state: WidgetUiState, val width: Int = 328, val scale: Float = 1f)
}
