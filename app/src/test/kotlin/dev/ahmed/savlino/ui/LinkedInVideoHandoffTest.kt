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

/** A LinkedIn video post is inspected and downloaded by yt-dlp through its clean post URL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LinkedInVideoHandoffTest {
    private val shortlink = "https://lnkd.in/p/synthetic"
    private val post = "https://www.linkedin.com/posts/someone_title-ugcPost-7380000000000000002-AbCd/"
    private val feed = "https://www.linkedin.com/feed/update/urn:li:activity:7380000000000000001/"
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel

    private inner class RecordingEngine : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "2026.08.19",
                listOf(Format("720", "mp4", true, true, height = 720)), listOf(PresetChoice(Preset.BALANCED, "720")),
                title = "Real title"))
        }
    }
    private val engine = RecordingEngine()
    private val images = FakeImages { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(post)!!, it.revision)) }

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

    @Test fun regionalEntryWithoutLandingStillHandsWwwToLegacy() {
        images.result = pageFailure(ImageFailure.NETWORK, fallback = null)
        check(post.replace("www.", "ae."))
        assertEquals(listOf(post), engine.inspected)
    }

    @Test fun unusableFallbackStillInspectsOnlyTheOriginalSharedLink() {
        for (fallback in listOf(null, "$post?utm_source=share", "https://www.facebook.com/watch/?v=123")) {
            engine.inspected.clear()
            images.result = pageFailure(ImageFailure.BUDGET, fallback = fallback)
            check(shortlink)
            assertEquals(listOf(shortlink), engine.inspected)
        }
    }

    @Test fun videoPostInspectsTheCleanPostUrlOnceAndTheConfirmedJobUsesIt() {
        check(shortlink)
        assertEquals(listOf(MediaSourceRoute.LINKEDIN), images.routes)
        assertEquals("exactly one yt-dlp inspection, on the clean post URL", listOf(post), engine.inspected)
        val ready = vm.optionsState as? OptionsState.Ready
        assertNotNull(vm.optionsState.toString(), ready)
        assertEquals(post, ready!!.inspection.source.link.rawUrl)
        assertEquals("the user's own link stays in the field", shortlink, vm.text)
        vm.confirmDownload()
        val row = awaitRows().single()
        assertEquals(post, row.sourceUrl)
        assertEquals(listOf(post), engine.inspected)
    }

    @Test fun theActivityFormIsAlsoCanonical() {
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(feed)!!, it.revision)) }
        check(shortlink)
        assertEquals(listOf(feed), engine.inspected)
        assertTrue(vm.optionsState is OptionsState.Ready)
    }

    @Test fun anEncodedArabicSlugIsCanonical() {
        val arabic = "https://www.linkedin.com/posts/%D8%A3%D8%AD%D9%85%D8%AF-%d8%b9_ugcPost-7380000000000000002-AbCd/"
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(arabic)!!, it.revision)) }
        check(shortlink)
        assertEquals(listOf(arabic), engine.inspected)
        assertEquals(arabic, (vm.optionsState as OptionsState.Ready).inspection.source.link.rawUrl)
    }

    @Test fun onlyTheExactCanonicalPostUrlIsHandedOver() {
        for (bad in listOf("${post}?utm_source=share", post.removeSuffix("/"), post.replace("www.", ""),
            post.replace("https://www.linkedin.com", "https://www.linkedin.com.evil.example"), "$post#x",
            "https://www.linkedin.com/in/someone/", "https://www.linkedin.com/posts/a/b/", feed.removeSuffix("/"),
            "https://www.linkedin.com/feed/update/urn:li:share:7380000000000000001/", "https://lnkd.in/p/synthetic",
            "https://www.facebook.com/watch/?v=900000000000002", "https://example.com/posts/someone/",
            "https://www.linkedin.com/posts/%D8%A3%2F..%2Fx/", "https://www.linkedin.com/posts/a%2e%2eb/",
            "https://www.linkedin.com/posts/a%3Fb/", "https://www.linkedin.com/posts/a%23b/", "https://www.linkedin.com/posts/a%25b/",
            "https://www.linkedin.com/posts/a%0Ab/", "https://www.linkedin.com/posts/a%G1b/", "https://www.linkedin.com/posts/a%D/",
            "https://www.linkedin.com/posts/%D8%A3/?x=1")) {
            val link = LinkInput.parse(bad) ?: continue
            images.result = { MediaInspectResult.VideoRedirect(LinkRevision(link, it.revision)) }
            check(shortlink)
            assertEquals(bad, emptyList<String>(), engine.inspected)
            assertEquals(bad, OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        images.result = { MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse(post)!!, it.revision + 1)) }
        check(shortlink)
        assertEquals(emptyList<String>(), engine.inspected)
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
    }

    private fun pageFailure(reason: ImageFailure, fallback: String? = post, phase: ImagePhase = ImagePhase.PAGE,
        status: Int? = null, revisionOffset: Long = 0): (LinkRevision) -> MediaInspectResult = { source ->
        MediaInspectResult.Failure(reason, phase, status,
            fallback?.let { LinkRevision(LinkInput.parse(it)!!, source.revision + revisionOffset) })
    }

    @Test fun aPreVerdictPageFailureFallsBackOnceToYtDlpWithTheCleanLanding() {
        for ((reason, status) in listOf(ImageFailure.BUDGET to null, ImageFailure.NETWORK to null,
            ImageFailure.SOURCE_REFUSED to null, ImageFailure.UNAVAILABLE to null, ImageFailure.HTTP to 500, ImageFailure.HTTP to 401, ImageFailure.HTTP to 403,
            ImageFailure.NO_MEDIA to null)) {
            engine.inspected.clear()
            images.result = pageFailure(reason, status = status)
            check(shortlink)
            assertEquals("$reason", listOf(post), engine.inspected)
            val ready = vm.optionsState as? OptionsState.Ready
            assertNotNull("$reason ${vm.optionsState}", ready)
            assertEquals(post, ready!!.inspection.source.link.rawUrl)
            assertEquals(shortlink, vm.text)
        }
    }

    @Test fun imageFailuresAndNotPublicKeepTheirErrors() {
        val cases = listOf(
            pageFailure(ImageFailure.HTTP, phase = ImagePhase.IMAGE, status = 403) to OptionsState.ImageError(ImageFailure.HTTP, 403),
            pageFailure(ImageFailure.DECODE, phase = ImagePhase.DECODE) to OptionsState.ImageError(ImageFailure.DECODE, phase = ImagePhase.DECODE),
            pageFailure(ImageFailure.HTTP, status = 404) to OptionsState.ImageError(ImageFailure.HTTP, 404, phase = ImagePhase.PAGE),
            pageFailure(ImageFailure.PRIVATE) to OptionsState.ImageError(ImageFailure.PRIVATE, phase = ImagePhase.PAGE),
        )
        for ((index, case) in cases.withIndex()) {
            images.result = case.first
            check(shortlink)
            assertEquals("case $index", emptyList<String>(), engine.inspected)
            assertEquals("case $index", case.second, vm.optionsState)
        }
    }

    @Test fun aLinkedInPostRedirectFromAnyOtherRouteIsRefused() {
        for (link in listOf("https://www.facebook.com/somepage/posts/900000000000001", "https://example.com/photos/photo.jpg")) {
            check(link)
            assertEquals(emptyList<String>(), engine.inspected)
            assertEquals(link, OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        }
        assertEquals(listOf(MediaSourceRoute.FACEBOOK, MediaSourceRoute.DIRECT_IMAGE), images.routes)
    }
}
