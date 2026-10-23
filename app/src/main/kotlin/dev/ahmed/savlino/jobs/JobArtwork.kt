package dev.ahmed.savlino.jobs

import android.graphics.Bitmap
import android.util.LruCache
import dev.ahmed.savlino.preview.PreviewArtworkCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Job row artwork: a small square copy of the thumbnail the options sheet already showed, at filesDir/job-art/<jobId>.
 * Never fetched here and never keyed by URL; a job without one shows its kind icon.
 */
class JobArtwork internal constructor(filesDir: File, private val codec: PreviewArtworkCodec) {
    constructor(filesDir: File) : this(filesDir, PreviewArtworkCodec())

    private val root = File(filesDir.canonicalFile, "job-art")
    private val memory = LruCache<String, Bitmap>(MEMORY_ENTRIES)

    /** IO. Null when the sheet had no artwork, the file is gone or does not decode. */
    suspend fun load(jobId: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!JobStore.validJobId(jobId)) return@withContext null
        memory.get(jobId)?.let { return@withContext it }
        val bytes = try { File(root, jobId).takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes() }
            catch (_: Exception) { null } ?: return@withContext null
        codec.decode(bytes, SIDE, SIDE.toLong() * SIDE * 4)?.also { memory.put(jobId, it) }
    }

    /** Atomic write of already-capped bytes; false leaves no file. */
    internal fun save(jobId: String, bytes: ByteArray): Boolean = synchronized(this) {
        if (!JobStore.validJobId(jobId) || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val temp = File(root, "$jobId.tmp")
        try {
            root.mkdirs()
            temp.writeBytes(bytes)
            temp.renameTo(File(root, jobId)) || run { temp.delete(); false }
        } catch (_: Exception) { temp.delete(); false }
    }

    fun delete(jobId: String): Unit = synchronized(this) {
        if (!JobStore.validJobId(jobId)) return
        memory.remove(jobId)
        File(root, jobId).delete()
    }

    /** After the 100-row prune: drop artwork of every job the store no longer has. */
    internal fun retainOnly(jobIds: Set<String>): Unit = synchronized(this) {
        root.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".tmp")
            if (id !in jobIds || file.name.endsWith(".tmp")) { memory.remove(id); file.delete() }
        }
    }

    fun clear(): Unit = synchronized(this) {
        memory.evictAll()
        root.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val SIDE = 160
        const val MAX_BYTES = 30 * 1024
        private const val MEMORY_ENTRIES = 20

        /** Center square crop, ≤[SIDE] px, JPEG ≤[MAX_BYTES]. The caller keeps [bitmap]. */
        internal fun encode(bitmap: Bitmap, codec: PreviewArtworkCodec = PreviewArtworkCodec()): ByteArray? {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
            val side = minOf(bitmap.width, bitmap.height)
            val square = try { Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side) }
                catch (_: Exception) { return null }
            try {
                for (quality in listOf(85, 70, 50)) {
                    val encoded = codec.encodeSampled(square, SIDE, MAX_BYTES, Bitmap.CompressFormat.JPEG, quality) ?: continue
                    encoded.pixels.recycle()
                    return encoded.bytes
                }
                return null
            } finally { if (square !== bitmap) square.recycle() }
        }
    }
}

/**
 * Memory-only hand-off from the options sheet to [DownloadCoordinator.enqueue], keyed by inspection id.
 * Holds encoded bytes for the last two inspections only; nothing reaches disk until a download is confirmed.
 */
object SheetArtwork {
    private val pending = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 2
    }

    /** Encodes now (call off main); the sheet may drop its bitmap afterwards. */
    fun offer(inspectionId: String, bitmap: Bitmap) {
        val bytes = JobArtwork.encode(bitmap) ?: return
        synchronized(pending) { pending[inspectionId] = bytes }
    }

    /** Kept (not removed) so a second format from the same sheet also gets artwork; the two-entry cap bounds it. */
    internal fun take(inspectionId: String): ByteArray? = synchronized(pending) { pending[inspectionId] }

    internal fun clear() = synchronized(pending) { pending.clear() }
}
