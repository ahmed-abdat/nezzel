package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.engine.tiktok.TikTokSound
import dev.ahmed.savlino.media.ImageSaver
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TikTokImageRouteTest {
    private val photo = "https://www.tiktok.com/@synthetic/photo/900000001"
    private val video = "https://www.tiktok.com/@synthetic/video/900000001"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<LinkViewModel>()
    private val staging get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "tiktok-images")
    private val reads = mutableListOf<String>()
    private val audio = listOf(Format("audio", "m4a", false, true),
        Format("audio-mp3", "mp3", false, true, conversion = AudioConversion.MAIN, sourceFormatId = "audio"))
    private fun engine(formats: List<Format> = audio) = object : EngineAdapter by FakeEngine() {
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            reads += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "fixture-v1", formats))
        }
    }
    private fun success(source: LinkRevision): MediaInspectResult.Success = photoSuccess(source, 2)
    private fun photoSuccess(source: LinkRevision, photos: Int): MediaInspectResult.Success {
        val original = imageSuccess(source, found = photos, metadata = MediaMetadata("Post"))
        return MediaInspectResult.Success(original.inspection, original.preview,
            original.discovery.copy(sound = TikTokSound(null, null, 12), photoLimit = 40), original.saveAvailability,
            original.previews, LinkRevision(assertNotNull(LinkInput.parse(video)), source.revision)) { original.close() }
    }
    private fun model(images: FakeImages, engine: EngineAdapter = engine(), saver: ImageSaver? = null): LinkViewModel =
        LinkViewModel(SavedStateHandle(), engine = engine, images = images, imageSaver = saver,
            currentEngineVersion = { "fixture-v1" }, previewDispatcher = Dispatchers.Unconfined, previewImage = ::testBitmap)
            .also { models += it }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun check(model: LinkViewModel, url: String) { model.edit(url); model.checkLink(); idle() }
    @After fun stop() { models.forEach { it.dismissOptions() }; scope.cancel(); staging.deleteRecursively() }

    @Test fun refreshFailureKeepsItsReasonAndOnlyNotFoundBecomesExpired() {
        for (failure in listOf(MediaInspectResult.Failure(ImageFailure.NETWORK, ImagePhase.PAGE),
            MediaInspectResult.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE),
            MediaInspectResult.Failure(ImageFailure.DECODE, ImagePhase.IMAGE),
            MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 503),
            MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 404),
            MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 410),
            MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE))) {
            val saver = ImageSaver(FakeFetch(failure = ImageSaveResult.Failure(ImageFailure.EXPIRED, 410)),
                RecordingPublisher(), staging, noSpaceCheck, scope)
            val images = FakeImages(::success)
            val vm = model(images, saver = saver)
            check(vm, photo)
            images.result = { failure }
            vm.saveImage()
            runBlocking { withTimeout(10_000) { saver.awaitIdle() } }; idle()
            val error = assertIs<OptionsState.ImageError>(vm.optionsState)
            val notFound = failure.reason == ImageFailure.NO_MEDIA || failure.statusCode in setOf(404, 410)
            assertEquals(if (notFound) ImageFailure.EXPIRED else failure.reason, error.failure)
            assertEquals(failure.statusCode, error.statusCode)
            assertEquals(failure.phase, error.phase)
            assertTrue(reads.isEmpty())
            vm.dismissOptions()
        }
    }

    @Test fun photoReaderPrecedesYtdlpAndSoundHandsOffOnceWithoutLoopingBack() {
        val images = FakeImages(::success)
        val vm = model(images)
        check(vm, photo)
        assertIs<OptionsState.ImageReady>(vm.optionsState)
        assertTrue(reads.isEmpty())
        vm.inspectCarouselVideo(); idle()
        assertIs<OptionsState.Ready>(vm.optionsState)
        assertEquals(listOf(video), reads)
        assertEquals(listOf(MediaSourceRoute.TIKTOK), images.routes)
        vm.selectFormat("audio")
        vm.confirmDownload()
        assertEquals(video, assertNotNull(vm.takeConfirmedCommand()).source.link.rawUrl)
    }
    @Test fun aSinglePhotoKeepsThePlainNameAndSinglePhotoPresentation() {
        val publisher = RecordingPublisher()
        val saver = ImageSaver(FakeFetch(), publisher, staging, noSpaceCheck, scope)
        val images = FakeImages { photoSuccess(it, 1) }
        val vm = model(images, saver = saver)
        check(vm, photo)
        assertFalse(assertIs<OptionsState.ImageReady>(vm.optionsState).isCarousel)
        vm.saveImage()
        runBlocking { withTimeout(10_000) { saver.awaitIdle() } }; idle()
        assertEquals(listOf("Post.jpg"), publisher.names)
    }
    @Test fun audioOnlyVideoTriesPhotosOnceAndReaderFailureRestoresTheOriginalAudioChoices() {
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE) }
        val vm = model(images)
        check(vm, video)
        assertEquals(listOf(video), reads)
        assertEquals(listOf(MediaSourceRoute.TIKTOK), images.routes)
        assertEquals(audio, assertIs<OptionsState.Ready>(vm.optionsState).inspection.formats)
    }
    @Test fun audioOnlyVideoShowsPhotosAndItsSoundSwitchDoesNotReenterTheReader() {
        val images = FakeImages(::success)
        val vm = model(images)
        check(vm, video)
        assertIs<OptionsState.ImageReady>(vm.optionsState)
        assertEquals(listOf(video), reads)
        vm.inspectCarouselVideo(); idle()
        assertIs<OptionsState.Ready>(vm.optionsState)
        assertEquals(listOf(video, video), reads)
        assertEquals(listOf(MediaSourceRoute.TIKTOK), images.routes)
    }
    @Test fun photoPageFailureHandsOffToAudioOnlyOnce() {
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE) }
        val vm = model(images)
        check(vm, photo)
        assertIs<OptionsState.Ready>(vm.optionsState)
        assertEquals(listOf(video), reads)
        assertEquals(listOf(MediaSourceRoute.TIKTOK), images.routes)
    }
    @Test fun actualVideoOrUnrelatedAudioNeverTriggersAPhotoPageRead() {
        for (formats in listOf(listOf(Format("720", "mp4", true, true, height = 720)),
            listOf(Format("other-audio", "m4a", false, true)))) {
            val images = FakeImages(::success)
            val vm = model(images, engine(formats))
            check(vm, video)
            assertIs<OptionsState.Ready>(vm.optionsState)
            assertTrue(images.routes.isEmpty())
            vm.dismissOptions()
        }
    }
    @Test fun expiryRefreshesOnceKeepsSavedSiblingsUnselectedAndNeverTransfersAutomatically() {
        val fetch = FakeFetch(failure = ImageSaveResult.Failure(ImageFailure.EXPIRED, 410), failureAt = 2)
        val publisher = RecordingPublisher()
        val saver = ImageSaver(fetch, publisher, staging, noSpaceCheck, scope)
        val images = FakeImages(::success)
        val vm = model(images, saver = saver)
        check(vm, photo)
        vm.saveImage()
        runBlocking { withTimeout(10_000) { saver.awaitIdle() } }; idle()
        val ready = assertIs<OptionsState.ImageReady>(vm.optionsState)
        assertEquals(2, images.routes.size)
        assertEquals(setOf(ready.photos[1].ref), vm.selectedImageRefs)
        assertEquals(listOf("Post_1.jpg"), publisher.names)
        assertEquals(2, fetch.calls.get())
        fetch.failureAt = null
        vm.saveImage()
        runBlocking { withTimeout(10_000) { saver.awaitIdle() } }; idle()
        val expired = assertIs<OptionsState.ImageReady>(vm.optionsState)
        assertEquals(2, images.routes.size)
        assertTrue(expired.photos[1].ref in expired.expiredPhotos)
        assertFalse(vm.imageSaveEnabled)
        assertEquals(3, fetch.calls.get())
        assertEquals(listOf("Post_1.jpg"), publisher.names)
    }
}
