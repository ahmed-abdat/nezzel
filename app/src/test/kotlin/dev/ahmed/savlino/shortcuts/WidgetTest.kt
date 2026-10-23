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
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.jobs.command
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
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
        val old = job(DownloadPhase.FAILED).copy(jobId = "old", filename = "old.mp4")
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
        val old = job(DownloadPhase.FAILED).copy(jobId = "old", filename = "old.mp4")
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

    @Test fun activeSnapshotReplacesCompletionWithoutANewAdmission() = runTest {
        val old = job().copy(jobId = "old", filename = "old.mp4")
        val progress = MutableStateFlow(listOf(old, job()))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf(1) }, render = { _, _ -> })
        val collection = updater.start(backgroundScope)
        runCurrent()
        completed.emit(old.copy(phase = DownloadPhase.COMPLETED)); runCurrent()
        progress.value = listOf(job(bytes = 60)); runCurrent()
        assertEquals("clip.mp4", updater.currentState.title)
        assertEquals(DownloadPhase.DOWNLOADING, updater.currentState.phase)
        assertEquals(60, updater.currentState.percent)
        progress.value = listOf(job(bytes = 70)); runCurrent()
        assertEquals("clip.mp4", updater.currentState.title)
        assertEquals(70, updater.currentState.percent)
        progress.value = listOf(job(), job().copy(jobId = "next", filename = "next.mp4")); runCurrent()
        assertEquals("next.mp4", updater.currentState.title)
        assertEquals(DownloadPhase.DOWNLOADING, updater.currentState.phase)
        collection.cancel()
    }

    @Test fun retryOrResumeOfAnExistingJobReplacesTheRememberedCompletion() = runTest {
        for (paused in listOf(false, true)) {
            val a = job(if (paused) DownloadPhase.DOWNLOADING else DownloadPhase.FAILED)
                .copy(jobId = "a", filename = "a.mp4", paused = paused)
            val b = job().copy(jobId = "b", filename = "b.mp4")
            val progress = MutableStateFlow(listOf(a, b))
            val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
            val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf() })
            val collection = updater.start(backgroundScope)
            try {
                runCurrent()
                completed.emit(b.copy(phase = DownloadPhase.COMPLETED)); runCurrent()
                progress.value = listOf(a); runCurrent()
                // A retry/resume changes phase, not identity, and can arrive without a new admission.
                progress.value = listOf(a.copy(phase = DownloadPhase.PREPARING, paused = false, waiting = true)); runCurrent()
                assertEquals("a.mp4", updater.currentState.title)
                assertEquals(DownloadPhase.PREPARING, updater.currentState.phase)
                assertTrue(updater.currentState.waiting)
            } finally { collection.cancel() }
        }
    }

    @Test fun retriedOlderJobWinsOverANewerFailureInTheSnapshot() = runTest {
        val a = job(DownloadPhase.FAILED).copy(jobId = "a", filename = "a.mp4")
        val newerFailure = job(DownloadPhase.FAILED).copy(jobId = "failed", filename = "failed.mp4")
        val b = job().copy(jobId = "b", filename = "b.mp4")
        val progress = MutableStateFlow(listOf(a, newerFailure, b))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf() })
        val collection = updater.start(backgroundScope)
        try {
            runCurrent()
            completed.emit(b.copy(phase = DownloadPhase.COMPLETED)); runCurrent()
            progress.value = listOf(a, newerFailure); runCurrent()
            assertEquals("b.mp4", updater.currentState.title)
            progress.value = listOf(a.copy(phase = DownloadPhase.PREPARING), newerFailure); runCurrent()
            assertEquals("a.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.PREPARING, updater.currentState.phase)
        } finally { collection.cancel() }
    }

    @Test fun freshProcessShowsTheNewestJournalUpdateIncludingCompletion() = runTest {
        val uri = android.net.Uri.parse("content://media/external_primary/downloads/7")
        JobStore(app).use { store ->
            // Insert completion first: the journal's admission order must not decide freshness.
            assertTrue(store.enqueue(command("saved")))
            assertTrue(store.startTransfer("saved"))
            assertTrue(store.markStaged("saved"))
            assertTrue(store.recordPending("saved", PendingPublication(uri, "saved.mp4")))
            assertTrue(store.complete("saved", PublishedFile(uri, "saved.mp4")))
            assertTrue(store.enqueue(command("old-failure")))
            assertTrue(store.fail("old-failure", DownloadError.Network))
            store.writableDatabase.execSQL("UPDATE jobs SET updated_at = ? WHERE job_id = ?", arrayOf<Any>(1000L, "old-failure"))
            store.writableDatabase.execSQL("UPDATE jobs SET updated_at = ? WHERE job_id = ?", arrayOf<Any>(2000L, "saved"))
        }
        val progress = MutableStateFlow(emptyList<DownloadProgress>())
        val updater = WidgetUpdater(app, progress, journal = { JobStore(app).use { it.all() } }, widgetIds = { intArrayOf() })
        val collection = updater.start(backgroundScope)
        try {
            runCurrent()
            assertEquals("saved.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
            assertEquals("Saved", status(view(updater.currentState)))
            // Recovery publishes failures but omits completed rows; this must retain the seed.
            progress.value = listOf(job(DownloadPhase.FAILED).copy(jobId = "old-failure", filename = "old.mp4")); runCurrent()
            assertEquals("saved.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
            progress.value = listOf(job(DownloadPhase.PREPARING).copy(jobId = "old-failure", filename = "old.mp4")); runCurrent()
            assertEquals("old.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.PREPARING, updater.currentState.phase)
        } finally { collection.cancel() }
    }

    @Test fun newerJournalFailureWinsOverAnOlderCompletion() = runTest {
        val uri = android.net.Uri.parse("content://media/external_primary/downloads/7")
        JobStore(app).use { store ->
            assertTrue(store.enqueue(command("new-failure")))
            assertTrue(store.fail("new-failure", DownloadError.Network))
            assertTrue(store.enqueue(command("old-saved")))
            assertTrue(store.startTransfer("old-saved"))
            assertTrue(store.markStaged("old-saved"))
            assertTrue(store.recordPending("old-saved", PendingPublication(uri, "old.mp4")))
            assertTrue(store.complete("old-saved", PublishedFile(uri, "old.mp4")))
            store.writableDatabase.execSQL("UPDATE jobs SET updated_at = ? WHERE job_id = ?", arrayOf<Any>(2000L, "new-failure"))
            store.writableDatabase.execSQL("UPDATE jobs SET updated_at = ? WHERE job_id = ?", arrayOf<Any>(1000L, "old-saved"))
        }
        val progress = MutableStateFlow(emptyList<DownloadProgress>())
        val updater = WidgetUpdater(app, progress, journal = { JobStore(app).use { it.all() } }, widgetIds = { intArrayOf() })
        val collection = updater.start(backgroundScope)
        try {
            runCurrent()
            assertEquals("clip.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.FAILED, updater.currentState.phase)
            progress.value = listOf(job(DownloadPhase.FAILED).copy(jobId = "new-failure")); runCurrent()
            JobStore(app).use { assertTrue(it.remove("new-failure")) }
            progress.value = emptyList(); runCurrent()
            assertNull(updater.currentState.title)
        } finally { collection.cancel() }
    }

    @Test fun forgottenSavedFileLeavesTheWidgetOnlyWhenItIsTheShownCompletion() = runTest {
        val old = job(DownloadPhase.FAILED).copy(jobId = "old", filename = "old.mp4")
        val progress = MutableStateFlow(listOf(old, job()))
        val completed = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 1)
        val updater = WidgetUpdater(app, progress, completed, widgetIds = { intArrayOf(1) }, render = { _, _ -> })
        val collection = updater.start(backgroundScope)
        try {
            runCurrent()
            completed.emit(job(DownloadPhase.COMPLETED)); progress.value = listOf(old); runCurrent()
            updater.forget("old"); runCurrent()
            assertEquals(DownloadPhase.COMPLETED, updater.currentState.phase)
            updater.forget("new"); runCurrent()
            assertEquals("old.mp4", updater.currentState.title)
            assertEquals(DownloadPhase.FAILED, updater.currentState.phase)
        } finally { collection.cancel() }
    }
}
