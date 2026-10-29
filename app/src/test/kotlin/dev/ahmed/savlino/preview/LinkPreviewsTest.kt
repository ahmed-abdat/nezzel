package dev.ahmed.savlino.preview

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class LinkPreviewsTest : PreviewCacheTestSupport() {
    @get:Rule val compose = createEmptyComposeRule()
    private val link = "https://example.com/video"
    private class HeldEngine : EngineAdapter by FakeEngine() {
        var result = CompletableDeferred<InspectResult>()
        val started = CompletableDeferred<LinkRevision>()
        override suspend fun installedVersion() = "runtime-one"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            started.complete(source)
            return result.await()
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun inspection(source: LinkRevision, title: String, thumbnail: String? = null) = Inspection(source, "runtime-one",
        listOf(Format("18", "mp4", true, true, height = 360)), title = title, uploader = "Channel", durationSeconds = 42, thumbnailUrl = thumbnail)
    private fun previews(cache: PreviewCache) = LinkPreviews({ cache }, Dispatchers.Unconfined)
    private fun seed(previews: LinkPreviews) = runBlocking {
        assertTrue(previews.open(link).write(inspection(LinkRevision(LinkInput.parse(link)!!, 1), "Kept title"), image()))
    }

    @Test fun secondOpenRendersCachedHeaderBeforeInspectCompletesAndFreshResultWins() {
        val cache = open()
        val previews = previews(cache)
        val engine = HeldEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, previews = previews)
        vm.paste(link); idle()
        val first = (vm.optionsState as OptionsState.Checking).source
        engine.result.complete(InspectResult.Success(inspection(first, "Kept title"))); idle()
        vm.previewArtworkShown(image()); idle()
        vm.dismissOptions(); idle()
        engine.result = CompletableDeferred()
        vm.checkLink(); idle()
        assertTrue(vm.optionsState is OptionsState.Checking)
        assertFalse(vm.downloadEnabled)
        assertTrue(vm.formatOptions.isEmpty())
        assertEquals("Channel", vm.cachedPreview?.uploader)
        assertEquals(42L, vm.cachedPreview?.durationSeconds)
        assertNotNull(vm.cachedPreview?.artwork)
        val freshArtwork = image().apply { eraseColor(android.graphics.Color.BLUE) }
        val loadedArtwork = CompletableDeferred<android.graphics.Bitmap?>()
        var thumbnailLoads = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                InspectionHeader((vm.optionsState as? OptionsState.Ready)?.inspection, "example.com",
                    checking = vm.optionsState is OptionsState.Checking, cachedPreview = vm.cachedPreview,
                    earlyPreview = dev.ahmed.savlino.engine.mediahttp.EarlyPreview(null, "https://example.com/early.jpg"),
                    load = { thumbnailLoads++; loadedArtwork.await() }, onArtwork = vm::previewArtworkShown)
            }
            compose.onNodeWithText("Kept title").assertExists()
            compose.onNodeWithText("Channel", substring = true).assertExists()
            compose.onNodeWithTag("preview_thumbnail_placeholder").assertDoesNotExist()
            assertEquals("cached pixels must not fetch a preview URL", 0, thumbnailLoads)
            val source = (vm.optionsState as OptionsState.Checking).source
            engine.result.complete(InspectResult.Success(inspection(source, "Fresh title", "https://example.com/fresh.jpg"))); idle()
            compose.onNodeWithText("Fresh title").assertExists()
            assertTrue(vm.downloadEnabled)
            assertEquals(1, thumbnailLoads)
            loadedArtwork.complete(freshArtwork)
            compose.waitForIdle(); idle()
            runBlocking {
                val refreshed = previews.open(link).read { "runtime-one" }!!
                assertEquals("Fresh title", refreshed.title)
                assertEquals(android.graphics.Color.BLUE, refreshed.artwork!!.getPixel(0, 0))
            }
        } finally { vm.dismissOptions(); idle(); controller.pause().stop().destroy() }
    }

    @Test fun failedLiveInspectionCannotSelectCachedFormatsOrConfirmDownload() {
        val cache = open(); val previews = previews(cache); seed(previews)
        val engine = HeldEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, previews = previews)
        vm.paste(link); idle()
        assertEquals("Kept title", vm.cachedPreview?.title)
        engine.result.complete(InspectResult.Failure(DownloadError.Network)); idle()
        assertEquals(OptionsState.Error(DownloadError.Network), vm.optionsState)
        assertTrue(vm.formatOptions.isEmpty())
        assertNull(vm.selectedFormat)
        assertFalse(vm.downloadEnabled)
        vm.confirmDownload()
        assertNull(vm.takeConfirmedCommand())
        vm.dismissOptions(); idle()
    }

    @Test fun intakeWithAutomaticLoadingOffDoesNotExposeCachedDetails() {
        val cache = open(); val previews = previews(cache); seed(previews)
        val vm = LinkViewModel(SavedStateHandle(), engine = HeldEngine(), previews = previews)
        vm.automaticOptions = false
        vm.paste(link); idle()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertNull(vm.cachedPreview)
        vm.checkLink(); idle()
        assertEquals("Kept title", vm.cachedPreview?.title)
        vm.dismissOptions(); idle()
    }

    @Test fun engineStartsAndCompletesWhileCacheOpenIsBlocked() {
        val cache = open()
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val previews = LinkPreviews({ cache }, kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val engine = HeldEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, previews = previews)
        try {
            // Do not run the cache dispatcher: its bootstrap cannot complete until explicitly released below.
            vm.paste(link); idle()
            assertTrue("inspection must start before cache bootstrap can finish", engine.started.isCompleted)
            assertTrue(vm.optionsState is OptionsState.Checking)
            assertNull(vm.cachedPreview)
            val source = runBlocking { engine.started.await() }
            engine.result.complete(InspectResult.Success(inspection(source, "Live title"))); idle()
            assertEquals("Live title", (vm.optionsState as OptionsState.Ready).inspection.title)
            assertTrue(vm.downloadEnabled)
            assertEquals(0, cache.usage().entries)
            // The already-completed live result persists only after the session captures its cache token.
            repeat(3) { scheduler.runCurrent(); idle() }
            assertEquals(1, cache.usage().entries)
            runBlocking { assertEquals("Live title", this@LinkPreviewsTest.previews(cache).open(link).read { "runtime-one" }?.title) }
        } finally { vm.dismissOptions(); idle(); scheduler.runCurrent(); idle() }
    }

    @Test fun optionalCacheFailureDoesNotBlockLiveInspection() {
        val previews = LinkPreviews({ throw java.io.IOException("Storage unavailable") }, Dispatchers.Unconfined)
        val engine = HeldEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, previews = previews)
        vm.paste(link); idle()
        val source = (vm.optionsState as OptionsState.Checking).source
        engine.result.complete(InspectResult.Success(inspection(source, "Live title"))); idle()
        assertEquals("Live title", (vm.optionsState as OptionsState.Ready).inspection.title)
        assertTrue(vm.downloadEnabled)
        assertNull(vm.cachedPreview)
        vm.dismissOptions(); idle()
    }

    @Test fun metadataCompletionCannotEraseArtworkAlreadyDrawnForThatInspection() = runBlocking {
        val previews = previews(open())
        val session = previews.open(link)
        val live = inspection(LinkRevision(LinkInput.parse(link)!!, 1), "Live title")
        assertTrue(session.write(live, image()))
        assertTrue(session.write(live))
        val cached = previews.open(link).read { "runtime-one" }!!
        assertNotNull(cached.artwork)
        assertEquals(android.graphics.Color.RED, cached.artwork!!.getPixel(0, 0))
    }

    @Test fun runtimeChangeMissesAndClearRejectsInFlightInspectionWrites() = runBlocking {
        val cache = open(); val previews = previews(cache); seed(previews)
        assertNull(previews.open(link).read { "runtime-two" })
        val inFlight = previews.open(link)
        assertTrue(cache.clear())
        inFlight.write(inspection(LinkRevision(LinkInput.parse(link)!!, 1), "Late title"), image())
        assertEquals(0, cache.usage().entries)
        assertNull(previews.open(link).read { "runtime-one" })
    }

    @Test fun settingsSharedClearPathRemovesPreviewEntries() = runBlocking {
        val cache = open(); seed(previews(cache))
        val app = RuntimeEnvironment.getApplication()
        val store = JobStore(app)
        try {
            DownloadCoordinator(app, engine = HeldEngine(), store = store, clearPreviews = cache::clear).clearShareLinks()
            assertEquals(0, cache.usage().entries)
        } finally { store.close() }
    }
}
