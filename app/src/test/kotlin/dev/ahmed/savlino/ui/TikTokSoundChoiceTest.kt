package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.media.ImageSaver
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

/** One Save on a TikTok photo post: photos only, the sound only, or both; the sound is a real engine audio row or nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TikTokSoundChoiceTest {
    private val photo = "https://www.tiktok.com/@synthetic/photo/900000001"
    private val video = "https://www.tiktok.com/@synthetic/video/900000001"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val staging get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "tiktok-sound")
    private val reads = mutableListOf<String>()
    private var result: (LinkRevision) -> InspectResult = { source ->
        // MP3 listed first: the M4A row the engine returned must still win.
        InspectResult.Success(Inspection(source, "fixture-v1", listOf(
            Format("audio-mp3", "mp3", false, true, conversion = AudioConversion.MAIN, sourceFormatId = "audio"),
            Format("audio", "m4a", false, true))))
    }
    private val engine = object : EngineAdapter by FakeEngine() {
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            reads += source.link.rawUrl
            return result(source)
        }
    }
    private val publisher = RecordingPublisher()
    private lateinit var store: JobStore
    private lateinit var saver: ImageSaver
    private lateinit var vm: LinkViewModel
    private val notices = AtomicInteger()

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        store = JobStore(app).also { it.all() } // Warm the cold SQLite open outside the timed waits.
        saver = ImageSaver(FakeFetch(), publisher, staging, noSpaceCheck, scope)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in this fixture") })
        vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, images = FakeImages { tikTokPhotoSuccess(it, 2) },
            imageSaver = saver, currentEngineVersion = { "fixture-v1" }, previewDispatcher = Dispatchers.Unconfined, previewImage = ::testBitmap)
        scope.launch(Dispatchers.Unconfined) { vm.soundFailedEvents.collect { notices.incrementAndGet() } }
        vm.edit(photo); vm.checkLink(); pump()
        assertIs<OptionsState.ImageReady>(vm.optionsState)
    }
    @After fun tearDown() { vm.dismissOptions(); scope.cancel(); store.close(); staging.deleteRecursively() }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    /** [sound]: wait for the sound's own outcome too (a job row or the notice); it may land after the photos. */
    private fun settle(sound: Boolean = true) = runBlocking {
        withTimeout(10_000) { saver.awaitIdle() }
        withTimeout(5_000) { while (vm.imageSaving || (sound && store.all().isEmpty() && notices.get() == 0)) { pump(); Thread.sleep(5) } }
        pump()
    }

    @Test fun soundStartsUntickedAndOnlyATickedChoiceEnablesSave() {
        assertFalse(vm.soundSelected)
        assertTrue(vm.imageSaveEnabled)
        vm.selectAllImages(false)
        assertFalse(vm.imageSaveEnabled)
        vm.toggleSound()
        assertTrue(vm.soundSelected)
        assertTrue(vm.imageSaveEnabled)
        vm.toggleSound()
        assertFalse(vm.imageSaveEnabled)
        assertTrue(reads.isEmpty(), "no read before Save")
    }

    @Test fun photosOnlyNeverReadsTheSound() {
        vm.saveImage(); settle(sound = false)
        assertEquals(listOf("Post_1.jpg", "Post_2.jpg"), publisher.names)
        assertTrue(reads.isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(0, notices.get())
    }

    @Test fun bothSavesThePhotosAndEnqueuesOneJobWithTheReturnedM4a() {
        vm.toggleSound()
        vm.saveImage(); settle()
        assertEquals(listOf("Post_1.jpg", "Post_2.jpg"), publisher.names)
        assertEquals(listOf(video), reads)
        val job = store.all().single()
        assertEquals("audio", job.format.id)
        assertEquals("m4a", job.format.container)
        assertEquals(Size.Unknown, job.format.size)
        assertEquals(video, job.sourceUrl)
        assertEquals(0, notices.get())
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun saveOnlySoundOnACarouselLeavesEveryPhotoUnsaved() {
        assertEquals(2, (vm.optionsState as OptionsState.ImageReady).photos.size)
        assertEquals(2, vm.selectedImageRefs.size)
        assertTrue(reads.isEmpty()); assertTrue(store.all().isEmpty())
        vm.savePostSound(); settle()
        assertTrue(publisher.names.isEmpty())
        assertEquals(listOf(video), reads)
        assertEquals("audio", store.all().single().format.id)
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun soundOnlyEnqueuesTheAudioAndClosesTheSheet() {
        vm.selectAllImages(false)
        vm.toggleSound()
        vm.saveImage(); settle()
        assertTrue(publisher.names.isEmpty())
        val job = store.all().single()
        assertEquals("audio", job.format.id)
        assertEquals(job.jobId, vm.acceptedJob)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertEquals("", vm.text)
    }

    @Test fun noAudioOnlyRowOrAFailedReadStillSavesThePhotosAndTellsOnce() {
        for (returned in listOf<(LinkRevision) -> InspectResult>(
            { InspectResult.Success(Inspection(it, "fixture-v1", listOf(Format("720", "mp4", true, true, height = 720)))) },
            { InspectResult.Failure(DownloadError.Network) })) {
            result = returned
            vm.toggleSound()
            vm.saveImage(); settle()
            assertEquals(listOf("Post_1.jpg", "Post_2.jpg"), publisher.names)
            assertTrue(store.all().isEmpty(), "never an invented format")
            assertEquals(1, notices.get())
            publisher.names.clear(); notices.set(0); reads.clear()
            vm.edit(photo); vm.checkLink(); pump()
        }
    }

    @Test fun soundOnlyFailureKeepsTheSheetAndTellsOnce() {
        result = { InspectResult.Failure(DownloadError.Network) }
        vm.selectAllImages(false)
        vm.toggleSound()
        vm.saveImage(); settle()
        assertIs<OptionsState.ImageReady>(vm.optionsState)
        assertFalse(vm.soundSelected)
        assertEquals(1, notices.get())
        assertTrue(store.all().isEmpty())
    }
}
