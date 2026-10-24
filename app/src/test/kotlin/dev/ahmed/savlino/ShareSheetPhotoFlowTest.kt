package dev.ahmed.savlino

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import dev.ahmed.savlino.domain.MediaMetadata
import dev.ahmed.savlino.engine.UnavailableEngine
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.media.ImageSaver
import dev.ahmed.savlino.ui.FakeFetch
import dev.ahmed.savlino.ui.FakeImages
import dev.ahmed.savlino.ui.OptionsState
import dev.ahmed.savlino.ui.RecordingPublisher
import dev.ahmed.savlino.ui.imageSuccess
import dev.ahmed.savlino.ui.noSpaceCheck
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A shared photo post in the sheet: the grid, Select all, a save no way out can interrupt, then a toast and close. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShareSheetPhotoFlowTest {
    private val url = "https://www.linkedin.com/posts/someone_title-activity-7380000000000000000-AbCd"
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetch = FakeFetch(gate = CompletableDeferred())
    private val publisher = RecordingPublisher()
    private var store: JobStore? = null
    private var controller: ActivityController<ShareSheetActivity>? = null

    private fun install(found: Int) {
        val jobs = JobStore(app).also { store = it }
        app.coordinatorOverride = DownloadCoordinator(app, UnavailableEngine(), jobs,
            workManager = { error("No background scheduler in UI fixture") })
        app.imageInspectOverride = FakeImages { imageSuccess(it, found = found, metadata = MediaMetadata("Carousel")) }
        app.imageSaverOverride = ImageSaver(fetch, publisher,
            File(app.noBackupFilesDir, "image-save").apply { deleteRecursively() }, noSpaceCheck, io)
    }

    // Robolectric's shared window recomposer stops getting frames after the first test: drive our own.
    private val clock = androidx.compose.runtime.BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
    private val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext).also { r ->
        scope.launch { r.runRecomposeAndApplyChanges() }
    }

    private fun launch(): ShareSheetActivity {
        val intent = Intent(app, ShareSheetActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        val c = Robolectric.buildActivity(ShareSheetActivity::class.java, intent).also { controller = it }
        c.create().start().postCreate(null).resume()
        c.get().window.decorView.compositionContext = recomposer
        return c.visible().get()
    }

    private var frameNanos = 0L
    private fun frame(activity: ShareSheetActivity, idleMillis: Long = 16) {
        frameNanos += idleMillis * 1_000_000
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); clock.sendFrame(frameNanos)
        semanticsNodes(activity, rounds = 1, idleMillis = idleMillis)
    }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        try { withTimeout(5000) { while (!check()) { controller?.get()?.let { frame(it, 50) }; delay(5) } } }
        catch (_: TimeoutCancellationException) { fail("Timed out waiting for $what; " + controller?.get()?.let { a ->
            "state=${a.links.optionsState::class.simpleName} saving=${a.links.imageSaving} texts=${texts(semanticsNodes(a))}" }) }
    }

    private fun nodes(activity: ShareSheetActivity): List<SemanticsNode> {
        repeat(60) { frame(activity) }
        return semanticsNodes(activity, rounds = 1)
    }

    /** The innermost clickable carrying [label] (OnClick sits on the button, the text on its child). */
    private fun clickable(activity: ShareSheetActivity, label: String): SemanticsNode {
        val all = nodes(activity)
        return all.filter { SemanticsActions.OnClick in it.config && label in texts(descendants(it)) }
            .minByOrNull { descendants(it).size } ?: error("no clickable '$label' in ${texts(all)}")
    }

    @After fun tearDown() {
        fetch.gate?.complete(Unit)
        runBlocking { withTimeout(5000) {
            while (app.scope.coroutineContext[Job]!!.children.any()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
        } }
        controller?.let { c -> if (!c.get().isDestroyed) c.pause().stop().destroy() }
        store?.close()
        app.coordinatorOverride = null; app.imageInspectOverride = null; app.imageSaverOverride = null
        recomposer.cancel(); scope.cancel(); io.cancel()
    }

    @Test fun carouselSelectAllThenASaveNoWayOutInterruptsThenToastAndClose() {
        install(found = 3)
        val activity = launch()
        waitFor("photo grid") { activity.links.optionsState is OptionsState.ImageReady }
        assertEquals(3, (activity.links.optionsState as OptionsState.ImageReady).photos.size)
        assertEquals("all photos start selected", 3, activity.links.selectedImageRefs.size)

        // Select all toggles: everything off, then everything back on.
        val none = activity.getString(R.string.image_select_none)
        assertEquals(true, clickable(activity, none).config[SemanticsActions.OnClick].action?.invoke())
        waitFor("none selected") { activity.links.selectedImageRefs.isEmpty() }
        assertFalse(activity.links.imageSaveEnabled)
        assertEquals(true, clickable(activity, activity.getString(R.string.image_select_all)).config[SemanticsActions.OnClick].action?.invoke())
        waitFor("all selected again") { activity.links.selectedImageRefs.size == 3 }

        activity.links.saveImage()
        waitFor("saving") { activity.links.imageSaving }

        // Open in Nezzel is disabled mid-save, and its handler refuses too.
        val open = clickable(activity, activity.getString(R.string.share_sheet_open_app))
        assertTrue("Open in Nezzel disabled while saving", SemanticsProperties.Disabled in open.config)
        open.config[SemanticsActions.OnClick].action?.invoke()
        // Back is swallowed while saving (the sheet's BackHandler and the dismiss lock).
        activity.onBackPressedDispatcher.onBackPressed()
        repeat(3) { nodes(activity) }
        assertNull("no hand-off mid-save", shadowOf(activity).nextStartedActivity)
        assertFalse("still open mid-save", activity.isFinishing)
        assertTrue(activity.links.imageSaving)

        fetch.gate!!.complete(Unit)
        runBlocking { withTimeout(10_000) { app.imageSaver.awaitIdle() } }
        val saved = activity.resources.getQuantityString(R.plurals.image_saved_count, 3,
            dev.ahmed.savlino.locale.NezzelFormat.count(3, "en"))
        waitFor("toast") { ShadowToast.getTextOfLatestToast() == saved }
        assertEquals(3, publisher.published.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        waitFor("sheet closes after the toast") { activity.isFinishing }
    }
}
