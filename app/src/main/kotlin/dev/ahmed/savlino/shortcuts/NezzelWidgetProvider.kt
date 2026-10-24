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

    /** The first widget on the home screen. */
    override fun onEnabled(context: Context) {
        (context.applicationContext as NezzelApplication).usage.count(dev.ahmed.savlino.usage.UsageKeys.WIDGET_ADDED)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val state = (context.applicationContext as NezzelApplication).widgets.currentState
        manager.updateAppWidget(id, views(AppLocale.localized(context), state, narrow(options)))
    }

    companion object {
        // Two cells leave room for the bar and the status only; the title still reaches TalkBack.
        private fun narrow(options: Bundle): Boolean = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 200

        fun render(context: Context, ids: IntArray, state: WidgetUiState, language: String = AppLocale.current(context)) {
            val manager = AppWidgetManager.getInstance(context)
            val localized = AppLocale.localized(context, language)
            for (id in ids) manager.updateAppWidget(id, views(localized, state, narrow(manager.getAppWidgetOptions(id))))
        }

        internal fun views(context: Context, state: WidgetUiState, narrow: Boolean): RemoteViews {
            val language = context.resources.configuration.locales[0].language
            // First-strong, not LTR: an Arabic title reads right to left and ellipsizes at its own end.
            val title = state.title?.let(NezzelFormat::firstStrong)
            val status = when {
                title == null -> context.getString(R.string.widget_idle)
                state.paused -> context.getString(R.string.widget_paused)
                state.waiting -> context.getString(R.string.download_waiting)
                state.phase == DownloadPhase.DOWNLOADING && state.percent != null ->
                    NezzelFormat.percent(state.percent, language).let { if (language == "ar") NezzelFormat.isolate(it) else it }
                else -> context.getString(when (state.phase) {
                    DownloadPhase.DOWNLOADING -> R.string.download_transferring
                    DownloadPhase.JOINING -> R.string.download_joining
                    DownloadPhase.CONVERTING -> R.string.download_converting
                    DownloadPhase.SAVING -> R.string.download_saving
                    else -> R.string.download_preparing
                })
            }
            // "+1" when more run behind the shown job: isolated, so the sign stays before the digit in RTL.
            val more = state.more.takeIf { it > 0 && title != null }
            val shown = more?.let { "$status · ${NezzelFormat.isolate(context.getString(R.string.widget_more, NezzelFormat.count(it, language)))}" } ?: status
            val spoken = listOfNotNull(context.getString(R.string.downloads), title, status,
                more?.let { context.resources.getQuantityString(R.plurals.widget_more_running, it, NezzelFormat.count(it, language)) })
            // A real percent fills the bar; preparing, queued and unknown sizes move without a number. Paused freezes, dimmed.
            val live = title != null
            val determinate = state.paused || state.percent != null && !state.waiting
            return RemoteViews(context.packageName, R.layout.widget_nezzel).apply {
                setInt(R.id.widget_root, "setLayoutDirection", context.resources.configuration.layoutDirection)
                setTextViewText(R.id.widget_title, title.orEmpty())
                setViewVisibility(R.id.widget_title, if (live && !narrow) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_status, shown)
                setViewVisibility(R.id.widget_progress, if (live && !state.paused) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.widget_progress_paused, if (live && state.paused) View.VISIBLE else View.GONE)
                if (live) setProgressBar(if (state.paused) R.id.widget_progress_paused else R.id.widget_progress,
                    100, state.percent ?: 0, !determinate)
                setContentDescription(R.id.widget_root, spoken.joinToString(". "))
                setOnClickPendingIntent(R.id.widget_root, ShortcutActions.pendingIntent(context, ShortcutActions.OPEN_DOWNLOADS))
            }
        }
    }
}
