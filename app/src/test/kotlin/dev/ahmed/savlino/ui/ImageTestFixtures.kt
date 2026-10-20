package dev.ahmed.savlino.ui

import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.media.ImageFetch
import dev.ahmed.savlino.media.ImageInspect
import dev.ahmed.savlino.storage.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

internal val imageIdentity = MediaAdapterIdentity("public-image", 1, "android-image-1", 1)
internal val imageFixture = ByteArray(2048) { (it * 7).toByte() }

/** App-side copy of a preview, as legacy-graphics Robolectric can allocate it. */
internal fun testBitmap(preview: OwnedImagePreview) =
    android.graphics.Bitmap.createBitmap(preview.width, preview.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

internal class TestPreview(override val width: Int = 640, override val height: Int = 480) : OwnedImagePreview {
    override val allocationBytes = width * height * 4L
    override var isClosed = false
    override fun close() { isClosed = true }
}

/** Validated synthetic still images; every rendition is returned, never inferred by UI. */
internal fun imageSuccess(source: LinkRevision, preview: TestPreview = TestPreview(), found: Int = 1,
    availability: ImageSaveAvailability = ImageSaveAvailability.Available, previewQuality: Boolean = false,
    metadata: MediaMetadata = MediaMetadata(), hasVideo: Boolean = false): MediaInspectResult.Success {
    val item = MediaItemRef(imageIdentity.adapterId, "item-0")
    val rendition = MediaRenditionRef(item, "rendition-0")
    val image = MediaImageMetadata(MediaImageFormat.JPEG, Size.Exact(imageFixture.size.toLong()), 1080, 1350,
        MediaImageProof("b".repeat(64), imageFixture.size.toLong()))
    val items = (0 until found).map {
        val ref = MediaItemRef(imageIdentity.adapterId, "item-$it")
        MediaItem(ref, it, MediaKind.IMAGE, metadata,
            listOf(MediaRendition.Image(MediaRenditionRef(ref, "rendition-$it"), image)))
    }
    val inspection = MediaInspection(source, imageIdentity, items, MediaInspectionValidity.processLocal())
    return MediaInspectResult.Success(inspection, preview, ImageDiscovery(found, DiscoveryCompleteness.COMPLETE, previewQuality = previewQuality, hasVideo = hasVideo), availability,
        items.associate { it.renditions.single().ref to preview }) { preview.close() }
}

/** Records every image inspection; a [gate] holds the result until released (cancellation is recorded, not thrown). */
internal class FakeImages(var result: (LinkRevision) -> MediaInspectResult = { imageSuccess(it) }) : ImageInspect {
    val routes = mutableListOf<MediaSourceRoute>()
    val cancelled = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    /** Like the engine's blocking IO: once started it finishes even if the caller is cancelled. */
    var nonCancellable = false
    override suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute): MediaInspectResult {
        routes += route
        gate?.let { if (nonCancellable) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { it.await() } else it.await() }
        return result(source)
    }
    override fun cancel(operationId: String) { cancelled += operationId }
}

internal class FakeFetch(var gate: CompletableDeferred<Unit>? = null, var failure: ImageSaveResult.Failure? = null, var failureAt: Int? = null) : ImageFetch {
    val calls = AtomicInteger()
    val selections = java.util.Collections.synchronizedList(mutableListOf<MediaSelection>())
    override suspend fun transfer(selection: MediaSelection, sink: java.io.OutputStream): ImageSaveResult {
        calls.incrementAndGet()
        selections += selection
        gate?.await()
        failure?.takeIf { failureAt == null || calls.get() == failureAt }?.let { return it }
        sink.write(imageFixture)
        return ImageSaveResult.Success(MediaImageMetadata(MediaImageFormat.JPEG, Size.Exact(imageFixture.size.toLong()), 1080, 1350,
            MediaImageProof("b".repeat(64), imageFixture.size.toLong())))
    }
}

/** Records publications without a MediaStore; the published URI is a fixed own-download row. */
internal class RecordingPublisher : Publisher {
    val published = AtomicInteger()
    val names = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun no(): Nothing = throw StorageException(StorageError.SaveFailed)
    override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String) = no()
    override fun copy(pending: PendingPublication, source: File, cancellation: android.os.CancellationSignal) = no()
    override fun commit(pending: PendingPublication) = no()
    override fun recover(uri: Uri) = no()
    override fun abandon(uri: Uri) = no()
    override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
        cancellation: android.os.CancellationSignal): PublicationResult {
        job.requireOutput(stagedFile)
        names += dev.ahmed.savlino.domain.FilenameSanitizer.sanitize(title, extension)
        published.incrementAndGet()
        job.cleanup()
        // Same naming as MediaStorePublisher: the display name is always FilenameSanitizer output.
        return PublishedFile(Uri.parse("content://media/external_primary/downloads/7"),
            dev.ahmed.savlino.domain.FilenameSanitizer.sanitize(title, extension))
    }
}

internal val noSpaceCheck = object : SpaceGate {
    override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) = Unit
    override fun beforePublication(outputBytes: Long) = Unit
}
