package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.engine.mediahttp.MediaHttpFailure
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

enum class MediaSourceRoute { DIRECT_IMAGE, LINKEDIN, FACEBOOK, X, INSTAGRAM, TELEGRAM, BLUESKY, TIKTOK, REDDIT, PINTEREST, LEGACY }

/**
 * Additive, explicit routing facade; EngineAdapter and the audiovisual command path remain untouched.
 * Image failures stay typed image failures: there is no fallback from an image route to another client.
 */
class MediaSourceInspector(private val images: PublicImageInspector, private val legacy: LegacyMediaInspectionAdapter,
    private val imageIdentity: MediaAdapterIdentity = PUBLIC_IMAGE_IDENTITY) {
    private val operations = ConcurrentHashMap<String, ImageInspectionOperation>()

    suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute): MediaInspectResult {
        if (route == MediaSourceRoute.LEGACY) return legacy.inspect(source, operationId)
        var operation = images.newOperation(source, imageIdentity)
        // Two transport operations at most: the link just closed can hold its slot a moment longer. Wait for it
        // briefly instead of failing a fresh link as unreadable.
        var waited = 0L
        while (operation.token.checkActive() == MediaHttpFailure.BUSY && waited < BUSY_WAIT_MS) {
            delay(BUSY_POLL_MS); waited += BUSY_POLL_MS
            operation = images.newOperation(source, imageIdentity)
        }
        operations[operationId] = operation
        return try {
            images.inspect(operation, when (route) {
                MediaSourceRoute.LINKEDIN -> ImageSourceType.LINKEDIN
                MediaSourceRoute.FACEBOOK -> ImageSourceType.FACEBOOK
                MediaSourceRoute.X -> ImageSourceType.X
                MediaSourceRoute.INSTAGRAM -> ImageSourceType.INSTAGRAM
                MediaSourceRoute.TELEGRAM -> ImageSourceType.TELEGRAM
                MediaSourceRoute.BLUESKY -> ImageSourceType.BLUESKY
                MediaSourceRoute.TIKTOK -> ImageSourceType.TIKTOK
                MediaSourceRoute.REDDIT -> ImageSourceType.REDDIT
                MediaSourceRoute.PINTEREST -> ImageSourceType.PINTEREST
                else -> ImageSourceType.DIRECT
            })
        } finally { operations.remove(operationId, operation) }
    }

    fun cancel(operationId: String) {
        operations[operationId]?.let(images::cancel)
        legacy.cancel(operationId)
    }

    companion object {
        val PUBLIC_IMAGE_IDENTITY = MediaAdapterIdentity("public-image", 1, "android-image-1", 1)
        private const val BUSY_WAIT_MS = 3_000L
        private const val BUSY_POLL_MS = 100L
    }
}

/** Delegates to the existing adapter and keeps its original result; bridges only a genuinely returned item ID. */
class LegacyMediaInspectionAdapter(private val engine: EngineAdapter) {
    suspend fun inspect(source: LinkRevision, operationId: String, returnedItemId: String? = null,
        identity: MediaAdapterIdentity? = null): MediaInspectResult {
        val result = engine.inspect(source, operationId)
        val bridge = if (result is InspectResult.Success && returnedItemId != null && identity != null)
            runCatching { MediaLegacyBridge.wrap(result.inspection, returnedItemId, identity) }.getOrNull()
        else null
        return MediaInspectResult.Legacy(result, bridge)
    }

    fun cancel(operationId: String) = engine.cancel(operationId)
}
