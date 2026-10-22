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

/** Sub-unit B: a Facebook video post is inspected and downloaded by yt-dlp through its `watch/?v=` link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FacebookVideoHandoffTest {
    private val post = "https://www.facebook.com/somepage/posts/900000000000001"
    private val watch = "https://www.facebook.com/watch/?v=900000000000002"
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel

    private inner class RecordingEngine : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        var failure: DownloadError? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            gate?.await()
            return failure?.let { InspectResult.Failure(it) } ?: InspectResult.Success(Inspection(source, "2026.08.19",
                listOf(Format("720", "mp4", true, true, height = 720)), listOf(PresetChoice(Preset.BALANCED, "720")),
                title = "Real title"))
        }
    }
    private val engine = RecordingEngine()
    private val images = FakeImages { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(watch)!!, it.revision)) }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in this fixture") })
        vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, images = images,
            previewDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, previewImage = ::testBitmap)
    }
    @After fun tearDown() { vm.dismissOptions(); store.close() }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun check(url: String) { vm.edit(url); vm.checkLink(); pump() }
    private fun awaitRows() = runBlocking {
        withTimeout(5000) { while (store.all().isEmpty()) { pump(); delay(5) } }
        pump()
        store.all()
    }

    @Test fun noMediaPageKeepsItsVerdictWhenLegacyCannotFindMedia() {
        images.result = { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        for (error in listOf(DownloadError.Failed, DownloadError.Unavailable, DownloadError.Unsupported)) {
            engine.inspected.clear()
            engine.failure = error
            check(post)
            assertEquals(listOf(post), engine.inspected)
            assertEquals(OptionsState.ImageError(ImageFailure.NO_MEDIA), vm.optionsState)
        }
    }

    @Test fun noMediaPageStillShowsFormatsWhenLegacyFindsAVideo() {
        images.result = { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        check(post)
        assertEquals(listOf(post), engine.inspected)
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertTrue(vm.downloadEnabled)
    }

    @Test fun noMediaPagePreservesNetworkAndAccessErrorsFromLegacy() {
        images.result = { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        for (error in listOf(DownloadError.Network, DownloadError.Offline, DownloadError.EngineUnavailable,
            DownloadError.LoginRequired, DownloadError.SourceRefused)) {
            engine.failure = error
            check(post)
            assertEquals(OptionsState.Error(error), vm.optionsState)
        }
    }

    @Test fun noMediaOverrideDoesNotLeakIntoANewOperationOrOtherReaders() {
        images.result = { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        engine.failure = DownloadError.Failed
        check(post)
        check(watch)
        assertEquals(OptionsState.Error(DownloadError.Failed), vm.optionsState)
        check("https://www.linkedin.com/feed/update/urn:li:activity:900000000000001/")
        assertEquals(OptionsState.Error(DownloadError.Failed), vm.optionsState)
        images.result = { MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE) }
        check(post)
        assertEquals(OptionsState.Error(DownloadError.Failed), vm.optionsState)
    }

    private fun mixedResult(source: LinkRevision, videoSource: LinkRevision?) = imageSuccess(source, hasVideo = true).let {
        MediaInspectResult.Success(it.inspection, it.preview, it.discovery, it.saveAvailability, it.previews,
            videoSource = videoSource) { it.close() }
    }

    @Test fun carouselVideoUsesTheWatchLinkAndKeepsStalenessBoundToTheUsersPost() {
        images.result = { mixedResult(it, LinkRevision(LinkInput.parse(watch)!!, it.revision)) }
        check(post)
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        vm.inspectCarouselVideo(); pump()
        assertEquals(listOf(watch), engine.inspected)
        val ready = vm.optionsState as OptionsState.Ready
        assertEquals(post, ready.resolvedFrom?.link?.rawUrl)
        assertEquals(watch, ready.inspection.source.link.rawUrl)
        assertTrue(vm.downloadEnabled)
    }

    @Test fun carouselVideoRefusesUntrustedOrStaleWatchLinks() {
        for (bad in listOf("https://example.com/watch/?v=900000000000003", "$watch&t=5")) {
            images.result = { mixedResult(it, LinkRevision(LinkInput.parse(bad)!!, it.revision)) }
            check(post); vm.inspectCarouselVideo(); pump()
            assertTrue(engine.inspected.isEmpty())
            assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        images.result = { mixedResult(it, LinkRevision(LinkInput.parse(watch)!!, it.revision + 1)) }
        check(post); vm.inspectCarouselVideo(); pump()
        assertTrue(engine.inspected.isEmpty())
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
    }

    @Test fun carouselWithoutOneVideoLinkKeepsTheOriginalInspectionPath() {
        images.result = { mixedResult(it, null) }
        check(post); vm.inspectCarouselVideo(); pump()
        assertEquals(listOf(post), engine.inspected)
        assertTrue(vm.optionsState is OptionsState.Ready)
    }

    @Test fun eventLinksAreUnsupportedWithoutCallingEitherInspector() {
        for (host in listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com")) {
            check("https://$host/events/900000000000001/")
            assertEquals(OptionsState.Error(DownloadError.Unsupported), vm.optionsState)
        }
        assertTrue(images.routes.isEmpty())
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun directStoryLinksShowTheStoryFailureWithoutCallingEitherInspector() {
        for (host in listOf("www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com")) {
            check("https://$host/stories/900000000000001/Synthetic/")
            assertEquals(OptionsState.ImageError(ImageFailure.FACEBOOK_STORY, phase = ImagePhase.PAGE), vm.optionsState)
            assertFalse(vm.canReportLink)
            assertFalse(vm.canUpdateAndRetry)
        }
        assertTrue(images.routes.isEmpty())
        assertTrue(engine.inspected.isEmpty())
        val legacyOnly = LinkViewModel(SavedStateHandle(), engine = engine)
        legacyOnly.edit("https://mbasic.facebook.com/stories/123/Synthetic/")
        legacyOnly.checkLink(); pump()
        assertEquals(OptionsState.ImageError(ImageFailure.FACEBOOK_STORY, phase = ImagePhase.PAGE), legacyOnly.optionsState)
        assertTrue(engine.inspected.isEmpty())
        legacyOnly.dismissOptions()
    }

    @Test fun redirectedStoryFailuresNeverFallBackToYtdlpOrOfferReportOrEngineUpdate() {
        images.result = { MediaInspectResult.Failure(ImageFailure.FACEBOOK_STORY, ImagePhase.PAGE) }
        check("https://www.facebook.com/share/Synthetic/")
        assertEquals(OptionsState.ImageError(ImageFailure.FACEBOOK_STORY, phase = ImagePhase.PAGE), vm.optionsState)
        assertFalse(vm.canReportLink)
        assertFalse(vm.canUpdateAndRetry)
        assertEquals(listOf(MediaSourceRoute.FACEBOOK), images.routes)
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun eventLinksAreUnsupportedEvenWithoutAnImageInspector() {
        val legacyOnly = LinkViewModel(SavedStateHandle(), engine = engine)
        legacyOnly.edit("https://mbasic.facebook.com/events/900000000000001/photo.jpg")
        legacyOnly.checkLink(); pump()
        assertEquals(OptionsState.Error(DownloadError.Unsupported), legacyOnly.optionsState)
        assertTrue(engine.inspected.isEmpty())
        legacyOnly.dismissOptions()
    }

    @Test fun editingThePostDuringCarouselVideoInspectionDropsItsResult() {
        images.result = { mixedResult(it, LinkRevision(LinkInput.parse(watch)!!, it.revision)) }
        check(post)
        engine.gate = CompletableDeferred()
        vm.inspectCarouselVideo(); pump()
        assertEquals(listOf(watch), engine.inspected)
        vm.edit("https://www.facebook.com/synthetic/posts/900000000000009")
        engine.gate!!.complete(Unit); pump()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertFalse(vm.downloadEnabled)
    }

    @Test fun pageFailuresUseTheOriginalPostExactlyOnce() {
        for ((reason, status) in listOf(ImageFailure.BUDGET to null, ImageFailure.NETWORK to null,
            ImageFailure.UNAVAILABLE to null, ImageFailure.SOURCE_REFUSED to null, ImageFailure.NO_MEDIA to null,
            ImageFailure.HTTP to 401, ImageFailure.HTTP to 403, ImageFailure.HTTP to 500)) {
            engine.inspected.clear()
            images.result = { MediaInspectResult.Failure(reason, ImagePhase.PAGE, status) }
            check(post)
            assertEquals(listOf(post), engine.inspected)
        }
    }

    @Test fun videoPostInspectsTheWatchUrlOnceAndTheConfirmedJobUsesIt() {
        check(post)
        assertEquals(listOf(MediaSourceRoute.FACEBOOK), images.routes)
        assertEquals("exactly one yt-dlp inspection, on the watch link", listOf(watch), engine.inspected)
        val ready = vm.optionsState as? OptionsState.Ready
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(watch, ready!!.inspection.source.link.rawUrl)
        assertEquals("the user's own link stays in the field", post, vm.text)
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload()
        val row = awaitRows().single()
        assertEquals(watch, row.sourceUrl)
        assertEquals(ready.inspection.source.revision, row.revision)
        assertEquals(listOf(watch), engine.inspected)
    }

    @Test fun ytDlpFailureOnTheWatchUrlIsTheNormalError() {
        engine.failure = DownloadError.Unsupported
        check(post)
        assertEquals(listOf(watch), engine.inspected)
        assertEquals(OptionsState.Error(DownloadError.Unsupported), vm.optionsState)
        assertEquals(post, vm.text)
        assertTrue(store.all().isEmpty())
    }

    @Test fun editingTheLinkDuringTheWatchInspectionDropsItsResult() {
        engine.gate = CompletableDeferred()
        check(post)
        assertEquals(listOf(watch), engine.inspected)
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Checking)
        assertEquals(post, (vm.optionsState as OptionsState.Checking).source.link.rawUrl)
        vm.edit("https://www.facebook.com/somepage/posts/900000000000009")
        engine.gate!!.complete(Unit); pump()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        vm.confirmDownload()
        assertTrue(store.all().isEmpty())
    }

    @Test fun aRedirectThatIsNotAFacebookWatchLinkOrIsForAnotherRevisionIsRefused() {
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse("https://example.com/watch/?v=1")!!, it.revision)) }
        check(post)
        assertEquals(emptyList<String>(), engine.inspected)
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(watch)!!, it.revision + 1)) }
        check(post)
        assertEquals(emptyList<String>(), engine.inspected)
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
    }

    @Test fun onlyTheExactCanonicalWatchLinkIsHandedOver() {
        for (bad in listOf("$watch&t=5", "https://m.facebook.com/watch/?v=900000000000002",
            "https://www.facebook.com/watch/?v=900000000000002#x", "https://www.facebook.com/watch?v=900000000000002",
            "https://www.facebook.com/reel/900000000000002")) {
            val link = LinkInput.parse(bad) ?: continue
            images.result = { MediaInspectResult.VideoRedirect(LinkRevision(link, it.revision)) }
            check(post)
            assertEquals(bad, emptyList<String>(), engine.inspected)
            assertEquals(bad, OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
    }

    @Test fun aVideoRedirectFromAnyRouteButFacebookIsRefused() {
        for (link in listOf("https://www.linkedin.com/posts/someone_title-activity-7380000000000000000-AbCd",
            "https://example.com/photos/photo.jpg")) {
            check(link)
            assertEquals(emptyList<String>(), engine.inspected)
            assertEquals(link, OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        assertEquals(listOf(MediaSourceRoute.LINKEDIN, MediaSourceRoute.DIRECT_IMAGE), images.routes)
    }

    @Test fun photoPostsReelsAndWatchLinksAreUnchanged() {
        images.result = { imageSuccess(it) }
        check("https://www.facebook.com/photo/?fbid=42")
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        assertEquals(emptyList<String>(), engine.inspected)
        for (link in listOf("https://www.facebook.com/reel/1234567890", watch)) {
            check(link)
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertEquals(link, (vm.optionsState as OptionsState.Ready).inspection.source.link.rawUrl)
        }
        assertEquals(listOf(MediaSourceRoute.FACEBOOK), images.routes)
        assertEquals(listOf("https://www.facebook.com/reel/1234567890", watch), engine.inspected)
    }
}
