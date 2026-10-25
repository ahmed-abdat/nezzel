package dev.ahmed.savlino.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.NezzelFormat

internal enum class WidgetSize(val layout: Int, val bounds: SizeF) {
    SLIM(R.layout.widget_nezzel_slim, SizeF(110f, 40f)),
    SQUARE(R.layout.widget_nezzel_square, SizeF(110f, 110f)),
    WIDE(R.layout.widget_nezzel, SizeF(250f, 110f));

    companion object {
        fun from(options: Bundle): WidgetSize = when {
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) < 110 -> SLIM
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 250 -> SQUARE
            else -> WIDE
        }
    }
}

class NezzelWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val updater = (context.applicationContext as NezzelApplication).widgets
        updater.refresh()
        render(context, ids, updater.currentState)
    }

    override fun onEnabled(context: Context) {
        val app = context.applicationContext as NezzelApplication
        app.usage.count(dev.ahmed.savlino.usage.UsageKeys.WIDGET_ADDED)
        app.widgets.refresh()
    }

    override fun onDisabled(context: Context) {
        (context.applicationContext as NezzelApplication).widgets.refresh()
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val updater = (context.applicationContext as NezzelApplication).widgets
        updater.refresh()
        manager.updateAppWidget(id, responsiveViews(AppLocale.localized(context), updater.currentState, options))
    }

    companion object {
        fun render(context: Context, ids: IntArray, state: WidgetUiState, language: String = AppLocale.current(context)) {
            val manager = AppWidgetManager.getInstance(context)
            val localized = AppLocale.localized(context, language)
            for (id in ids) manager.updateAppWidget(id, responsiveViews(localized, state, manager.getAppWidgetOptions(id)))
        }

        internal fun responsiveViews(context: Context, state: WidgetUiState, options: Bundle): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31) {
                val sizes = WidgetSize.entries.map { it.bounds to it } + listOf(
                    SizeF(110f, 120f) to WidgetSize.SQUARE,
                    SizeF(150f, 150f) to WidgetSize.SQUARE,
                    SizeF(250f, 120f) to WidgetSize.WIDE,
                    SizeF(250f, 150f) to WidgetSize.WIDE,
                    SizeF(300f, 150f) to WidgetSize.WIDE,
                )
                return RemoteViews(sizes.associate { (bounds, size) -> bounds to views(context, state, size,
                    bounds.width.toInt(), bounds.height.toInt()) })
            }
            val size = WidgetSize.from(options)
            return views(context, state, size,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.bounds.width.toInt()),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.bounds.height.toInt()))
        }

        // Kept for the existing capture helper; new hosts select all three size buckets.
        internal fun views(context: Context, state: WidgetUiState, narrow: Boolean): RemoteViews =
            views(context, state, if (narrow) WidgetSize.SLIM else WidgetSize.WIDE)

        internal fun views(context: Context, state: WidgetUiState, size: WidgetSize,
            widthDp: Int = size.bounds.width.toInt(), heightDp: Int = size.bounds.height.toInt()): RemoteViews {
            val language = context.resources.configuration.locales[0].language
            val live = state.jobs.isNotEmpty()
            val wide = size == WidgetSize.WIDE
            val shown = state.jobs.take(if (wide) 2 else 1)
            val more = state.more + state.jobs.size - shown.size
            val paddingDp = if (size == WidgetSize.SLIM || heightDp < 120 || live) 6 else 8
            val gapDp = if (heightDp < 120) 2 else 8
            val density = context.resources.displayMetrics.density
            val downloads = ShortcutActions.pendingIntent(context, ShortcutActions.OPEN_DOWNLOADS)
            return RemoteViews(context.packageName, size.layout).apply {
                setInt(R.id.widget_root, "setLayoutDirection", context.resources.configuration.layoutDirection)
                val padding = (paddingDp * density).toInt()
                setViewPadding(R.id.widget_root, padding, if (size == WidgetSize.SLIM) 0 else padding,
                    padding, if (size == WidgetSize.SLIM) 0 else padding)
                visible(R.id.widget_live, live)
                setContentDescription(R.id.widget_root, context.getString(R.string.widget_description))
                setOnClickPendingIntent(R.id.widget_root, downloads)
                setTextViewText(R.id.widget_saved_count, context.getString(R.string.widget_saved_count, NezzelFormat.count(state.savedCount, language)))
                visible(R.id.widget_saved_count, !live && size != WidgetSize.SLIM)
                visible(R.id.widget_paste, !live)
                visible(R.id.widget_paste_icon, live && size != WidgetSize.SLIM)
                visible(R.id.widget_slim_live, live)
                visible(R.id.widget_square_controls, live)
                setOnClickPendingIntent(R.id.widget_paste, ShortcutActions.pendingIntent(context, ShortcutActions.PASTE_LINK))
                setOnClickPendingIntent(R.id.widget_paste_icon, ShortcutActions.pendingIntent(context, ShortcutActions.PASTE_LINK))
                visible(R.id.widget_more, more > 0 && size != WidgetSize.SLIM)
                setTextViewText(R.id.widget_more, NezzelFormat.isolate(context.getString(R.string.widget_more, NezzelFormat.count(more, language))))
                setContentDescription(R.id.widget_more, context.resources.getQuantityString(R.plurals.widget_more_accessibility, more, NezzelFormat.count(more, language)))
                visible(R.id.widget_recents, !live && state.recents.isNotEmpty() && size != WidgetSize.SLIM)
                setViewPadding(R.id.widget_recents, 0, (gapDp * density).toInt(), 0, 0)
                val recentIds = listOf(R.id.widget_recent_1, R.id.widget_recent_2, R.id.widget_recent_3, R.id.widget_recent_4)
                val recentCount = minOf(state.recents.size, if (wide) 4 else 1)
                val gapIds = listOf(R.id.widget_recent_gap_1, R.id.widget_recent_gap_2, R.id.widget_recent_gap_3)
                for ((index, id) in gapIds.withIndex()) visible(id, index < recentCount - 1)
                // At the 110dp minimum width a square keeps one 48dp tile; wide has room for four.
                for ((index, id) in recentIds.withIndex()) {
                    val recent = state.recents.getOrNull(index)?.takeIf { wide || index < 1 }
                    visible(id, recent != null)
                    if (recent != null) {
                        if (recent.thumbnail != null) {
                            val image = if (Build.VERSION.SDK_INT >= 31) recent.thumbnail else roundedThumbnail(recent.thumbnail,
                                (widthDp - 2 * paddingDp - (recentCount - 1) * 8).toFloat() / recentCount,
                                (heightDp - 2 * paddingDp - 48 - gapDp).toFloat(), density)
                            setImageViewBitmap(id, image)
                        }
                        else setImageViewResource(id, when {
                            recent.media.audio -> R.drawable.ic_widget_music
                            recent.media.mimeType.startsWith("image/") -> R.drawable.ic_widget_image
                            else -> R.drawable.ic_widget_movie
                        })
                        val padding = if (recent.thumbnail == null) (12 * context.resources.displayMetrics.density).toInt() else 0
                        setViewPadding(id, padding, padding, padding, padding)
                        setContentDescription(id, context.getString(R.string.widget_open_saved, NezzelFormat.firstStrong(recent.media.displayName)))
                        setOnClickPendingIntent(id, ShortcutActions.savedPendingIntent(context, recent.media.uri))
                    }
                }
                for (index in 0..1) {
                    val row = shown.getOrNull(index)
                    val ids = if (index == 0) RowIds(R.id.widget_job, R.id.widget_title, R.id.widget_status,
                        R.id.widget_progress, R.id.widget_progress_paused, R.id.widget_remaining, R.id.widget_control)
                    else RowIds(R.id.widget_job_second, R.id.widget_title_second, R.id.widget_status_second,
                        R.id.widget_progress_second, R.id.widget_progress_paused_second, R.id.widget_remaining_second, R.id.widget_control_second)
                    visible(ids.root, row != null)
                    if (row == null) continue
                    val title = NezzelFormat.firstStrong(row.title)
                    val status = status(context, row, language)
                    setTextViewText(ids.title, title)
                    visible(ids.title, wide)
                    setTextViewText(ids.status, status)
                    val remaining = row.remainingSeconds?.let {
                        context.getString(R.string.download_transfer_remaining, NezzelFormat.isolate(NezzelFormat.duration(it)))
                    }
                    setTextViewText(ids.remaining, remaining.orEmpty())
                    visible(ids.remaining, remaining != null && wide && heightDp >= 150 && index == 0 && context.resources.configuration.fontScale <= 1.1f)
                    val showBar = size != WidgetSize.SQUARE && row.phase != DownloadPhase.FAILED
                    visible(ids.progress, showBar && !row.paused)
                    visible(ids.pausedProgress, showBar && row.paused)
                    setProgressBar(if (row.paused) ids.pausedProgress else ids.progress,
                        100, row.percent ?: 0, row.percent == null && !row.paused)
                    setContentDescription(ids.root, listOfNotNull(title, status, remaining).joinToString(". "))
                    setOnClickPendingIntent(ids.root, downloads)
                    visible(ids.control, row.controllable)
                    setImageViewResource(ids.control, if (row.paused) R.drawable.ic_widget_resume else R.drawable.ic_pause)
                    setContentDescription(ids.control, context.getString(if (row.paused) R.string.download_resume_accessibility else R.string.download_pause_accessibility, title))
                    if (row.controllable) setOnClickPendingIntent(ids.control, control(context, row))
                    if (index == 0 && size == WidgetSize.SQUARE) setImageViewBitmap(R.id.widget_ring, ring(context, row))
                }
            }
        }

        internal fun control(context: Context, job: WidgetJob): PendingIntent {
            val action = if (job.paused) DownloadNotifications.RESUME else DownloadNotifications.PAUSE
            return PendingIntent.getBroadcast(context, 0, Intent(context, DownloadControlReceiver::class.java)
                .setAction(action).setData(Uri.Builder().scheme("nezzel").authority("widget-control").appendPath(action).appendPath(job.id).build())
                .putExtra(DownloadWorker.JOB_ID, job.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun status(context: Context, job: WidgetJob, language: String): String = when {
            job.phase == DownloadPhase.FAILED -> context.getString(R.string.widget_failed)
            job.paused -> context.getString(R.string.widget_paused)
            job.waiting -> context.getString(R.string.download_waiting)
            job.percent != null -> NezzelFormat.percent(job.percent, language).let { if (language == "ar") NezzelFormat.isolate(it) else it }
            else -> context.getString(when (job.phase) {
                DownloadPhase.DOWNLOADING -> R.string.download_transferring
                DownloadPhase.JOINING -> R.string.download_joining
                DownloadPhase.CONVERTING -> R.string.download_converting
                DownloadPhase.SAVING -> R.string.download_saving
                else -> R.string.download_preparing
            })
        }

        /** Pre-31 fallback: crop to the actual tile aspect ratio, then mask 12dp corners before parceling. */
        private fun roundedThumbnail(source: Bitmap, widthDp: Float, heightDp: Float, density: Float): Bitmap {
            val width = widthDp.coerceAtLeast(1f)
            val height = heightDp.coerceAtLeast(1f)
            val pixelsPerDp = minOf(density, 192f / maxOf(width, height))
            val result = Bitmap.createBitmap((width * pixelsPerDp).toInt().coerceAtLeast(1),
                (height * pixelsPerDp).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val scale = maxOf(result.width.toFloat() / source.width, result.height.toFloat() / source.height)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((result.width - source.width * scale) / 2, (result.height - source.height * scale) / 2)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            val radius = 12 * pixelsPerDp
            Canvas(result).drawRoundRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), radius, radius, paint)
            return result
        }

        /** Decorative ring only; the separately labelled text is the source of truth for unknown progress. */
        private fun ring(context: Context, job: WidgetJob): Bitmap {
            val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND }
            paint.color = context.getColor(R.color.widget_track)
            canvas.drawCircle(72f, 72f, 62f, paint)
            job.percent?.let {
                paint.color = context.getColor(R.color.widget_primary)
                paint.alpha = if (job.paused) 128 else 255
                canvas.drawArc(10f, 10f, 134f, 134f, -90f, it * 3.6f, false, paint)
            }
            return bitmap
        }

        private fun RemoteViews.visible(id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
        private data class RowIds(val root: Int, val title: Int, val status: Int, val progress: Int, val pausedProgress: Int, val remaining: Int, val control: Int)
    }
}
