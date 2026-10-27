package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.EngineUpdateStatus
import dev.ahmed.savlino.jobs.JobStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** X/Instagram/Telegram/Bluesky/Reddit read with yt-dlp beside the reader; the reader's verdict decides which one is shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LinkViewModelRaceTest {
    private val instagram = "https://www.instagram.com/p/SynthPost01/?igsh=Synthetic"
    private val canonicalPost = "https://www.instagram.com/p/SynthPost01/"
    private val reddit = "https://www.reddit.com/r/synthetic/comments/1abcdef/synthetic_photo/"
    private val handoffLinks = listOf(instagram, "https://x.com/someone/status/1790000000000000001?s=20",
        "https://t.me/synthetic_channel/123", "https://bsky.app/profile/synthetic.test/post/3synthetic001", reddit)
    private lateinit var store: JobStore
    private lateinit var coordinator: DownloadCoordinator
    private lateinit var vm: LinkViewModel

    private class RaceEngine : EngineAdapter by FakeEngine() {
        val inspected = java.util.Collections.synchronizedList(mutableListOf<String>())
        val cancelled = java.util.Collections.synchronizedList(mutableListOf<String>())
        var inspectHold: CompletableDeferred<Unit>? = null
        var updateHold: CompletableDeferred<Unit>? = null
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            inspectHold?.await()
            return InspectResult.Success(Inspection(source, "2026.08.19", listOf(Format("http", "mp4", true, true)),
                listOf(PresetChoice(Preset.BEST, "http")), title = "Post"))
        }
        override fun cancel(operationId: String) { cancelled += operationId }
        override suspend fun updateYtdlp(): EngineUpdateResult {
            updateHold?.await()
            return EngineUpdateResult.Success("2026.08.19", "2026.08.19")
        }
    }
    private val engine = RaceEngine()
    private val images = FakeImages()
    private var nextId = 0

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        store = JobStore(app)
        // The first SQLite open loads Robolectric's native runtime (seconds when cold); keep it out of timed waits.
        store.all()
        coordinator = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in this fixture") })
        vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, images = images,
            newOperationId = { "op-${++nextId}" }, previewDispatcher = Dispatchers.Unconfined, previewImage = ::testBitmap,
            currentEngineVersion = { "2026.08.19" })
    }
    @After fun tearDown() {
        images.gate?.complete(Unit); engine.inspectHold?.complete(Unit); engine.updateHold?.complete(Unit)
        vm.dismissOptions(); pump(); store.close()
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle(done: () -> Boolean) = runBlocking { withTimeout(5000) { while (!done()) { pump(); delay(5) } }; pump() }
    private fun check(link: String) { vm.edit(link); vm.checkLink(); pump() }
    private fun reset() { engine.inspected.clear(); engine.cancelled.clear(); images.routes.clear(); images.cancelled.clear() }
    /** The reader is still working: hold it until [answer] is released. */
    private fun holdReader(answer: (LinkRevision) -> MediaInspectResult) {
        images.gate = CompletableDeferred(); images.result = answer
    }

    @Test fun aVideoVerdictShowsTheSpeculativeReadAndNeverInspectsTwice() {
        for (link in handoffLinks) {
            reset()
            holdReader { MediaInspectResult.VideoRedirect(it) }
            check(link)
            assertEquals(link, 1, engine.inspected.size) // yt-dlp already reading while the reader works
            assertTrue(vm.optionsState is OptionsState.Checking)
            images.gate!!.complete(Unit)
            settle { vm.optionsState !is OptionsState.Checking }
            val ready = vm.optionsState as? OptionsState.Ready
            assertNotNull(vm.optionsState.toString(), ready)
            assertEquals(listOf("http"), vm.formatOptions.map { it.id })
            assertEquals(link, ready!!.resolvedFrom!!.link.rawUrl)
            assertEquals(link, 1, engine.inspected.size)
            assertEquals(emptyList<String>(), engine.cancelled)
        }
    }

    @Test fun aPageFailureBeforeTheVerdictAwaitsTheSpeculativeReadToo() {
        holdReader { MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE) }
        engine.inspectHold = CompletableDeferred()
        check(instagram)
        images.gate!!.complete(Unit); pump()
        assertTrue("still reading with yt-dlp", vm.optionsState is OptionsState.Checking)
        engine.inspectHold!!.complete(Unit)
        settle { vm.optionsState !is OptionsState.Checking }
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Ready)
        assertEquals(listOf(canonicalPost), engine.inspected)
    }

    @Test fun aPhotoVerdictCancelsTheSpeculativeReadAndItsLateResultIsDropped() {
        holdReader { imageSuccess(it, found = 2) }
        engine.inspectHold = CompletableDeferred()
        check(instagram)
        assertEquals(listOf(canonicalPost), engine.inspected)
        images.gate!!.complete(Unit); pump()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.ImageReady)
        assertEquals(listOf("op-2"), engine.cancelled)
        engine.inspectHold!!.complete(Unit); pump()
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        assertEquals(1, engine.inspected.size)
    }

    @Test fun aTerminalReaderFailureCancelsTheSpeculativeRead() {
        holdReader { MediaInspectResult.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE) }
        engine.inspectHold = CompletableDeferred()
        check(instagram)
        images.gate!!.complete(Unit); pump()
        assertEquals(OptionsState.ImageError(ImageFailure.PRIVATE, phase = ImagePhase.PAGE), vm.optionsState)
        assertEquals(listOf("op-2"), engine.cancelled)
        engine.inspectHold!!.complete(Unit); pump()
        assertEquals(OptionsState.ImageError(ImageFailure.PRIVATE, phase = ImagePhase.PAGE), vm.optionsState)
    }

    @Test fun anEditedLinkDropsBothReads() {
        holdReader { MediaInspectResult.VideoRedirect(it) }
        engine.inspectHold = CompletableDeferred()
        check(instagram)
        vm.edit("https://www.instagram.com/p/SynthPost02/"); pump()
        assertEquals(listOf("op-1"), images.cancelled)
        assertEquals(listOf("op-2"), engine.cancelled)
        images.gate!!.complete(Unit); engine.inspectHold!!.complete(Unit); pump()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertEquals(1, engine.inspected.size)
    }

    @Test fun aRedditPostRacesYtDlpWithTheUsersOwnLink() {
        // Photos win: the yt-dlp read is cancelled and its late result dropped.
        holdReader { imageSuccess(it, found = 2) }
        engine.inspectHold = CompletableDeferred()
        check(reddit)
        assertEquals(listOf(MediaSourceRoute.REDDIT), images.routes)
        assertEquals(listOf(reddit), engine.inspected)
        images.gate!!.complete(Unit); pump()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.ImageReady)
        assertEquals(listOf("op-2"), engine.cancelled)
        engine.inspectHold!!.complete(Unit); pump()
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        vm.dismissOptions(); pump()

        // A text post or a failed page hands off: the yt-dlp read of the same link is shown, never cancelled.
        reset()
        holdReader { MediaInspectResult.VideoRedirect(it) }
        check(reddit)
        images.gate!!.complete(Unit)
        settle { vm.optionsState !is OptionsState.Checking }
        assertEquals(reddit, (vm.optionsState as OptionsState.Ready).resolvedFrom!!.link.rawUrl)
        assertEquals(listOf(reddit), engine.inspected)
        assertEquals(emptyList<String>(), engine.cancelled)
        vm.dismissOptions(); pump()

        // A replacement link drops both reads.
        reset()
        holdReader { MediaInspectResult.VideoRedirect(it) }
        engine.inspectHold = CompletableDeferred()
        check(reddit)
        vm.edit("https://www.reddit.com/r/synthetic/comments/2bcdefg/another/"); pump()
        assertEquals(1, images.cancelled.size)
        assertEquals(1, engine.cancelled.size)
        images.gate!!.complete(Unit); engine.inspectHold!!.complete(Unit); pump()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertEquals(listOf(reddit), engine.inspected)
    }

    @Test fun facebookAndLinkedInNeverRace() {
        for (link in listOf("https://www.facebook.com/somepage/posts/900000000000001",
                "https://www.linkedin.com/posts/someone_title-ugcPost-7380000000000000002-AbCd/")) {
            holdReader { MediaInspectResult.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE) }
            check(link)
            assertEquals(link, emptyList<String>(), engine.inspected)
            images.gate!!.complete(Unit); pump()
            assertEquals(link, emptyList<String>(), engine.inspected)
            assertEquals(link, emptyList<String>(), engine.cancelled)
        }
    }

    @Test fun aPhotoWinOrDismissWhileMaintenanceHoldsAdmissionLeavesNoLaterEngineRead() {
        for (dismiss in listOf(false, true)) {
            reset()
            engine.updateHold = CompletableDeferred()
            val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
            runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
            holdReader { imageSuccess(it) }
            check(instagram)
            assertEquals(emptyList<String>(), engine.inspected) // speculative read waits for admission
            if (dismiss) vm.dismissOptions() else { images.gate!!.complete(Unit); pump() }
            assertTrue(vm.optionsState.toString(), if (dismiss) vm.optionsState == OptionsState.Hidden
                else vm.optionsState is OptionsState.ImageReady)
            images.gate!!.complete(Unit)
            engine.updateHold!!.complete(Unit)
            settle { !updating.isActive }
            runBlocking { delay(50) }; pump()
            assertEquals("dismiss=$dismiss", emptyList<String>(), engine.inspected)
        }
    }

    @Test fun aVideoVerdictWhileMaintenanceHoldsAdmissionWaitsThenReadsOnce() {
        engine.updateHold = CompletableDeferred()
        val updating = CoroutineScope(Dispatchers.IO).async { coordinator.maintenance.updateNow() }
        runBlocking { withTimeout(2000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } } }
        holdReader { MediaInspectResult.VideoRedirect(it) }
        check(instagram)
        images.gate!!.complete(Unit); pump()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Checking)
        assertTrue(vm.engineWaiting)
        assertEquals(emptyList<String>(), engine.inspected)
        engine.updateHold!!.complete(Unit)
        settle { !updating.isActive && vm.optionsState !is OptionsState.Checking }
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Ready)
        assertEquals(listOf(canonicalPost), engine.inspected)
    }
}
