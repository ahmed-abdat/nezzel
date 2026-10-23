package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun job(phase: DownloadPhase = DownloadPhase.DOWNLOADING, bytes: Long = 37) =
        DownloadProgress("new", "clip.mp4", phase, bytes, Size.Exact(100))
    private fun view(state: WidgetUiState, narrow: Boolean = false, language: String = "en"): View {
        val config = Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
        val context = app.createConfigurationContext(config)
        return NezzelWidgetProvider.views(context, state, narrow).apply(context, FrameLayout(context))
    }
    private fun status(view: View) = view.findViewById<TextView>(R.id.widget_status).text.toString()

    @Test fun remoteViewsShowRealStatesAndFloorPercentToFivePointBuckets() {
        assertEquals("Copy a link, then tap Paste", status(view(WidgetUiState())))
        assertEquals("Downloading 35%", status(view(WidgetUiState.from(job()))))
        assertEquals("Paused", status(view(WidgetUiState.from(job().copy(paused = true)))))
        assertEquals("Saved", status(view(WidgetUiState.from(job(DownloadPhase.COMPLETED)))))
        assertEquals("Didn't finish", status(view(WidgetUiState.from(job(DownloadPhase.FAILED)))))
        assertEquals("Didn't finish", status(view(WidgetUiState.from(job(DownloadPhase.FAILED).copy(awaitingSchedule = true, waiting = true)))))
        assertEquals("Downloading", status(view(WidgetUiState.from(job().copy(total = Size.Unknown)))))
        assertEquals("Preparing…", status(view(WidgetUiState.from(job(DownloadPhase.PREPARING)))))
        assertEquals("Saving…", status(view(WidgetUiState.from(job(DownloadPhase.SAVING)))))
    }

    @Test fun arabicUsesAppFormatterAndIsolatesLtrFilename() {
        val rendered = view(WidgetUiState.from(job()), language = "ar")
        assertEquals("جارٍ التنزيل \u206635%\u2069", status(rendered))
        assertEquals("\u2066clip.mp4\u2069", rendered.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals(View.LAYOUT_DIRECTION_RTL, rendered.layoutDirection)
    }

    @Test fun narrowHidesLatestAndActionsOpenTheActivityWithImmutableIntents() {
        assertEquals(View.GONE, view(WidgetUiState.from(job()), narrow = true).findViewById<View>(R.id.widget_latest).visibility)
        val rendered = view(WidgetUiState.from(job()))
        rendered.findViewById<View>(R.id.widget_paste).performClick()
        val paste = shadowOf(app).nextStartedActivity
        assertEquals(ShortcutActions.PASTE_LINK, paste.action)
        assertEquals(ComponentName(app.packageName, "dev.ahmed.savlino.PasteEntry"), paste.component)
        rendered.findViewById<View>(R.id.widget_latest).performClick()
        assertEquals(ShortcutActions.OPEN_DOWNLOADS, shadowOf(app).nextStartedActivity.action)
        for (action in listOf(ShortcutActions.PASTE_LINK, ShortcutActions.OPEN_DOWNLOADS))
            assertTrue(shadowOf(ShortcutActions.pendingIntent(app, action)).isImmutable)
    }

    @Test fun resizingUsesCurrentStateAndRendersNarrowLayout() {
        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(NezzelWidgetProvider::class.java, R.layout.widget_nezzel)
        NezzelWidgetProvider().onAppWidgetOptionsChanged(app, manager, id,
            Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110) })
        assertEquals(View.GONE, shadow.getViewFor(id).findViewById<View>(R.id.widget_latest).visibility)
    }

    @Test @Config(sdk = [29, 33]) fun choosingLanguageRendersExistingWidgetsWithoutProgressChanges() {
        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(NezzelWidgetProvider::class.java, R.layout.widget_nezzel)
        dev.ahmed.savlino.locale.AppLocale.choose(app, "fr")
        assertEquals("Coller un lien", shadow.getViewFor(id).findViewById<TextView>(R.id.widget_paste).text.toString())
        assertEquals("Copiez un lien, puis appuyez sur Coller", status(shadow.getViewFor(id)))
        dev.ahmed.savlino.locale.AppLocale.choose(app, "ar")
        assertEquals("الصق الرابط", shadow.getViewFor(id).findViewById<TextView>(R.id.widget_paste).text.toString())
        assertEquals("انسخ رابطًا ثم اضغط الصق", status(shadow.getViewFor(id)))
        assertEquals(View.LAYOUT_DIRECTION_RTL, shadow.getViewFor(id).layoutDirection)
    }

    @Test fun updaterCoalescesProgressAndDeliversLatestAfterTwoSeconds() = runTest {
        val progress = MutableStateFlow(listOf(job()))
        val calls = mutableListOf<Pair<Long, WidgetUiState>>()
        val updater = WidgetUpdater(app, progress, widgetIds = { intArrayOf(1) },
            render = { state, _ -> calls += testScheduler.currentTime to state })
        val collection = updater.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(0L), calls.map { it.first })
        progress.value = listOf(job(bytes = 39)); runCurrent()
        advanceTimeBy(1999); runCurrent()
        assertEquals(1, calls.size)
        progress.value = listOf(job(bytes = 47)); runCurrent()
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 2000L), calls.map { it.first })
        assertEquals(45, calls.last().second.percent)
        collection.cancel()
    }

    @Test fun updaterDoesNothingWithoutWidgetsAndRendersWhenOneIsAdded() = runTest {
        val progress = MutableStateFlow(emptyList<DownloadProgress>())
        var ids = intArrayOf()
        val calls = mutableListOf<WidgetUiState>()
        val updater = WidgetUpdater(app, progress, widgetIds = { ids }, render = { state, _ -> calls += state })
        val collection = updater.start(backgroundScope)
        runCurrent()
        progress.value = listOf(job()); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertTrue(calls.isEmpty())
        ids = intArrayOf(1)
        progress.value = listOf(job(bytes = 60)); runCurrent()
        assertEquals(60, calls.single().percent)
        assertEquals(60, updater.currentState.percent)
        collection.cancel()
    }

    @Test fun newestJobWinsAndCompletionSurvivesItsRemovalFromProgress() = runTest {
        val old = job().copy(jobId = "old", filename = "old.mp4")
        val progress = MutableStateFlow(listOf(old, job()))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf(1) }, render = { _, _ -> })
        val collection = updater.start(backgroundScope)
        runCurrent()
        assertEquals("clip.mp4", updater.currentState.title)
        completed.emit(job(DownloadPhase.COMPLETED)); runCurrent()
        progress.value = listOf(old); runCurrent()
        assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
        assertEquals("clip.mp4", updater.currentState.title)
        progress.value = listOf(old, job().copy(jobId = "next", filename = "next.mp4")); runCurrent()
        assertEquals("next.mp4", updater.currentState.title)
        collection.cancel()
    }

    @Test fun completionStillWinsWhenSnapshotRemovalArrivesBeforeCompletionEvent() = runTest {
        val old = job().copy(jobId = "old", filename = "old.mp4")
        val progress = MutableStateFlow(listOf(old, job()))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf(1) }, render = { _, _ -> })
        val collection = updater.start(backgroundScope)
        runCurrent()
        progress.value = listOf(old); runCurrent()
        completed.emit(job(DownloadPhase.COMPLETED)); runCurrent()
        assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
        assertEquals("clip.mp4", updater.currentState.title)
        collection.cancel()
    }

    @Test fun latestCompletionOverridesSnapshotUntilANewJobIsAdmitted() = runTest {
        val old = job().copy(jobId = "old", filename = "old.mp4")
        val progress = MutableStateFlow(listOf(old, job()))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf(1) }, render = { _, _ -> })
        val collection = updater.start(backgroundScope)
        runCurrent()
        completed.emit(old.copy(phase = DownloadPhase.COMPLETED)); runCurrent()
        progress.value = listOf(job(bytes = 60)); runCurrent()
        assertEquals("old.mp4", updater.currentState.title)
        assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
        progress.value = listOf(job(bytes = 70)); runCurrent()
        assertEquals("old.mp4", updater.currentState.title)
        progress.value = listOf(job(), job().copy(jobId = "next", filename = "next.mp4")); runCurrent()
        assertEquals("next.mp4", updater.currentState.title)
        assertEquals(DownloadPhase.DOWNLOADING, updater.currentState.phase)
        collection.cancel()
    }
}
