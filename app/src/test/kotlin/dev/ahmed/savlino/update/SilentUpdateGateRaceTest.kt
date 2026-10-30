package dev.ahmed.savlino.update

import android.app.Activity
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImageSaveResult
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.media.ImageFetch
import dev.ahmed.savlino.media.ImageSaver
import dev.ahmed.savlino.storage.DestinationPublisher
import dev.ahmed.savlino.ui.noSpaceCheck
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class GateFlipApplication : NezzelApplication() {
    var whenDownloadsRead: () -> Unit = {}
    var imageSaverOverride: ImageSaver? = null
    override val imageSaver get() = imageSaverOverride ?: super.imageSaver
    override fun onCreate() = Unit
    override val downloads: DownloadCoordinator get() {
        whenDownloadsRead()
        return super.downloads
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = GateFlipApplication::class)
class SilentUpdateGateRaceTest {
    @Test fun acceptedStatusQueueDefersInstallationBeforeDispatchAndBetweenItems() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val updates = AppUpdates(app, this) { error("No network") }
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val owner = CoroutineScope(SupervisorJob() + java.util.concurrent.Executor { queued += it }.asCoroutineDispatcher())
        val items = listOf(dev.ahmed.savlino.statuses.FakeStatusLibrary.item(1, false),
            dev.ahmed.savlino.statuses.FakeStatusLibrary.item(2, false))
        val library = dev.ahmed.savlino.statuses.FakeStatusLibrary().apply {
            gate = { assertFalse(updates.silentInstallAllowed(verified = true)) }
        }
        try {
            dev.ahmed.savlino.statuses.ui.StatusesState(library, owner).save(items)
            assertTrue(library.saved.isEmpty())
            assertFalse(updates.silentInstallAllowed(verified = true))
            withTimeout(5000) {
                while (owner.coroutineContext[Job]!!.children.any()) {
                    queued.poll()?.run() ?: yield()
                }
            }
            assertEquals(items, library.saved)
            assertTrue(updates.silentInstallAllowed(verified = true))
        } finally {
            owner.cancel()
            withTimeout(5000) {
                while (owner.coroutineContext[Job]!!.children.any()) { queued.poll()?.run() ?: yield() }
            }
        }
    }

    @Test fun imageSaveDefersSilentInstallUntilItsCleanupFinishes() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saver = ImageSaver(ImageFetch { _, _ ->
            entered.complete(Unit)
            release.await()
            ImageSaveResult.Failure(ImageFailure.CANCELLED)
        }, DestinationPublisher(app, noSpaceCheck), File(app.noBackupFilesDir, "save-test"), noSpaceCheck, owner)
        app.imageSaverOverride = saver
        try {
            val selection = MediaSelection(LinkRevision(LinkInput.parse("https://example.org/photo")!!, 1),
                MediaAdapterIdentity("public-image", 1, "android-image-1", 1),
                MediaRenditionRef(MediaItemRef("public-image", "item"), "rendition"), MediaKind.IMAGE,
                MediaInspectionValidity.processLocal())
            assertTrue(saver.save(selection, MediaImageMetadata(MediaImageFormat.JPEG), "Photo"))
            withTimeout(5000) { entered.await() }
            val updates = AppUpdates(app, this) { error("No network") }
            assertFalse(updates.silentInstallAllowed(verified = true))
            release.complete(Unit)
            saver.awaitIdle()
            assertTrue(updates.silentInstallAllowed(verified = true))
        } finally {
            release.complete(Unit)
            saver.awaitIdle()
            app.imageSaverOverride = null
            owner.cancel()
        }
    }
    @Test fun foregroundEntryDuringDownloadCheckStillDefersCommit() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val updates = AppUpdates(app, this) { error("No network") }
        app.whenDownloadsRead = { app.visibility.onActivityStarted(Activity()) }
        assertFalse(updates.silentInstallAllowed(verified = true))
    }
    @Test fun switchOffDuringDownloadCheckStillDefersCommit() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GateFlipApplication
        val updates = AppUpdates(app, this) { error("No network") }
        app.whenDownloadsRead = { app.preferences.appUpdates = false }
        assertFalse(updates.silentInstallAllowed(verified = true))
    }
}
