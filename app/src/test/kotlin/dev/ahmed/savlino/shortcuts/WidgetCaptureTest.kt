package dev.ahmed.savlino.shortcuts

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale

/**
 * Test-only fixtures on a launcher wallpaper, never production data or network/provider IO.
 * Run with NEZZEL_CAPTURE_DIR pointing to ~/Downloads/Nezzel/widget-w1; skipped in an ordinary gate.
 * Frames go through PixelCopy (hardware rendering) so clipToOutline corners render as on a launcher;
 * crops/ gets one sheet per state, language and theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class WidgetCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private data class Cell(val name: String, val width: Int, val height: Int, val size: WidgetSize)

    @Test fun widget() {
        assumeTrue(dir != null)
        val cells = listOf(
            Cell("wide-250x110", 250, 110, WidgetSize.WIDE),
            Cell("wide-300x150", 300, 150, WidgetSize.WIDE),
            // A phone 4x2 cell (about 360x200dp) picks this bucket.
            Cell("wide-340x190", 340, 190, WidgetSize.WIDE),
            Cell("square-110x110", 110, 110, WidgetSize.SQUARE),
            Cell("square-150x150", 150, 150, WidgetSize.SQUARE),
            Cell("slim-110x48", 110, 48, WidgetSize.SLIM),
        )
        val pictures = listOf(samplePicture(0xFF307B70.toInt(), 0xFFA8D5C8.toInt()),
            samplePicture(0xFF6A76A9.toInt(), 0xFFC2B7D9.toInt()))
        val jobArt = checkNotNull(WidgetArt.of(jobPicture()))
        try {
            for (language in listOf("ar", "en")) {
                val saved = (1..4).map { index ->
                    val mime = when (index) { 3 -> "audio/mp4"; 4 -> "image/jpeg"; else -> "video/mp4" }
                    val name = if (language == "ar") "ملف محفوظ $index" else "Saved file $index"
                    SavedMedia(Uri.parse("content://media/external/downloads/$index"), name, name, mime,
                        100, index.toLong(), null, null, null)
                }
                fun state(jobs: List<DownloadProgress>, art: Boolean = true) = WidgetUiState.from(jobs, saved).let { ui ->
                    ui.copy(recents = ui.recents.mapIndexed { index, recent -> recent.copy(thumbnail = pictures.getOrNull(index)) },
                        // The newest job has its picture; the second one is still waiting for it.
                        jobs = ui.jobs.mapIndexed { index, job -> if (art && index == 0) job.copy(art = jobArt) else job })
                }
                val title = if (language == "ar") "رحلة إلى جبال الأطلس في الشتاء.mp4" else "Sunset timelapse over the old harbour.mp4"
                val first = DownloadProgress("first", title, DownloadPhase.DOWNLOADING, 45, Size.Exact(100),
                    bytesPerSecond = 55.0 / 21, remainingTransferSeconds = 21)
                val second = DownloadProgress("second", if (language == "ar") "رحلة أخرى.mp4" else "Another journey.mp4",
                    DownloadPhase.DOWNLOADING, 80, Size.Exact(100))
                val states = listOf("idle-recents" to state(emptyList()),
                    "idle-empty" to WidgetUiState.from(emptyList()),
                    "one-running" to state(listOf(first)), "one-running-no-picture" to state(listOf(first), art = false),
                    // Newest first: primary gets the supplied ETA; recents remain in the shared snapshot.
                    "two-running-recents" to state(listOf(second, first)))
                for ((name, ui) in states) for (dark in listOf(false, true)) {
                    val crops = cells.map { capture(it, name, language, dark, ui) }
                    sheet(crops, File(checkNotNull(dir), "crops/$name-$language-${if (dark) "dark" else "light"}.png"))
                }
            }
        } finally { pictures.forEach(Bitmap::recycle) }
    }

    private fun capture(cell: Cell, name: String, language: String, dark: Boolean, state: WidgetUiState): Bitmap {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            val locale = Locale.forLanguageTag(language)
            setLocale(locale); setLayoutDirection(locale)
            fontScale = 1f
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        val context = app.createConfigurationContext(config)
        val density = context.resources.displayMetrics.density
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, cell.width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, cell.height)
        }
        val size = WidgetSize.from(options)
        assertEquals(cell.name, cell.size, size)
        val screen = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (dark) intArrayOf(0xFF1F2B2A.toInt(), 0xFF3B4442.toInt())
                else intArrayOf(0xFFB7C9C4.toInt(), 0xFFDCE5E1.toInt()))
        }
        val widget = NezzelWidgetProvider.views(context, state, size, cell.width, cell.height).apply(context, screen)
        screen.addView(widget, FrameLayout.LayoutParams((cell.width * density).toInt(), (cell.height * density).toInt(),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (300 * density).toInt() })
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity.setContentView(screen)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        val width = screen.width; val height = screen.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(activity.window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PixelCopy.SUCCESS, result)
        controller.pause().stop().destroy()
        val target = checkNotNull(dir)
        assertTrue(target.isDirectory || target.mkdirs())
        File(target, "W3-${cell.name}-$name-$language-${if (dark) "dark" else "light"}.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val margin = (16 * density).toInt()
        val crop = Bitmap.createBitmap(bitmap, widget.left - margin, widget.top - margin,
            widget.width + 2 * margin, widget.height + 2 * margin)
        bitmap.recycle()
        return crop
    }

    private fun sheet(crops: List<Bitmap>, file: File) {
        val sheet = Bitmap.createBitmap(crops.sumOf { it.width }, crops.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF808080.toInt())
        var x = 0f
        for (crop in crops) { canvas.drawBitmap(crop, x, 0f, null); x += crop.width; crop.recycle() }
        assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.outputStream().use { assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        sheet.recycle()
    }

    /** A synthetic 160 px job picture (sky, sun, hills): the size and shape JobArtwork keeps, no real media. */
    private fun jobPicture(): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawRect(0f, 0f, 160f, 160f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 160f, 0xFFF2A65A.toInt(), 0xFF7A4E8C.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(108f, 70f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE3A3.toInt() })
        canvas.drawOval(-40f, 100f, 120f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E4A52.toInt() })
        canvas.drawOval(60f, 112f, 220f, 230f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F363D.toInt() })
    }

    /** Synthetic thumbnail swatches exercise bitmap rendering alongside the two missing-thumbnail type tiles. */
    private fun samplePicture(start: Int, end: Int): Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, 96f, 96f, Paint().apply {
            shader = LinearGradient(0f, 0f, 96f, 96f, start, end, Shader.TileMode.CLAMP)
        })
    }
}
