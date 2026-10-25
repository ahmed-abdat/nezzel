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

        /**
         * Progress tick with an unchanged layout. Before 31 only the text/bar actions are merged into the
         * host's views. From 31 the host keeps sized RemoteViews and RemoteViews.mergeRemoteViews drops
         * actions merged into them, so a full update is sent; the same layout ids are reapplied in place
         * (no reinflation) and live states carry no thumbnails.
         */
        fun renderProgress(context: Context, ids: IntArray, state: WidgetUiState, language: String = AppLocale.current(context)) {
            if (Build.VERSION.SDK_INT >= 31) return render(context, ids, state, language)
            val manager = AppWidgetManager.getInstance(context)
            val localized = AppLocale.localized(context, language)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val size = WidgetSize.from(options)
                // The square's ring is a bitmap: keep bitmaps out of merged partial updates.
                if (size == WidgetSize.SQUARE) manager.updateAppWidget(id, responsiveViews(localized, state, options))
                else manager.partiallyUpdateAppWidget(id, progressViews(localized, state, size,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.bounds.height.toInt())))
            }
        }

        internal fun progressViews(context: Context, state: WidgetUiState, size: WidgetSize,
            heightDp: Int = size.bounds.height.toInt()): RemoteViews =
            RemoteViews(context.packageName, size.layout).apply {
                val language = context.resources.configuration.locales[0].language
                for ((index, row) in state.jobs.take(if (size == WidgetSize.WIDE) 2 else 1).withIndex())
                    bindProgress(context, rowIds(index), row, size, heightDp, index, language, withRing = false)
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
            // One spacing scale: 8dp padding (outer radius - inner radius) and 8dp gaps. At the 110dp minimum
            // height a 48dp Paste pill and 48dp tiles only fit with 5dp vertical padding and a 4dp gap.
            val compact = heightDp < 120
            // At 110dp wide, 6dp sides fit two 48dp controls (square) or the Paste label (slim).
            val paddingDp = if (size != WidgetSize.WIDE && widthDp < 120) 6 else 8
            val verticalDp = if (size == WidgetSize.SLIM) 0 else if (compact) 5 else 8
            val gapDp = if (compact) 4 else 8
            val density = context.resources.displayMetrics.density
            val downloads = ShortcutActions.pendingIntent(context, ShortcutActions.OPEN_DOWNLOADS)
            return RemoteViews(context.packageName, size.layout).apply {
                setInt(ROOT, "setLayoutDirection", context.resources.configuration.layoutDirection)
                val padding = (paddingDp * density).toInt()
                val vertical = (verticalDp * density).toInt()
                setViewPadding(ROOT, padding, vertical, padding, vertical)
                visible(R.id.widget_live, live)
                setContentDescription(ROOT, context.getString(R.string.widget_description))
                setOnClickPendingIntent(ROOT, downloads)
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
                val showRecents = !live && state.recents.isNotEmpty() && size != WidgetSize.SLIM
                visible(R.id.widget_recents, showRecents)
                setViewPadding(R.id.widget_recents, 0, (gapDp * density).toInt(), 0, 0)
                val recentIds = listOf(R.id.widget_recent_1, R.id.widget_recent_2, R.id.widget_recent_3, R.id.widget_recent_4)
                val recentCount = minOf(state.recents.size, if (wide) 4 else 1)
                val gapIds = listOf(R.id.widget_recent_gap_1, R.id.widget_recent_gap_2, R.id.widget_recent_gap_3)
                for ((index, id) in gapIds.withIndex()) visible(id, index < recentCount - 1)
                val tileWidth = (widthDp - 2 * paddingDp - (recentCount - 1) * 8).toFloat() / recentCount.coerceAtLeast(1)
                val tileHeight = (heightDp - 2 * verticalDp - 48 - gapDp).toFloat()
                // At the 110dp minimum width a square keeps one 48dp tile; wide has room for four.
                // Hidden tiles get no bitmaps, so live progress updates stay small.
                for ((index, id) in recentIds.withIndex()) {
                    val recent = state.recents.getOrNull(index)?.takeIf { showRecents && (wide || index < 1) }
                    visible(id, recent != null)
                    if (recent != null) {
                        if (recent.thumbnail != null) {
                            if (Build.VERSION.SDK_INT >= 31) {
                                setViewOutlinePreferredRadiusDimen(id, R.dimen.widget_inner_radius)
                                setImageViewBitmap(id, recent.thumbnail)
                            } else setImageViewBitmap(id, roundedThumbnail(recent.thumbnail, tileWidth, tileHeight,
                                density, context.resources.getDimension(R.dimen.widget_inner_radius) / density))
                            setViewPadding(id, 0, 0, 0, 0)
                        } else {
                            setImageViewResource(id, when {
                                recent.media.audio -> R.drawable.ic_widget_music
                                recent.media.mimeType.startsWith("image/") -> R.drawable.ic_widget_image
                                else -> R.drawable.ic_widget_movie
                            })
                            // centerCrop scales the vector to the content box: keep it a 24dp square.
                            val sideX = ((tileWidth - 24) / 2 * density).toInt().coerceAtLeast(0)
                            val sideY = ((tileHeight - 24) / 2 * density).toInt().coerceAtLeast(0)
                            setViewPadding(id, sideX, sideY, sideX, sideY)
                        }
                        setContentDescription(id, context.getString(R.string.widget_open_saved, NezzelFormat.firstStrong(recent.media.displayName)))
                        setOnClickPendingIntent(id, ShortcutActions.savedPendingIntent(context, recent.media.uri))
                    }
                }
                for (index in 0..1) {
                    val row = shown.getOrNull(index)
                    val ids = rowIds(index)
                    visible(ids.root, row != null)
                    if (row == null) continue
                    val title = NezzelFormat.firstStrong(row.title)
                    setTextViewText(ids.title, title)
                    visible(ids.title, wide)
                    val remaining = bindProgress(context, ids, row, size, heightDp, index, language)
                    visible(ids.remaining, remaining != null && wide && heightDp >= 150 && index == 0 && context.resources.configuration.fontScale <= 1.1f)
                    val showBar = size != WidgetSize.SQUARE && row.phase != DownloadPhase.FAILED
                    visible(ids.progress, showBar && !row.paused)
                    visible(ids.pausedProgress, showBar && row.paused)
                    setOnClickPendingIntent(ids.root, downloads)
                    visible(ids.control, row.controllable)
                    setImageViewResource(ids.control, if (row.paused) R.drawable.ic_widget_resume else R.drawable.ic_pause)
                    setContentDescription(ids.control, context.getString(if (row.paused) R.string.download_resume_accessibility else R.string.download_pause_accessibility, title))
                    if (row.controllable) setOnClickPendingIntent(ids.control, control(context, row))
                }
            }
        }

        /** Everything a progress tick may change; visibility and click targets stay with the full update. */
        private fun RemoteViews.bindProgress(context: Context, ids: RowIds, row: WidgetJob, size: WidgetSize,
            heightDp: Int, index: Int, language: String, withRing: Boolean = true): String? {
            val title = NezzelFormat.firstStrong(row.title)
            val status = status(context, row, language)
            setTextViewText(ids.status, status)
            val remaining = row.remainingSeconds?.let {
                context.getString(R.string.download_transfer_remaining, NezzelFormat.isolate(NezzelFormat.duration(it)))
            }
            setTextViewText(ids.remaining, remaining.orEmpty())
            setProgressBar(if (row.paused) ids.pausedProgress else ids.progress,
                100, row.percent ?: 0, row.percent == null && !row.paused)
            setContentDescription(ids.root, listOfNotNull(title, status, remaining).joinToString(". "))
            if (withRing && index == 0 && size == WidgetSize.SQUARE) setImageViewBitmap(R.id.widget_ring, ring(context, row))
            return remaining
        }

        private fun rowIds(index: Int) = if (index == 0) RowIds(R.id.widget_job, R.id.widget_title, R.id.widget_status,
            R.id.widget_progress, R.id.widget_progress_paused, R.id.widget_remaining, R.id.widget_control)
        else RowIds(R.id.widget_job_second, R.id.widget_title_second, R.id.widget_status_second,
            R.id.widget_progress_second, R.id.widget_progress_paused_second, R.id.widget_remaining_second, R.id.widget_control_second)

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

        /** Pre-31 fallback: crop to the actual tile aspect ratio, then mask the inner radius before parceling. */
        private fun roundedThumbnail(source: Bitmap, widthDp: Float, heightDp: Float, density: Float, radiusDp: Float): Bitmap {
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
            val radius = radiusDp * pixelsPerDp
            Canvas(result).drawRoundRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), radius, radius, paint)
            return result
        }

        /** Decorative ring only; the separately labelled text is the source of truth for unknown progress. */
        private fun ring(context: Context, job: WidgetJob): Bitmap {
            // 48dp at 3x with a 4dp stroke, like the bars.
            val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND }
            paint.color = context.getColor(R.color.widget_track)
            canvas.drawCircle(72f, 72f, 66f, paint)
            job.percent?.let {
                paint.color = context.getColor(R.color.widget_primary)
                paint.alpha = if (job.paused) 128 else 255
                canvas.drawArc(6f, 6f, 138f, 138f, -90f, it * 3.6f, false, paint)
            }
            return bitmap
        }

        /** @android:id/background lets the launcher animate from the widget into the app (API 31+). */
        private const val ROOT = android.R.id.background

        private fun RemoteViews.visible(id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
        private data class RowIds(val root: Int, val title: Int, val status: Int, val progress: Int, val pausedProgress: Int, val remaining: Int, val control: Int)
    }
}
