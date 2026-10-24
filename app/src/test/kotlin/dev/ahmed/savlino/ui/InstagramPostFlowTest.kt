package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Instagram photos use the reader; video handoffs use canonical links while keeping the intake revision. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InstagramPostFlowTest {
    private val link = "https://www.instagram.com/p/SynthPost01/?igsh=Synthetic"
    private val canonicalPost = "https://www.instagram.com/p/SynthPost01/"
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel

    private inner class RecordingEngine : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "2026.08.19", listOf(Format("http", "mp4", true, true)),
                listOf(PresetChoice(Preset.BEST, "http")), title = "Post"))
        }
    }
    private val engine = RecordingEngine()
    private val images = FakeImages()

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in this fixture") })
        vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, images = images,
            previewDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, previewImage = ::testBitmap, currentEngineVersion = { "2026.08.19" })
    }
    @After fun tearDown() { vm.dismissOptions(); store.close() }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun check() { vm.edit(link); vm.checkLink(); pump() }

    @Test fun photoPostShowsTheImageWithoutYtDlp() {
        images.result = { imageSuccess(it, found = 4) }
        check()
        assertEquals(listOf(MediaSourceRoute.INSTAGRAM), images.routes)
        val ready = vm.optionsState as? OptionsState.ImageReady
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(4, ready!!.discovery.discoveredCount)
        assertFalse(ready.directImage)
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun videoPostReachesYtDlpAndDownloadWithTheCanonicalLink() {
        images.result = { MediaInspectResult.VideoRedirect(it) }
        check()
        assertEquals(listOf(canonicalPost), engine.inspected)
        val ready = vm.optionsState as? OptionsState.Ready
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(canonicalPost, ready!!.inspection.source.link.rawUrl)
        assertEquals(listOf("http"), vm.formatOptions.map { it.id })
        vm.selectFormat("http")
        vm.confirmDownload()
        val row = runBlocking { withTimeout(5000) { while (store.all().isEmpty()) { pump(); Thread.sleep(5) } }; pump(); store.all().single() }
        assertEquals(canonicalPost, row.sourceUrl)
    }

    @Test fun aRedirectToAnyOtherLinkOrRevisionIsRefused() {
        for (other in listOf("https://www.instagram.com/p/SynthPost01/", "https://www.instagram.com/p/SynthPost02/")) {
            images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(other)!!, it.revision)) }
            check()
            assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(it.link, it.revision + 1)) }
        check()
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun notPublicAndProvenPhotoFailuresAreNeverAskedOfYtDlp() {
        for (failure in listOf(ImageFailure.NO_MEDIA, ImageFailure.PRIVATE)) {
            images.result = { MediaInspectResult.Failure(failure, ImagePhase.PAGE) }
            check()
            assertEquals(OptionsState.ImageError(failure, phase = ImagePhase.PAGE), vm.optionsState)
        }
        images.result = { MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 404) }
        check()
        assertEquals(OptionsState.ImageError(ImageFailure.HTTP, 404, phase = ImagePhase.PAGE), vm.optionsState)
        images.result = { MediaInspectResult.Failure(ImageFailure.BUDGET, ImagePhase.IMAGE) }
        check()
        assertEquals("a photo already proven stays an image failure", OptionsState.ImageError(ImageFailure.BUDGET), vm.optionsState)
        images.result = { MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.IMAGE) }
        check()
        assertEquals(OptionsState.ImageError(ImageFailure.NETWORK), vm.optionsState)
        for (failure in listOf(ImageFailure.CANCELLED, ImageFailure.STALE)) {
            images.result = { MediaInspectResult.Failure(failure, ImagePhase.PAGE) }
            check()
            assertFalse(failure.toString(), vm.optionsState is OptionsState.Ready)
        }
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun anUnusablePhotoAfterTheMediaVerdictShowsTheErrorAndNeverAsksYtDlp() {
        // What the engine reports when the post is known to be photos but a photo URL is off the allowlist.
        images.result = { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE) }
        check()
        assertEquals(OptionsState.ImageError(ImageFailure.SOURCE_REFUSED), vm.optionsState)
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun aReadFailureBeforeAnyMediaVerdictFallsBackToYtDlpOnce() {
        val failures = listOf(MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE),
            MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 429),
            MediaInspectResult.Failure(ImageFailure.BUDGET, ImagePhase.PAGE),
            MediaInspectResult.Failure(ImageFailure.UNAVAILABLE, ImagePhase.PAGE),
            // What the engine reports for a page timeout, partial body or network error.
            MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE))
        for ((index, failure) in failures.withIndex()) {
            images.result = { failure }
            check()
            assertEquals(failure.toString(), index + 1, engine.inspected.size)
            assertTrue(vm.optionsState is OptionsState.Ready)
        }
        assertTrue(engine.inspected.all { it == canonicalPost })
    }

    @Test fun aliasesAndUserPrefixedPostsUseCanonicalLinksForVideoAndPageFailureHandoffs() {
        for (input in listOf("https://instagr.am/p/SynthPost01/?igsh=Synthetic",
            "https://www.instagram.com/synthetic.user/p/SynthPost01/?igsh=Synthetic")) {
            for (video in listOf(false, true)) {
                images.result = { if (video) MediaInspectResult.VideoRedirect(it)
                    else MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE) }
                vm.edit(input); vm.checkLink(); pump()
                assertEquals(canonicalPost, engine.inspected.last())
                val ready = vm.optionsState as? OptionsState.Ready
                assertNotNull(vm.optionsState.toString(), ready)
                assertEquals(canonicalPost, ready!!.inspection.source.link.rawUrl)
                assertEquals(input, vm.text)
                vm.dismissOptions()
            }
        }
        assertEquals(4, engine.inspected.size)
        assertEquals(List(4) { MediaSourceRoute.INSTAGRAM }, images.routes)
    }

    @Test fun aliasesAndUserPrefixedReelsGoDirectlyToCanonicalYtDlpLinks() {
        for (input in listOf("https://instagr.am/reel/SynthPost01/?igsh=Synthetic",
            "https://www.instagram.com/synthetic.user/reel/SynthPost01/",
            "https://www.instagr.am/synthetic.user/reels/SynthPost01/")) {
            vm.edit(input); vm.checkLink(); pump()
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertEquals("https://www.instagram.com/reel/SynthPost01/", engine.inspected.last())
            vm.dismissOptions()
        }
        assertEquals(3, engine.inspected.size)
        assertEquals(emptyList<MediaSourceRoute>(), images.routes)
    }

    @Test fun withAutomaticLoadingOffNoInstagramReadOrYtDlpRunsUntilViewOptions() {
        vm.automaticOptions = false
        vm.paste(link); pump()
        vm.edit(""); pump()
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, link)
        vm.onNewShare(dev.ahmed.savlino.entry.ShareInput.from(share)); pump()
        assertEquals("the shared link is in the field", link, vm.text)
        assertEquals(emptyList<MediaSourceRoute>(), images.routes)
        assertEquals(emptyList<String>(), engine.inspected)
        vm.checkLink(); pump()
        assertEquals("explicit View options reads once", listOf(MediaSourceRoute.INSTAGRAM), images.routes)
    }

    @Test fun reelLinksGoStraightToYtDlpWithoutAnInstagramRead() {
        val reel = "https://www.instagram.com/reel/SynthPost01/?igsh=Synthetic"
        vm.edit(reel); vm.checkLink(); pump()
        assertEquals(emptyList<MediaSourceRoute>(), images.routes)
        assertEquals(listOf("https://www.instagram.com/reel/SynthPost01/"), engine.inspected)
    }
}
