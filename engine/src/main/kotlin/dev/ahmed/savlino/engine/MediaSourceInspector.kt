package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.util.concurrent.ConcurrentHashMap

enum class MediaSourceRoute { DIRECT_IMAGE, LINKEDIN, FACEBOOK, X, INSTAGRAM, TELEGRAM, BLUESKY, TIKTOK, LEGACY }

/**
 * Additive, explicit routing facade; EngineAdapter and the audiovisual command path remain untouched.
 * Image failures stay typed image failures: there is no fallback from an image route to another client.
 */
class MediaSourceInspector(private val images: PublicImageInspector, private val legacy: LegacyMediaInspectionAdapter,
    private val imageIdentity: MediaAdapterIdentity = PUBLIC_IMAGE_IDENTITY) {
    private val operations = ConcurrentHashMap<String, ImageInspectionOperation>()

    suspend fun inspect(source: LinkRevision, operationId: String, route: MediaSourceRoute): MediaInspectResult {
        if (route == MediaSourceRoute.LEGACY) return legacy.inspect(source, operationId)
        val operation = images.newOperation(source, imageIdentity)
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
