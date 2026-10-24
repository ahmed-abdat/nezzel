package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private fun title(view: View) = view.findViewById<TextView>(R.id.widget_title)
    private fun bar(view: View) = view.findViewById<ProgressBar>(R.id.widget_progress)
    private fun pausedBar(view: View) = view.findViewById<ProgressBar>(R.id.widget_progress_paused)
    private fun spoken(view: View) = view.findViewById<View>(R.id.widget_root).contentDescription.toString()

    @Test fun liveJobShowsTitleWithoutExtensionBucketedPercentAndARealBar() {
        val rendered = view(WidgetUiState.from(job()))
        assertEquals("⁨clip⁩", title(rendered).text.toString())
        assertEquals(View.VISIBLE, title(rendered).visibility)
        assertEquals("35%", status(rendered))
        assertEquals(View.VISIBLE, bar(rendered).visibility)
        assertFalse(bar(rendered).isIndeterminate)
        assertEquals(35, bar(rendered).progress)
        assertEquals(View.GONE, pausedBar(rendered).visibility)
        assertEquals("Downloads. ⁨clip⁩. 35%", spoken(rendered))
    }

    @Test fun idleAndEveryFinishedJobShowOnlyTheIdleLine() {
        for (state in listOf(WidgetUiState(), WidgetUiState.from(job(DownloadPhase.COMPLETED)), WidgetUiState.from(job(DownloadPhase.FAILED)),
            WidgetUiState.from(job(DownloadPhase.FAILED).copy(awaitingSchedule = true, waiting = true)),
            WidgetUiState.from(listOf(job(DownloadPhase.CANCELLED), job(DownloadPhase.COMPLETED))))) {
            val rendered = view(state)
            assertEquals("No downloads right now", status(rendered))
            assertEquals(View.GONE, title(rendered).visibility)
            assertEquals(View.GONE, bar(rendered).visibility)
            assertEquals(View.GONE, pausedBar(rendered).visibility)
            assertEquals("Downloads. No downloads right now", spoken(rendered))
        }
    }

    @Test fun pausedFreezesADimmedBarAndPhasesWithoutAPercentMoveWithoutANumber() {
        val paused = view(WidgetUiState.from(job(bytes = 52).copy(paused = true)))
        assertEquals("Paused", status(paused))
        assertEquals(View.GONE, bar(paused).visibility)
        assertEquals(View.VISIBLE, pausedBar(paused).visibility)
        assertFalse(pausedBar(paused).isIndeterminate)
        assertEquals(50, pausedBar(paused).progress)
        assertEquals(0.5f, pausedBar(paused).alpha)
        for ((state, text) in listOf(job(DownloadPhase.PREPARING) to "Preparing…", job().copy(total = Size.Unknown) to "Downloading",
            job(DownloadPhase.SAVING) to "Saving…", job(DownloadPhase.CONVERTING) to "Converting to MP3…",
            job(DownloadPhase.PREPARING).copy(waiting = true) to "Queued")) {
            val rendered = view(WidgetUiState.from(state))
            assertEquals(text, status(rendered))
            assertEquals(View.VISIBLE, bar(rendered).visibility)
            assertTrue(text, bar(rendered).isIndeterminate)
        }
    }

    @Test fun titleDropsOnlyAnExtensionLikeSuffix() {
        assertEquals("clip", WidgetUiState.title("clip.mp4"))
        assertEquals("Sunset", WidgetUiState.title("Sunset.webm"))
        assertEquals("Part 1. Intro", WidgetUiState.title("Part 1. Intro"))
        assertEquals("Release v1.2", WidgetUiState.title("Release v1.2"))
        assertEquals("song", WidgetUiState.title("song.m4a"))
        assertEquals(".mp4", WidgetUiState.title(".mp4"))
        assertEquals("a.b", WidgetUiState.title(" a.b.mkv "))
    }

    @Test fun arabicTitleReadsRightToLeftAndPercentStaysIsolated() {
        val rendered = view(WidgetUiState.from(job().copy(filename = "رحلة إلى الجبل.mp4")), language = "ar")
        assertEquals("⁨رحلة إلى الجبل⁩", title(rendered).text.toString())
        assertEquals("⁦35%⁩", status(rendered))
        assertEquals(View.LAYOUT_DIRECTION_RTL, rendered.layoutDirection)
        assertEquals("لا تنزيلات الآن", status(view(WidgetUiState(), language = "ar")))
        assertEquals("Aucun téléchargement en cours", status(view(WidgetUiState(), language = "fr")))
    }

    @Test fun newestRunningJobLeadsWithACountOfTheOthersRunningButNotTheQueue() {
        val older = job().copy(jobId = "older", filename = "older.mp4")
        val newer = job(bytes = 60).copy(jobId = "newer", filename = "newer.mp4")
        val queued = job(DownloadPhase.PREPARING).copy(jobId = "queued", filename = "queued.mp4", waiting = true)
        val state = WidgetUiState.from(listOf(older, newer, queued))
        assertEquals("newer", state.title)
        assertEquals(1, state.more)
        val rendered = view(state)
        assertEquals("60% · ⁦+1⁩", status(rendered))
        assertEquals("Downloads. ⁨newer⁩. 60%. 1 more running", spoken(rendered))
        val arabic = view(state, language = "ar")
        assertEquals("⁦60%⁩ · ⁦+1⁩", status(arabic))
        assertTrue(spoken(arabic).endsWith("تنزيل آخر جارٍ"))
        // One running job, or only a queue: no count.
        assertEquals(0, WidgetUiState.from(listOf(older, queued)).more)
        assertEquals("Queued", status(view(WidgetUiState.from(listOf(queued)))))
        // Paused behind nothing running still shows; a finished newer job never wins.
        assertEquals("older", WidgetUiState.from(listOf(older.copy(paused = true), job(DownloadPhase.COMPLETED))).title)
    }

    @Test fun narrowHidesOnlyTheTitleAndTheWholeWidgetOpensDownloads() {
        val narrow = view(WidgetUiState.from(job()), narrow = true)
        assertEquals(View.GONE, title(narrow).visibility)
        assertEquals("35%", status(narrow))
        assertEquals(View.VISIBLE, bar(narrow).visibility)
        assertEquals("Downloads. ⁨clip⁩. 35%", spoken(narrow))
        narrow.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(ShortcutActions.OPEN_DOWNLOADS, shadowOf(app).nextStartedActivity.action)
        view(WidgetUiState()).findViewById<View>(R.id.widget_root).performClick()
        assertEquals(ShortcutActions.OPEN_DOWNLOADS, shadowOf(app).nextStartedActivity.action)
        assertTrue(shadowOf(ShortcutActions.pendingIntent(app, ShortcutActions.OPEN_DOWNLOADS)).isImmutable)
        // The Paste button is gone from the widget (the tile and launcher shortcut keep pasting).
        assertEquals(0, app.resources.getIdentifier("widget_paste", "id", app.packageName))
    }

    @Test fun resizingUsesCurrentStateAndRendersNarrowLayout() {
        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(NezzelWidgetProvider::class.java, R.layout.widget_nezzel)
        NezzelWidgetProvider().onAppWidgetOptionsChanged(app, manager, id,
            Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110) })
        assertEquals(View.GONE, shadow.getViewFor(id).findViewById<View>(R.id.widget_title).visibility)
        assertEquals("No downloads right now", status(shadow.getViewFor(id)))
    }

    @Test @Config(sdk = [29, 33]) fun choosingLanguageRendersExistingWidgetsWithoutProgressChanges() {
        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(NezzelWidgetProvider::class.java, R.layout.widget_nezzel)
        dev.ahmed.savlino.locale.AppLocale.choose(app, "fr")
        assertEquals("Aucun téléchargement en cours", status(shadow.getViewFor(id)))
        dev.ahmed.savlino.locale.AppLocale.choose(app, "ar")
        assertEquals("لا تنزيلات الآن", status(shadow.getViewFor(id)))
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

    @Test fun updaterReturnsToIdleOnceTheJobLeavesOrFinishes() = runTest {
        val progress = MutableStateFlow(listOf(job()))
        val updater = WidgetUpdater(app, progress, widgetIds = { intArrayOf() })
        val collection = updater.start(backgroundScope)
        try {
            runCurrent()
            assertEquals("clip", updater.currentState.title)
            progress.value = listOf(job(DownloadPhase.FAILED)); runCurrent()
            assertEquals(WidgetUiState(), updater.currentState)
            progress.value = listOf(job(bytes = 70)); runCurrent()
            assertEquals(70, updater.currentState.percent)
            progress.value = emptyList(); runCurrent()
            assertEquals(WidgetUiState(), updater.currentState)
        } finally { collection.cancel() }
    }
}
