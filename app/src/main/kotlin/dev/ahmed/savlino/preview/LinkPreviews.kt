package dev.ahmed.savlino.preview

import android.graphics.Bitmap
import dev.ahmed.savlino.domain.Inspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Display-only seam for link screens. Tokens, identity, disk work and serialized artwork writes stay here. */
class LinkPreviews internal constructor(
    private val cache: () -> PreviewCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal suspend fun open(normalizedSource: String): Session = withContext(dispatcher) {
        val owner = try { cache() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        Session(owner, owner?.token(), normalizedSource)
    }

    internal inner class Session(
        private val owner: PreviewCache?,
        private val token: PreviewCacheToken?,
        private val source: String,
    ) {
        private val writes = Mutex()
        private var artwork: Bitmap? = null
        suspend fun read(runtime: suspend () -> String?): CachedPreview? = withContext(dispatcher) {
            val owner = owner ?: return@withContext null
            val token = token ?: return@withContext null
            val version = try { runtime()?.takeIf { it.isNotBlank() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (version == null) return@withContext null
            // Native reader inspections may report an adapter version distinct from the installed engine runtime.
            // Such writes deliberately miss this runtime key; reuse requires an exact identity, never a guessed match.
            owner.get(owner.key(token, source, RECIPE, version), token)
        }
        suspend fun write(inspection: Inspection, pixels: Bitmap? = null) = withContext(dispatcher) {
            val owner = owner ?: return@withContext false
            val token = token ?: return@withContext false
            writes.withLock {
                // A metadata write racing a drawn image must not erase that same inspection's artwork.
                if (pixels != null) artwork = pixels
                owner.put(owner.key(token, source, RECIPE, inspection.engineVersion), token,
                    inspection.title, inspection.uploader, inspection.durationSeconds, artwork)
            }
        }
    }

    private companion object {
        // Bump when the legacy inspection-to-header mapping changes; runtime upgrades have their own key part.
        const val RECIPE = "legacy-inspection-header-v1"
    }
}
