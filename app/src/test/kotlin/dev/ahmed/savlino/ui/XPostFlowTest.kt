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

/** X posts: photos come from the X reader; video/GIF posts still reach yt-dlp with the user's own link, unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class XPostFlowTest {
    private val link = "https://x.com/someone/status/1790000000000000001?s=20"
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel

    private inner class RecordingEngine : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        override suspend fun installedVersion() = "2026.08.19"
        // X's lone direct mp4 without codec facts (the just-landed fallback row).
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

    @Test fun mirrorVideoAndPageFailureInspectOnlyCanonicalX() {
        val canonical = "https://x.com/i/status/1790000000000000001"
        for (host in listOf("fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com")) {
            for (video in listOf(false, true)) {
                engine.inspected.clear()
                images.result = { source -> if (video) MediaInspectResult.VideoRedirect(source)
                    else MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE) }
                vm.edit("https://$host/synthetic/status/1790000000000000001")
                vm.checkLink(); pump()
                assertEquals(listOf(canonical), engine.inspected)
                vm.dismissOptions()
            }
        }
    }

    @Test fun photoPostShowsTheImageWithoutYtDlp() {
        images.result = { imageSuccess(it, found = 4) }
        check()
        assertEquals(listOf(MediaSourceRoute.X), images.routes)
        val ready = vm.optionsState as? OptionsState.ImageReady
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(4, ready!!.discovery.discoveredCount)
        assertFalse(ready.directImage)
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun videoPostReachesYtDlpWithTheUsersOwnLinkAndKeepsTheLoneMp4Row() {
        images.result = { MediaInspectResult.VideoRedirect(it) }
        check()
        assertEquals(listOf(link), engine.inspected)
        val ready = vm.optionsState as? OptionsState.Ready
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(link, ready!!.inspection.source.link.rawUrl)
        assertEquals(listOf("http"), vm.formatOptions.map { it.id })
        vm.selectFormat("http")
        vm.confirmDownload()
        val row = runBlocking { withTimeout(5000) { while (store.all().isEmpty()) { pump(); delay(5) } }; pump(); store.all().single() }
        assertEquals(link, row.sourceUrl)
    }

    @Test fun aRedirectToAnyOtherLinkOrRevisionIsRefused() {
        for (other in listOf("https://x.com/i/status/1790000000000000001", "https://x.com/someone/status/1790000000000000002")) {
            images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(other)!!, it.revision)) }
            check()
            assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(it.link, it.revision + 1)) }
        check()
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        assertEquals(emptyList<String>(), engine.inspected)
    }

    @Test fun textPostIsNoMediaAndNeverAskedOfYtDlp() {
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
            // What the engine reports for a syndication timeout, partial body or network error (XInspectionTest).
            MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE))
        for ((index, failure) in failures.withIndex()) {
            images.result = { failure }
            check()
            assertEquals(failure.toString(), index + 1, engine.inspected.size)
            assertTrue(vm.optionsState is OptionsState.Ready)
        }
        assertTrue(engine.inspected.all { it == link })
    }

    @Test fun withAutomaticLoadingOffNoXReadOrYtDlpRunsUntilViewOptions() {
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
        assertEquals("explicit View options reads once", listOf(MediaSourceRoute.X), images.routes)
    }
}
