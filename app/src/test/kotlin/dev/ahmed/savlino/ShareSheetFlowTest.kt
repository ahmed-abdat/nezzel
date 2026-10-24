package dev.ahmed.savlino

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.view.View
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import dev.ahmed.savlino.ui.OptionsState
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
import androidx.compose.ui.platform.compositionContext
import java.time.Duration

/** Share opens the options over the sending app; Download hands the job to the app's coordinator and the sheet closes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareSheetFlowTest {
    private val url = "https://youtu.be/one"
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val prefs get() = AppPreferences(app)
    private var store: JobStore? = null
    private var controller: ActivityController<ShareSheetActivity>? = null

    private class Engine(val failPrepare: Boolean = false) : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        override val isAvailable = true
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "2026.08.19", listOf(Format("720", "mp4", true, true, height = 720)),
                listOf(PresetChoice(Preset.BALANCED, "720")), title = "Real title"))
        }
        override fun prepareDownload(command: DownloadCommand) { if (failPrepare) error("no space") }
    }

    private fun install(engine: Engine, automatic: Boolean = true, granted: Boolean = true,
        savedFileExists: (Uri) -> Boolean = { true }): JobStore {
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.automaticOptions = automatic
        val jobs = JobStore(app).also { store = it }
        app.coordinatorOverride = DownloadCoordinator(app, engine, jobs, workManager = { error("No background scheduler in UI fixture") },
            savedFileExists = savedFileExists)
        return jobs
    }

    private fun share(text: String, subject: String? = null) = Intent(app, ShareSheetActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }

    // Robolectric's shared window recomposer stops getting frames after the first test: drive our own.
    private val clock = androidx.compose.runtime.BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
    private val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext).also { r ->
        scope.launch { r.runRecomposeAndApplyChanges() }
    }

    private fun launch(intent: Intent): ShareSheetActivity {
        val c = Robolectric.buildActivity(ShareSheetActivity::class.java, intent).also { controller = it }
        c.create().start().postCreate(null).resume()
        c.get().window.decorView.compositionContext = recomposer
        return c.visible().get()
    }

    private var frameNanos = 0L
    /** Frame time advances with the looper clock, so the sheet's slide-in animation completes. */
    private fun frame(activity: ShareSheetActivity, idleMillis: Long = 50) {
        frameNanos += idleMillis * 1_000_000
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); clock.sendFrame(frameNanos)
        semanticsNodes(activity, rounds = 1, idleMillis = idleMillis)
    }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        try { withTimeout(5000) { while (!check()) {
            controller?.get()?.let { frame(it) } ?: shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            delay(5)
        } } }
        catch (_: TimeoutCancellationException) { fail("Timed out waiting for $what; " + controller?.get()?.let { a -> "accepted=${a.links.acceptedJob} state=${a.links.optionsState::class.simpleName} more=${a.links.moreFormats} texts=${texts(semanticsNodes(a))}" }) }
    }

    private fun sheetTexts(activity: ShareSheetActivity): List<String> {
        repeat(60) { frame(activity, idleMillis = 16) }
        return texts(semanticsNodes(activity, rounds = 1))
    }

    @After fun tearDown() {
        runBlocking { withTimeout(5000) {
            while (app.scope.coroutineContext[Job]!!.children.any()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
        } }
        controller?.let { c -> if (!c.get().isDestroyed) c.pause().stop().destroy() }
        store?.close(); app.coordinatorOverride = null
        recomposer.cancel(); scope.cancel()
    }

    @Test fun downloadEnqueuesInTheAppCoordinatorMarksTheLinkHandledAndCloses() {
        val engine = Engine(); val jobs = install(engine)
        val activity = launch(share("Great clip $url"))
        assertEquals(url, activity.links.text)
        assertEquals("Great clip", activity.links.shareHint)
        waitFor("options") { activity.links.downloadEnabled }
        assertEquals(listOf(url), engine.inspected)
        assertTrue(jobs.all().isEmpty()); assertTrue(prefs.dismissedHashes.isEmpty())
        assertFalse(activity.isFinishing)

        activity.links.requestDownload()
        waitFor("job accepted") { activity.links.acceptedJob != null }
        assertEquals(jobs.all().single().jobId, activity.links.acceptedJob)
        assertEquals(JobStage.ENQUEUED, jobs.all().single().stage)
        assertEquals(OptionsState.Hidden, activity.links.optionsState)
        val started = sheetTexts(activity)
        assertTrue(started.contains(activity.getString(R.string.share_sheet_started)))
        assertTrue(started.contains(activity.getString(R.string.nezzel_close)))
        assertFalse("the check stays about 1.5 s", activity.isFinishing)
        waitFor("sheet closes after the check") { activity.isFinishing }
        waitFor("link marked handled") { prefs.dismissedHashes.isNotEmpty() }
    }

    @Test fun withAccessibilityOnTheStartedSheetStaysUntilClosed() {
        val engine = Engine(); install(engine)
        shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).setEnabled(true)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        activity.links.requestDownload()
        waitFor("job accepted") { activity.links.acceptedJob != null }
        repeat(4) { sheetTexts(activity) }
        assertFalse("no auto-close while an accessibility service is on", activity.isFinishing)
        val close = semanticsNodes(activity, rounds = 1).last { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
            activity.getString(R.string.nezzel_close) in texts(descendants(it)) }
        assertEquals(true, close.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke())
        waitFor("closed by Close") { activity.isFinishing }
    }

    @Test fun dismissingTheSheetEnqueuesNothingAndMarksNothing() {
        val engine = Engine(); val jobs = install(engine)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        controller!!.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(jobs.all().isEmpty())
        assertTrue(prefs.dismissedHashes.isEmpty())
    }

    @Test fun automaticOffTouchesNoNetworkUntilViewOptions() {
        val engine = Engine(); install(engine, automatic = false)
        val activity = launch(share("Great clip $url"))
        val shown = sheetTexts(activity)
        assertTrue(shown.toString(), activity.getString(R.string.check_link) in shown)
        assertTrue(engine.inspected.isEmpty())
        assertEquals(OptionsState.Hidden, activity.links.optionsState)
        activity.links.checkLink()
        waitFor("options") { activity.links.downloadEnabled }
        assertEquals(listOf(url), engine.inspected)
    }

    /** Sheets lay out only at sdk 29 in the JVM. */
    @Test @Config(sdk = [29]) fun previewKeepsOpenInNezzelBelowViewOptions() {
        install(Engine(), automatic = false)
        val activity = launch(share("Great clip $url"))
        sheetTexts(activity)
        fun bounds(label: String) = semanticsNodes(activity, rounds = 1).first { label in texts(listOf(it)) }.boundsInWindow
        val view = bounds(activity.getString(R.string.check_link))
        val open = bounds(activity.getString(R.string.share_sheet_open_app))
        assertTrue("$view", view.height > 0 && open.height > 0)
        assertTrue("$open below $view", open.top >= view.bottom)
    }

    @Test fun sameLinkSharedAgainKeepsTheOptionsAndANewLinkReplacesThem() {
        val engine = Engine(); install(engine)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        val options = activity.links.optionsState
        controller!!.newIntent(share("Look again $url"))
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(options, activity.links.optionsState)
        assertEquals(1, engine.inspected.size)
        controller!!.newIntent(share("https://youtu.be/two"))
        waitFor("new options") { engine.inspected.size == 2 && activity.links.downloadEnabled }
        assertEquals("https://youtu.be/two", activity.links.text)
        assertFalse(activity.links.hasPendingShare)
    }

    private fun assertHandsOff(activity: ShareSheetActivity, text: String) {
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals(Intent.ACTION_SEND, next.action)
        assertEquals(text, next.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    @Test fun firstRunGoesToTheFullApp() {
        val engine = Engine(); install(engine)
        app.getSharedPreferences("nezzel", 0).edit().remove("onboarding_seen").commit()
        val activity = launch(share(url))
        assertHandsOff(activity, url)
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun severalLinksGoToTheFullApp() {
        val engine = Engine(); install(engine)
        val text = "$url and https://youtu.be/two"
        assertHandsOff(launch(share(text)), text)
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun allQualitiesGoesToTheFullAppWithTheSameText() {
        val engine = Engine(); install(engine)
        val text = "Great clip $url"
        val activity = launch(share(text))
        waitFor("options") { activity.links.downloadEnabled }
        activity.links.showMoreFormats()
        waitFor("hand-off") { activity.isFinishing }
        assertHandsOff(activity, text)
    }

    @Test fun savedBeforeShowsFirstAndDownloadAgainStillEnqueues() {
        val engine = Engine(); val jobs = install(engine)
        val source = LinkRevision(LinkInput.parse(url)!!, 1)
        val command = (DownloadCommand.confirm("done-720", Inspection(source, "2026.08.19",
            listOf(Format("720", "mp4", true, true, height = 720))), source, "720", "clip") as ConfirmResult.Confirmed).command
        val uri = Uri.parse("content://media/external/downloads/7")
        assertTrue(jobs.enqueue(command)); assertTrue(jobs.startTransfer("done-720")); assertTrue(jobs.markStaged("done-720"))
        assertTrue(jobs.recordPending("done-720", PendingPublication(uri, "clip.mp4")))
        assertTrue(jobs.complete("done-720", PublishedFile(uri, "clip.mp4")))

        val activity = launch(share(url))
        waitFor("saved lookup") { activity.links.savedLink != null && activity.links.downloadEnabled }
        waitFor("download again shown") { sheetTexts(activity).any { it.startsWith(activity.getString(R.string.download_again)) } }
        activity.links.requestDownload()
        waitFor("second job") { jobs.all().size == 2 }
        waitFor("sheet closes") { activity.isFinishing }
    }

    @Test fun failedEnqueueKeepsTheSheetOpen() {
        val engine = Engine(failPrepare = true); install(engine)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        activity.links.requestDownload()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(activity.links.acceptedJob)
        assertFalse(activity.isFinishing)
    }

    @Test fun notificationLineShowsOnceOnAndroid13AndAskingStillEnqueues() {
        val engine = Engine(); val jobs = install(engine, granted = false)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        assertTrue(activity.getString(R.string.share_notify_line) in sheetTexts(activity))
        activity.links.requestDownload()
        val permission = shadowOf(activity).lastRequestedPermission!!
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), permission.requestedPermissions)
        assertTrue(app.getSharedPreferences("MainActivity", 0).getBoolean("notificationAsked", false))
        activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED))
        waitFor("job after denial") { jobs.all().size == 1 }
    }

    @Test @Config(sdk = [29]) fun noNotificationLineBeforeAndroid13() {
        val engine = Engine(); install(engine, granted = false)
        val activity = launch(share(url))
        waitFor("options") { activity.links.downloadEnabled }
        assertFalse(activity.getString(R.string.share_notify_line) in sheetTexts(activity))
    }

    @Test @Config(qualifiers = "ar") fun arabicSheetIsRightToLeftWithArabicText() {
        val engine = Engine(); install(engine, automatic = false)
        val activity = launch(share(url))
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.resources.configuration.layoutDirection)
        val shown = sheetTexts(activity)
        val viewOptions = activity.getString(R.string.check_link)
        assertTrue(viewOptions, viewOptions.any { it in '؀'..'ۿ' })
        assertTrue(shown.toString(), viewOptions in shown)
    }
}
