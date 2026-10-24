package dev.ahmed.savlino.media

import android.content.Context
import android.os.Environment
import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.engine.LegacyMediaInspectionAdapter
import dev.ahmed.savlino.engine.MediaSourceInspector
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.mediahttp.MediaHttpClient
import dev.ahmed.savlino.engine.mediahttp.ShortLinkResolver
import dev.ahmed.savlino.engine.mediahttp.YouTubeOEmbed
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.storage.DestinationPublisher
import dev.ahmed.savlino.storage.SaveDestination
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.storage.SpaceGate
import dev.ahmed.savlino.storage.SpaceBudget
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Process-wide media services. The guarded HTTP client serves image inspection/Save and optional
 * YouTube metadata; references remain memory-only. Services are created only when authorized work needs them.
 */
class MediaServices(context: Context, engine: () -> EngineAdapter, scope: CoroutineScope) {
    private val http by lazy { MediaHttpClient.create() }
    private val oembed by lazy { YouTubeOEmbed(http) }
    suspend fun earlyPreview(link: dev.ahmed.savlino.domain.ValidatedLink) = oembed.load(link.rawUrl)
    private val shortLinks by lazy { ShortLinkResolver(http) }
    suspend fun resolveShortLink(link: dev.ahmed.savlino.domain.ValidatedLink) = shortLinks.resolve(link)
    private val references by lazy { MediaReferenceStore() }
    private val inspector by lazy {
        MediaSourceInspector(PublicImageInspector(http, AndroidImagePreviewDecoder(), references), LegacyMediaInspectionAdapter(engine()))
    }
    val inspect: ImageInspect = object : ImageInspect {
        override suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute) =
            inspector.inspect(source, operationId, route)
        override fun cancel(operationId: String) { inspector.cancel(operationId) }
    }
    private val statusSaver = dev.ahmed.savlino.statuses.StatusSaver(context)
    /** Statuses tab storage: one READ-only folder grant; created on first use. */
    val statusLibrary: dev.ahmed.savlino.statuses.StatusLibrary by lazy {
        dev.ahmed.savlino.statuses.DocumentStatusLibrary.create(context, statusSaver)
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
        val publisher = DestinationPublisher(context, space, destinations)
        val statuses = statusSaver
        ImageSaver(TransferFetch { ImageSaveTransfer(http, references) },
            publisher, staging, preflight, scope,
            startupCleanup = { statuses.sweep() },
            abandonPending = { uri -> statuses.abandonIfStatus(uri) ?: publisher.abandon(uri) },
            pendingImages = { ownPendingImages(context.contentResolver, context.packageName) + destinations.pendingImages() })
    }
}
