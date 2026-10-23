package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.NezzelFormat

class NezzelWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, ids, (context.applicationContext as NezzelApplication).widgets.currentState)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val state = (context.applicationContext as NezzelApplication).widgets.currentState
        manager.updateAppWidget(id, views(AppLocale.localized(context), state, narrow(options)))
    }

    companion object {
        // Below a typical three-cell width leave all space for the mark and the 48dp Paste action.
        private fun narrow(options: Bundle): Boolean = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 200

        fun render(context: Context, ids: IntArray, state: WidgetUiState, language: String = AppLocale.current(context)) {
            val manager = AppWidgetManager.getInstance(context)
            val localized = AppLocale.localized(context, language)
            for (id in ids) manager.updateAppWidget(id, views(localized, state, narrow(manager.getAppWidgetOptions(id))))
        }

        internal fun views(context: Context, state: WidgetUiState, narrow: Boolean): RemoteViews {
            val language = context.resources.configuration.locales[0].language
            val title = state.title?.let(NezzelFormat::isolate).orEmpty()
            val status = when {
                state.title == null -> context.getString(R.string.widget_empty)
                state.paused -> context.getString(R.string.widget_paused)
                state.waiting -> context.getString(R.string.download_waiting)
                state.phase == DownloadPhase.DOWNLOADING && state.percent != null -> context.getString(
                    R.string.update_downloading, NezzelFormat.percent(state.percent, language).let { if (language == "ar") NezzelFormat.isolate(it) else it })
                else -> context.getString(when (state.phase) {
                    DownloadPhase.PREPARING -> R.string.download_preparing
                    DownloadPhase.DOWNLOADING -> R.string.download_transferring
                    DownloadPhase.JOINING -> R.string.download_joining
                    DownloadPhase.CONVERTING -> R.string.download_converting
                    DownloadPhase.SAVING -> R.string.download_saving
                    DownloadPhase.COMPLETED -> R.string.download_saved
                    DownloadPhase.CANCELLED -> R.string.download_cancelled
                    else -> R.string.widget_failed
                })
            }
            return RemoteViews(context.packageName, R.layout.widget_nezzel).apply {
                setInt(R.id.widget_root, "setLayoutDirection", context.resources.configuration.layoutDirection)
                setTextViewText(R.id.widget_paste, context.getString(R.string.shortcut_paste_link))
                setContentDescription(R.id.widget_paste, context.getString(R.string.shortcut_paste_link))
                setTextViewText(R.id.widget_title, title)
                setViewVisibility(R.id.widget_title, if (state.title == null) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_status, status)
                setViewVisibility(R.id.widget_latest, if (narrow) View.GONE else View.VISIBLE)
                setContentDescription(R.id.widget_latest, listOf(context.getString(R.string.downloads), title, status).joinToString(". "))
                setOnClickPendingIntent(R.id.widget_paste, ShortcutActions.pendingIntent(context, ShortcutActions.PASTE_LINK))
                setOnClickPendingIntent(R.id.widget_latest, ShortcutActions.pendingIntent(context, ShortcutActions.OPEN_DOWNLOADS))
            }
        }
    }
}
