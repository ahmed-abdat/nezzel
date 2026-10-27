package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.media.ImageSaver
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageInspectionFlowTest {
    private val linkedIn = "https://www.linkedin.com/posts/someone_title-activity-7380000000000000000-AbCd"
    private val direct = "https://example.com/photos/photo.jpg"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var staging: File
    private val publisher = RecordingPublisher()
    private val fetch = FakeFetch()
    private lateinit var saver: ImageSaver
    private var legacyInspections = 0
    private val usageKeys = mutableListOf<String>()
    private val usageSites = mutableListOf<String?>()
    private val usage = object : dev.ahmed.savlino.usage.UsageEvents {
        override fun count(key: String) { usageKeys += key }
        override fun site(link: String?) { usageSites += link }
    }
    private val video = Inspection(LinkRevision(LinkInput.parse(linkedIn)!!, 1), "fixture-v1",
        listOf(Format("720", "mp4", true, true, height = 720)), listOf(PresetChoice(Preset.BALANCED, "720")))

    @Before fun setUp() {
        staging = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "image-save").apply { deleteRecursively() }
        saver = ImageSaver(fetch, publisher, staging, noSpaceCheck, scope)
    }
    @After fun tearDown() { scope.cancel() }

    private fun engine(available: Boolean = true) = object : EngineAdapter by FakeEngine(InspectResult.Success(video)) {
        override val isAvailable get() = available
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            legacyInspections++
            return InspectResult.Success(Inspection(source, "fixture-v1", video.formats, video.presets))
        }
    }
    private fun model(images: FakeImages, engine: EngineAdapter = engine()) = LinkViewModel(SavedStateHandle(), engine = engine,
        currentEngineVersion = { "fixture-v1" }, images = images, imageSaver = saver,
        previewDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, previewImage = ::testBitmap, usage = usage)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun check(vm: LinkViewModel, url: String) { vm.edit(url); vm.checkLink(); idle() }
    private fun settle() { runBlocking { withTimeout(10_000) { saver.awaitIdle() } }; idle() }
    private fun stagingDirs() = File(staging, "downloads").listFiles().orEmpty().toList()

    @Test fun fallbackMatrixCallsLegacyExactlyOnceOnlyBeforeMediaVerdict() {
        val routes = listOf(linkedIn, "https://www.facebook.com/synthetic/posts/123",
            "https://x.com/synthetic/status/1790000000000000001", "https://www.instagram.com/p/SynthPost01/",
            "https://t.me/synthetic_channel/123", "https://bsky.app/profile/synthetic.test/post/3synthetic001", direct)
        for ((index, url) in routes.withIndex()) for (phase in ImagePhase.entries) {
            val cases = listOf(ImageFailure.HTTP to 401, ImageFailure.HTTP to 403, ImageFailure.HTTP to 404,
                ImageFailure.HTTP to 410, ImageFailure.HTTP to 500, ImageFailure.SOURCE_REFUSED to null,
                ImageFailure.BUDGET to null, ImageFailure.UNAVAILABLE to null, ImageFailure.NETWORK to null,
                ImageFailure.NO_MEDIA to null, ImageFailure.PRIVATE to null, ImageFailure.CANCELLED to null,
                ImageFailure.STALE to null, ImageFailure.LINKED_ELSEWHERE to null)
            for ((reason, status) in cases) {
                val before = legacyInspections
                val vm = model(FakeImages { MediaInspectResult.Failure(reason, phase, status) })
                check(vm, url)
                val eligible = index < 6 && phase == ImagePhase.PAGE && when (reason) {
                    ImageFailure.HTTP -> status !in listOf(404, 410)
                    ImageFailure.NO_MEDIA -> index in listOf(0, 1, 4, 5)
                    ImageFailure.SOURCE_REFUSED, ImageFailure.BUDGET, ImageFailure.UNAVAILABLE, ImageFailure.NETWORK -> true
                    else -> false
                }
                assertEquals("route=$index phase=$phase reason=$reason status=$status", if (eligible) 1 else 0, legacyInspections - before)
                vm.dismissOptions()
            }
        }
    }

    @Test fun carouselDefaultsToAllAndSavesOnlyChosenPhotosInPostOrder() {
        val vm = model(FakeImages { imageSuccess(it, found = 4, metadata = MediaMetadata("Carousel")) })
        check(vm, linkedIn)
        val ready = vm.optionsState as OptionsState.ImageReady
        assertEquals(4, ready.photos.size)
        assertEquals(4, vm.selectedImageRefs.size)
        vm.selectAllImages(false)
        assertFalse(vm.imageSaveEnabled)
        vm.toggleImage(ready.photos[3].ref)
        vm.toggleImage(ready.photos[1].ref)
        vm.saveImage(); settle()
        assertEquals(listOf("rendition-1", "rendition-3"), fetch.selections.map { it.renditionRef.renditionId })
        assertEquals(listOf("Carousel_2.jpg", "Carousel_4.jpg"), publisher.names)
        assertEquals(2, publisher.published.get())
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun telegramAndBlueskyAlbumsPublishOnlyConfirmedPhotosThroughTheSharedSaver() {
        for ((route, url) in listOf(MediaSourceRoute.TELEGRAM to "https://t.me/synthetic_channel/123",
            MediaSourceRoute.BLUESKY to "https://bsky.app/profile/synthetic.test/post/3synthetic001")) {
            val images = FakeImages { imageSuccess(it, found = 3, metadata = MediaMetadata("Album")) }
            val vm = model(images)
            val beforeFetch = fetch.selections.size
            val beforePublish = publisher.published.get()
            check(vm, url)
            val ready = vm.optionsState as OptionsState.ImageReady
            assertEquals(listOf(route), images.routes)
            assertEquals(3, ready.photos.size)
            assertEquals(beforePublish, publisher.published.get())
            vm.selectAllImages(false)
            vm.toggleImage(ready.photos[1].ref)
            vm.saveImage(); settle()
            assertEquals(listOf("rendition-1"), fetch.selections.drop(beforeFetch).map { it.renditionRef.renditionId })
            assertEquals(beforePublish + 1, publisher.published.get())
            assertEquals("Album_2.jpg", publisher.names.last())
            assertEquals(OptionsState.Hidden, vm.optionsState)
        }
        assertEquals(0, legacyInspections)
    }

    @Test fun missingPreviewsKeepThePhotoChoicesAndExplicitSaveWorks() {
        for (url in listOf("https://t.me/synthetic_channel/123", "https://bsky.app/profile/synthetic.test/post/3synthetic001")) {
            val images = FakeImages { source ->
                val result = imageSuccess(source, found = 2)
                MediaInspectResult.Success(result.inspection, null,
                    result.discovery.copy(notPreviewedCount = 2), result.saveAvailability) { result.close() }
            }
            val vm = model(images)
            val before = publisher.published.get()
            check(vm, url)
            val ready = vm.optionsState as OptionsState.ImageReady
            assertNull(ready.preview)
            assertTrue(ready.previews.isEmpty())
            assertEquals(2, ready.photos.size)
            assertEquals(2, ready.discovery.notPreviewedCount)
            assertTrue(vm.imageSaveEnabled)
            assertEquals(before, publisher.published.get())
            vm.selectAllImages(false)
            vm.toggleImage(ready.photos[1].ref)
            vm.saveImage(); settle()
            assertEquals(before + 1, publisher.published.get())
            assertEquals("rendition-1", fetch.selections.last().renditionRef.renditionId)
            assertEquals(OptionsState.Hidden, vm.optionsState)
        }
        assertEquals(0, legacyInspections)
    }

    @Test fun newPhotoReadersHandBackVideosAndRejectOtherLinksOrRevisionsAndPrivatePosts() {
        for (url in listOf("https://telegram.me/synthetic_channel/123?single",
            "https://bsky.app/profile/synthetic.test/post/3synthetic001")) {
            val vm = model(FakeImages { MediaInspectResult.VideoRedirect(it) })
            val before = legacyInspections
            check(vm, url)
            val ready = vm.optionsState as OptionsState.Ready
            assertEquals(url, ready.inspection.source.link.rawUrl)
            assertEquals(before + 1, legacyInspections)
            vm.dismissOptions()
            for (differentRevision in listOf(false, true)) {
                val bad = model(FakeImages { MediaInspectResult.VideoRedirect(if (differentRevision)
                    LinkRevision(it.link, it.revision + 1) else LinkRevision(LinkInput.parse("https://example.com/video")!!, it.revision)) })
                check(bad, url)
                assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), bad.optionsState)
                assertEquals(before + 1, legacyInspections)
                bad.dismissOptions()
            }
            for (reason in listOf(ImageFailure.PRIVATE, ImageFailure.APP_ONLY)) {
                val error = model(FakeImages { MediaInspectResult.Failure(reason, ImagePhase.PAGE) })
                check(error, url)
                assertEquals(OptionsState.ImageError(reason, phase = ImagePhase.PAGE), error.optionsState)
                assertEquals(before + 1, legacyInspections)
                error.dismissOptions()
            }
        }
    }

    @Test fun photoCountsUseConfirmationSourceAndOneSiteForEveryPublishedItem() {
        val vm = model(FakeImages { imageSuccess(it, found = 3) })
        check(vm, linkedIn)
        fetch.gate = CompletableDeferred()
        vm.saveImage()
        vm.edit("https://elsewhere.example.org/another-post")
        fetch.gate!!.complete(Unit)
        settle()
        assertEquals(3, publisher.published.get())
        assertEquals(3, usageKeys.count { it == "dl.finished.photo.any" })
        assertEquals(0, usageKeys.count { it == "dl.failed.photo.any" })
        assertEquals("One tap is one funnel end, however many photos it saved", listOf("funnel.finished"), usageKeys.filter { it == "funnel.finished" || it == "funnel.failed" })
        assertEquals("One tap counts its site once", listOf(linkedIn), usageSites)
    }

    @Test fun cancellingBetweenPhotosCountsTheCancelWithoutInventingFailedOrAttemptedItems() {
        val cancellingPublisher = object : dev.ahmed.savlino.storage.Publisher by publisher {
            override fun publish(job: dev.ahmed.savlino.storage.JobDirectory.Job, stagedFile: File, title: String,
                extension: String, mimeType: String, cancellation: android.os.CancellationSignal): dev.ahmed.savlino.storage.PublicationResult {
                val saved = publisher.publish(job, stagedFile, title, extension, mimeType, cancellation)
                saver.cancel() // Commit already won; the next item has not been attempted.
                return saved
            }
        }
        saver = ImageSaver(fetch, cancellingPublisher, staging, noSpaceCheck, scope)
        val vm = model(FakeImages { imageSuccess(it, found = 2) })
        check(vm, linkedIn)
        vm.saveImage(); settle()
        val result = saver.outcome.value as ImageSaveState.BatchFinished
        assertTrue(result.cancelled)
        assertEquals(1, result.saved.size)
        assertEquals(0, result.cancelledCount)
        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.unattemptedCount)
        assertEquals(1, usageKeys.count { it == "dl.finished.photo.any" })
        assertEquals(1, usageKeys.count { it == "dl.cancelled.photo.any" })
        assertEquals(0, usageKeys.count { it == "dl.failed.photo.any" })
        assertEquals(1, fetch.calls.get())
        assertEquals(listOf(linkedIn), usageSites)
    }

    @Test fun cancellationCountsOnlyAttemptedPhotoAndNeverTheUnattemptedTail() {
        val vm = model(FakeImages { imageSuccess(it, found = 4) })
        check(vm, linkedIn)
        fetch.gate = CompletableDeferred()
        vm.saveImage()
        runBlocking { withTimeout(5000) { while (fetch.calls.get() == 0) delay(10) } }
        vm.cancelImageSave()
        fetch.gate!!.complete(Unit)
        settle()
        assertEquals(0, usageKeys.count { it == "dl.failed.photo.any" })
        assertEquals(1, usageKeys.count { it == "dl.cancelled.photo.any" })
        assertEquals(0, usageKeys.count { it == "dl.finished.photo.any" })
        assertTrue(usageSites.isEmpty())
    }

    @Test fun partialCarouselSaveLeavesOnlyFailedPhotosSelectedAndReportsActualPublications() {
        val vm = model(FakeImages { imageSuccess(it, found = 3) })
        check(vm, linkedIn)
        val ready = vm.optionsState as OptionsState.ImageReady
        fetch.failure = ImageSaveResult.Failure(ImageFailure.HTTP, 404)
        fetch.failureAt = 2
        vm.saveImage(); settle()
        assertSame(ready, vm.optionsState)
        assertEquals(setOf(ready.photos[1].ref), vm.selectedImageRefs)
        assertTrue(vm.imageSaveEnabled)
        assertEquals(2, publisher.published.get())
        assertEquals(2, usageKeys.count { it == "dl.finished.photo.any" })
        assertEquals(1, usageKeys.count { it == "dl.failed.photo.any" })
        assertEquals("One tap counts its site once", listOf(linkedIn), usageSites)
        runBlocking { withTimeout(1000) { vm.imageEvents.take(1).collect { event ->
            assertEquals(2, (event as ImageSaveState.BatchFinished).saved.size)
            assertEquals(3, event.total)
        } } }
    }

    @Test fun mixedPostOffersExistingVideoInspectionOnlyOnExplicitSwitch() {
        val vm = model(FakeImages { imageSuccess(it, found = 2, hasVideo = true) })
        check(vm, "https://www.instagram.com/p/SynthPost01/?synthetic=1")
        assertEquals(0, legacyInspections)
        assertEquals(0, fetch.calls.get())
        vm.inspectCarouselVideo(); idle()
        assertEquals(1, legacyInspections)
        val ready = vm.optionsState as OptionsState.Ready
        assertEquals("https://www.instagram.com/p/SynthPost01/", ready.inspection.source.link.rawUrl)
        assertEquals(0, fetch.calls.get())
    }

    @Test fun allFailedPhotosNoticeSurvivesVideoInspectionAndClearsForNextLink() {
        for (url in listOf("https://www.instagram.com/p/SynthPost01/", "https://x.com/synthetic/status/1790000000000000001")) {
            val vm = model(FakeImages { MediaInspectResult.VideoRedirect(it, photosUnavailable = true) })
            check(vm, url)
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertTrue(vm.photosUnavailable)
            vm.dismissOptions()
            assertFalse(vm.photosUnavailable)
        }
    }

    @Test fun previewShownMeansNoSaveNoStagingNoRowAndTheEnginePreviewIsClosed() {
        val preview = TestPreview()
        val images = FakeImages { imageSuccess(it, preview) }
        val vm = model(images)
        check(vm, linkedIn)
        assertEquals(listOf(MediaSourceRoute.LINKEDIN), images.routes)
        val ready = vm.optionsState as? OptionsState.ImageReady
        assertNotNull(vm.optionsState.toString(), ready?.preview)
        assertTrue("engine preview must be closed once copied", preview.isClosed)
        assertTrue(vm.imageSaveEnabled)
        assertEquals(0, fetch.calls.get())
        assertEquals(emptyList<File>(), stagingDirs())
        assertEquals(0, publisher.published.get())
        assertEquals(0, legacyInspections)
    }

    @Test fun linkedInPostWithNoImageFallsBackToTheLegacyEngineExactlyOnce() {
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        val vm = model(images)
        check(vm, linkedIn)
        assertEquals(1, legacyInspections)
        assertEquals(listOf(MediaSourceRoute.LINKEDIN), images.routes)
        assertTrue(vm.optionsState is OptionsState.Ready)
    }

    @Test fun facebookPostWithNoImageFallsBackOnceAndPrivateDoesNot() {
        val post = "https://www.facebook.com/nasa/posts/1234567890"
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        val vm = model(images)
        check(vm, post)
        assertEquals(listOf(MediaSourceRoute.FACEBOOK), images.routes)
        assertEquals(1, legacyInspections)
        assertTrue(vm.optionsState is OptionsState.Ready)
        images.result = { MediaInspectResult.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE) }
        check(vm, "https://www.facebook.com/photo/?fbid=42")
        assertEquals(listOf(MediaSourceRoute.FACEBOOK, MediaSourceRoute.FACEBOOK), images.routes)
        assertEquals(1, legacyInspections)
        assertEquals(OptionsState.ImageError(ImageFailure.PRIVATE, phase = ImagePhase.PAGE), vm.optionsState)
    }

    @Test fun facebookReelGoesStraightToTheLegacyEngine() {
        val images = FakeImages()
        val vm = model(images)
        check(vm, "https://www.facebook.com/reel/1234567890")
        assertEquals(emptyList<MediaSourceRoute>(), images.routes)
        assertEquals(1, legacyInspections)
    }

    @Test fun facebookVideoPostWithoutAnImageGoesToTheLegacyEngine() {
        // The parser answers NO_MEDIA for a video post's thumbnail-only page; the app falls back once.
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.NO_MEDIA, ImagePhase.PAGE) }
        val vm = model(images)
        check(vm, "https://www.facebook.com/story.php?story_fbid=123&id=456")
        assertEquals(listOf(MediaSourceRoute.FACEBOOK), images.routes)
        assertEquals(1, legacyInspections)
        assertTrue(vm.optionsState is OptionsState.Ready)
    }

    @Test fun clearingTheViewModelDuringALateSuccessStillClosesThePreview() {
        val preview = TestPreview()
        val images = FakeImages { imageSuccess(it, preview) }.apply { gate = CompletableDeferred(); nonCancellable = true }
        val store = androidx.lifecycle.ViewModelStore()
        val vm = model(images)
        store.put("links", vm)
        check(vm, direct)
        assertTrue(vm.optionsState is OptionsState.Checking)
        store.clear()
        images.gate!!.complete(Unit)
        // The engine finishes on IO and resumes the cleared (cancelled) caller on main.
        repeat(100) { if (!preview.isClosed) { Thread.sleep(10); idle() } }
        assertTrue("a result delivered to a cleared ViewModel must still be closed", preview.isClosed)
    }

    @Test fun moreThanFourSavedEventsAreAllDeliveredInOrder() {
        val vm = model(FakeImages())
        val saved = (1..6).map { n ->
            check(vm, "https://example.com/photos/p$n.jpg")
            val selection = (vm.optionsState as? OptionsState.ImageReady)?.selection
            assertNotNull(selection)
            vm.saveImage(); settle()
            selection!!
        }
        val events = mutableListOf<ImageSaveState>()
        runBlocking { withTimeoutOrNull(2_000) { vm.imageEvents.take(6).collect { events += it } } }
        assertEquals(saved, events.map { (it as? ImageSaveState.Saved)?.selection })
    }

    @Test fun savedNamePrefersThePostTitleThenTheAuthorThenATimestamp() {
        val long = "word ".repeat(40) + "end"
        val metadata = ArrayDeque(listOf(MediaMetadata(title = "Team / launch: day?", uploader = "Ahmed"),
            MediaMetadata(uploader = "Ahmed Abdat"), MediaMetadata(title = long), MediaMetadata()))
        val vm = model(FakeImages { imageSuccess(it, metadata = metadata.removeFirst()) })
        val names = (1..4).map { n ->
            check(vm, "https://example.com/photos/n$n.jpg")
            vm.saveImage(); settle()
            (saver.outcome.value as? ImageSaveState.Saved)?.displayName
        }
        assertEquals("Team launch day.jpg", names[0])
        assertEquals("Ahmed Abdat.jpg", names[1])
        assertEquals("word ".repeat(16).trim() + ".jpg", names[2]) // short: ≤80 chars, cut at a word
        assertTrue(names[3].toString(), names[3]!!.matches(Regex("Nezzel_\\d{8}_\\d{6}\\.jpg")))
    }

    /** Engine parser output (real LinkedIn JSON-LD shape) → inspection metadata → Save → published name. */
    @Test fun saveNamesTheFileFromTheParsedPostTitleThenAuthor() {
        fun page(headline: String?) = ("<html><head><link rel=canonical href=\"$linkedIn\">" +
            "<meta property=\"og:image\" content=\"https://media.licdn.com/dms/image/v2/SYNTHETIC/1\">" +
            "<script type=\"application/ld+json\">{\"@type\":\"SocialMediaPosting\",\"@id\":\"$linkedIn\"," +
            (headline?.let { "\"headline\":\"$it\"," } ?: "") +
            "\"author\":{\"name\":\"Placeholder Author\"},\"image\":{\"url\":\"https://media.licdn.com/dms/image/v2/SYNTHETIC/1\"}}</script>" +
            "</head><body><article data-activity-urn=\"urn:li:activity:7380000000000000000\"><ul class=\"feed-images-content\">" +
            "<li data-test-id=\"feed-images-content__list-item\" data-position=\"0\"><img data-delayed-url=\"https://media.licdn.com/dms/image/v2/SYNTHETIC/1\"></li>" +
            "</ul></article></body></html>").toByteArray()
        val metadata = ArrayDeque(listOf(page("Launch day: photos https://lnkd.in/x\\nmore"), page(null)).map { html ->
            val parsed = dev.ahmed.savlino.engine.linkedin.LinkedInPostAttachmentParser().parse(html, linkedIn)
            val success = parsed as dev.ahmed.savlino.engine.linkedin.LinkedInParseResult.Success
            MediaMetadata(title = success.title, uploader = success.author)
        })
        val vm = model(FakeImages { imageSuccess(it, metadata = metadata.removeFirst()) })
        val names = (1..2).map { n ->
            check(vm, "$linkedIn?n=$n")
            vm.saveImage(); settle()
            (saver.outcome.value as? ImageSaveState.Saved)?.displayName
        }
        assertEquals(listOf("Launch day photos.jpg", "Placeholder Author.jpg"), names)
    }

    @Test fun sourceRefusalIsItsOwnTypedErrorOnInspectionAndOnSave() {
        val vm = model(FakeImages { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE) })
        check(vm, linkedIn)
        assertEquals(0, legacyInspections)
        assertEquals(OptionsState.ImageError(ImageFailure.SOURCE_REFUSED), vm.optionsState)
        fetch.failure = ImageSaveResult.Failure(ImageFailure.SOURCE_REFUSED)
        val saving = model(FakeImages())
        check(saving, direct)
        saving.saveImage(); settle()
        assertEquals(OptionsState.ImageError(ImageFailure.SOURCE_REFUSED, directImage = true), saving.optionsState)
        assertEquals(0, publisher.published.get())
        // A direct image link refused at inspection is also marked as an image (not a post) failure.
        val refused = model(FakeImages { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE) })
        check(refused, "https://example.com/photos/refused.jpg")
        assertEquals(OptionsState.ImageError(ImageFailure.SOURCE_REFUSED, directImage = true), refused.optionsState)
    }

    @Test fun page403FallsBackExactlyOnce() {
        val images = FakeImages { MediaInspectResult.Failure(ImageFailure.HTTP, ImagePhase.PAGE, 403) }
        val vm = model(images)
        check(vm, linkedIn)
        assertEquals(1, legacyInspections)
        assertTrue(vm.optionsState is OptionsState.Ready)
    }

    @Test fun directImageWorksWhileTheVideoEngineIsUnavailable() {
        val images = FakeImages()
        val vm = model(images, engine(available = false))
        check(vm, direct)
        assertEquals(listOf(MediaSourceRoute.DIRECT_IMAGE), images.routes)
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        check(vm, "https://youtube.com/watch?v=one")
        assertEquals(OptionsState.Error(DownloadError.EngineUnavailable), vm.optionsState)
        assertEquals(1, images.routes.size)
    }

    @Test fun lateSuccessAfterAnEditIsClosedAndIgnored() {
        val preview = TestPreview()
        val images = FakeImages { imageSuccess(it, preview) }.apply { gate = CompletableDeferred() }
        val vm = model(images)
        check(vm, direct)
        assertTrue(vm.optionsState is OptionsState.Checking)
        vm.edit("https://example.com/photos/other.png")
        assertEquals(1, images.cancelled.size)
        images.gate!!.complete(Unit); idle()
        assertTrue(preview.isClosed)
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun saveNeverAsksForNotificationsAndDismissesWithASavedEvent() {
        val vm = model(FakeImages())
        var prompted = false
        vm.notificationPrompt = { prompted = true }
        val events = mutableListOf<ImageSaveState>()
        val collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.imageEvents.collect { events += it } }
        check(vm, direct)
        vm.saveImage()
        settle()
        assertFalse(prompted)
        assertFalse(vm.hasPendingNotificationChoice)
        assertEquals(1, fetch.calls.get())
        assertEquals(1, publisher.published.get())
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertTrue(events.single() is ImageSaveState.Saved)
        assertEquals(emptyList<File>(), stagingDirs())
        collector.cancel()
    }

    @Test fun aNewImageInspectionWaitsForTheRunningSave() {
        fetch.gate = CompletableDeferred()
        val images = FakeImages()
        val vm = model(images)
        check(vm, direct)
        vm.saveImage(); idle()
        assertTrue(vm.imageSaving)
        vm.edit("https://example.com/photos/next.png"); vm.checkLink(); idle()
        assertEquals("inspection must wait for the in-flight Save", 1, images.routes.size)
        fetch.gate!!.complete(Unit)
        settle()
        assertEquals(2, images.routes.size)
        assertTrue(vm.optionsState is OptionsState.ImageReady)
    }
}
