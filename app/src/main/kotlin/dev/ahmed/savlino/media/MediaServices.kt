package dev.ahmed.savlino.media

import android.content.Context
import android.os.Environment
import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.engine.LegacyMediaInspectionAdapter
import dev.ahmed.savlino.engine.MediaSourceInspector
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.mediahttp.MediaHttpClient
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.storage.DestinationPublisher
import dev.ahmed.savlino.storage.SaveDestination
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.storage.SpaceGate
import dev.ahmed.savlino.storage.SpaceBudget
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Process-wide image services. One HTTP client and one memory-only reference store are shared by the
 * inspector and the Save transfer; nothing is created until an image link or Save needs it.
 */
class MediaServices(context: Context, engine: () -> EngineAdapter, scope: CoroutineScope) {
    private val http by lazy { MediaHttpClient.create() }
    private val references by lazy { MediaReferenceStore() }
    private val inspector by lazy {
        MediaSourceInspector(PublicImageInspector(http, AndroidImagePreviewDecoder(), references), LegacyMediaInspectionAdapter(engine()))
    }
    val inspect: ImageInspect = object : ImageInspect {
        override suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute) =
            inspector.inspect(source, operationId, route)
        override fun cancel(operationId: String) { inspector.cancel(operationId) }
    }
    val saver: ImageSaver = run {
        val destinations = SaveDestinations(context)
        val staging = File(context.noBackupFilesDir, "image-save")
        val space = SpaceBudget(staging, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        // A SAF provider has no queryable volume; private staging still needs a bounded preflight.
        val privateSpace = SpaceBudget(staging, staging)
        val preflight = object : SpaceGate by space {
            override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) {
                if (destinations.destination is SaveDestination.Default)
                    space.beforeTransfer(stagingBytes, outputBytes, unknownSizeUpperBound)
                else privateSpace.beforeTransfer(stagingBytes, outputBytes, unknownSizeUpperBound)
            }
        }
        ImageSaver(TransferFetch { ImageSaveTransfer(http, references) },
            DestinationPublisher(context, space, destinations), staging, preflight, scope,
            pendingImages = { ownPendingImages(context.contentResolver, context.packageName) + destinations.pendingImages() })
    }
}
