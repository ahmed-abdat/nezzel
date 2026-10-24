package dev.ahmed.savlino.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.util.LruCache
import dev.ahmed.savlino.downloads.SavedMedia
import org.json.JSONObject

sealed interface SaveDestination {
    data object Default : SaveDestination
    // Deliberately no generated toString containing a device-specific URI.
    class Tree(val uri: Uri) : SaveDestination
}

/** Device-local selection and ownership receipts. Excluded from both backup and device transfer. */
class SaveDestinations(private val context: Context) {
    internal val prefs: SharedPreferences = context.getSharedPreferences("nezzel-destinations", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver
    internal data class FileBounds(val size: Long?, val modified: Long?, val mime: String,
        val width: Int? = null, val height: Int? = null, val durationMillis: Long? = null)
    // Found old downloads share these probes, so the cache holds more than the receipts alone.
    private val boundsCache = LruCache<Uri, FileBounds>(2048)
    private class FileSnapshot(val media: SavedMedia?)
    private val fileCache = LruCache<Uri, FileSnapshot>(512)
    val destination: SaveDestination get() = prefs.getString("tree", null)?.let { SaveDestination.Tree(Uri.parse(it)) }
        ?: SaveDestination.Default
    val displayName: String get() = prefs.getString("name", null) ?: context.getString(dev.ahmed.savlino.R.string.save_path)

    /** Acquire and durably store the new grant and retain grants while receipts reference them. A cancelled picker never calls this. */
    fun choose(uri: Uri?) = synchronized(lock) {
        val old = (destination as? SaveDestination.Tree)?.uri
        if (uri != null) {
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
            resolver.takePersistableUriPermission(uri, FLAGS)
        }
        try {
            val name = uri?.let { folderName(it) }
            check(prefs.edit().putString("tree", uri?.toString()).putString("name", name).commit())
        } catch (failure: Exception) {
            if (uri != null && uri != old) releaseIfUnused(uri)
            throw failure
        }
        if (old != null && old != uri) releaseIfUnused(old)
    }

    fun available(): Boolean = try {
        when (val chosen = destination) {
            SaveDestination.Default -> true
            is SaveDestination.Tree -> { requireGrant(chosen.uri); folderName(chosen.uri); true }
        }
    } catch (_: Exception) { false }

    internal fun requireGrant(tree: Uri) {
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission })
            throw StorageException(StorageError.FolderUnavailable)
    }
    internal fun folderName(tree: Uri, cancellation: CancellationSignal? = null): String {
        requireGrant(tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS), android.os.Bundle.EMPTY, cancellation)?.use {
            if (!it.moveToFirst() || it.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR ||
                it.getInt(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0)
                throw StorageException(StorageError.FolderUnavailable)
            it.getString(0) ?: throw StorageException(StorageError.FolderUnavailable)
        } ?: throw StorageException(StorageError.FolderUnavailable)
    }
    /** The save folder or a receipt still needs this tree's grant. */
    internal fun holds(tree: Uri): Boolean = synchronized(lock) {
        (destination as? SaveDestination.Tree)?.uri == tree || receipts().any { treeOf(it.first) == tree }
    }
    private fun releaseIfUnused(uri: Uri) {
        if ((destination as? SaveDestination.Tree)?.uri == uri || receipts().any { treeOf(it.first) == uri }) return
        // Old downloads may hold the same Download/Nezzel tree.
        if (OldDownloads(context).holds(uri)) return
        try { resolver.releasePersistableUriPermission(uri, FLAGS) } catch (_: SecurityException) { /* Already revoked. */ }
    }

    internal fun receipt(uri: Uri): JSONObject? = prefs.getString("file:$uri", null)?.let(::JSONObject)
    internal fun record(uri: Uri, name: String, mime: String, published: Boolean, folderName: String? = null) = synchronized(lock) {
        val record = (if (published) receipt(uri) else null) ?: JSONObject()
            .put("nonce", java.util.UUID.randomUUID().toString())
            .put("name", name).put("mime", mime).put("time", System.currentTimeMillis() / 1000)
        record.put("published", published)
        if (folderName != null) record.put("folder", folderName)
        check(prefs.edit().putString("file:$uri", record.toString()).commit())
    }
    /** After create/copy (including a failed copy), remember the document we may later delete. */
    internal fun recordPendingState(uri: Uri) {
        val nonce = synchronized(lock) { receipt(uri)?.optString("nonce") } ?: return
        val state = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED), android.os.Bundle.EMPTY, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun value(column: Int) = if (cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
            value(0) to value(1)?.takeIf { it > 0 }
        } ?: throw StorageException(StorageError.FolderUnavailable)
        synchronized(lock) {
            val record = receipt(uri)?.takeUnless { it.getBoolean("published") || it.optString("nonce") != nonce } ?: return
            record.put("cleanupSize", state.first)
            record.put("cleanupModified", state.second)
            check(prefs.edit().putString("file:$uri", record.toString()).commit())
        }
    }

    fun publishedFolderName(uri: Uri?): String = uri?.takeIf(DocumentsContract::isTreeUri)
        ?.let { receipt(it)?.optString("folder")?.takeIf(String::isNotBlank) }
        ?: context.getString(dev.ahmed.savlino.R.string.save_path)

    internal fun forget(uri: Uri) = synchronized(lock) {
        check(prefs.edit().remove("file:$uri").commit())
        boundsCache.remove(uri)
        fileCache.remove(uri)
        releaseIfUnused(treeOf(uri))
    }
    internal fun pendingImages(): List<Uri> = receipts().filter { (_, r) -> !r.getBoolean("published") && r.getString("mime").startsWith("image/") }.map { it.first }
    internal fun pendingJobFiles(): List<Uri> = receipts().filter { (_, r) ->
        !r.getBoolean("published") && !r.getString("mime").startsWith("image/")
    }.map { it.first }
    private fun receipts() = prefs.all.keys.filter { it.startsWith("file:") }.mapNotNull { key ->
        val uri = Uri.parse(key.removePrefix("file:")); receipt(uri)?.let { uri to it }
    }
    internal fun publishedUris(): List<Uri> = receipts().filter { it.second.getBoolean("published") }.map { it.first }

    /** Receipt metadata is already local. Never query a provider or probe dimensions for the first emission. */
    internal fun recordedFiles(): List<SavedMedia> = receipts().mapNotNull { (uri, record) ->
        if (!record.getBoolean("published")) return@mapNotNull null
        fileCache.get(uri)?.let { return@mapNotNull it.media }
        val name = record.getString("name")
        SavedMedia(uri, name, name.substringBeforeLast('.').ifBlank { name }, record.getString("mime"),
            null, record.getLong("time"), null, null, null)
    }

    /** Blocking explicit verification; observation reconciles each URI separately after its first emission. */
    @androidx.annotation.WorkerThread
    fun savedFiles(): List<SavedMedia> = publishedUris().mapNotNull { savedFile(it, CancellationSignal()) }

    @androidx.annotation.WorkerThread
    internal fun savedFile(uri: Uri, cancellation: CancellationSignal): SavedMedia? {
        val record = receipt(uri)?.takeIf { it.getBoolean("published") } ?: return null
        cancellation.throwIfCanceled()
        val media = try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED), android.os.Bundle.EMPTY, cancellation)?.use {
                if (!it.moveToFirst()) return@use null
                val name = it.getString(0) ?: record.getString("name")
                val size = if (it.isNull(1)) null else it.getLong(1).takeIf { n -> n >= 0 }
                val modified = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { column -> column >= 0 && !it.isNull(column) }?.let(it::getLong)
                val mime = record.getString("mime")
                cancellation.throwIfCanceled()
                val bounds = fileBounds(uri, size, modified, mime)
                SavedMedia(uri, name, name.substringBeforeLast('.').ifBlank { name }, mime, size,
                    record.getLong("time"), bounds.height, null, bounds.durationMillis, width = bounds.width)
            }
        } catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Unavailability is not proof of absence: keep the receipt/grant.
        cancellation.throwIfCanceled()
        fileCache.put(uri, FileSnapshot(media))
        return media
    }
    /** Cache only, no IO: found files fill in from earlier probes on the first emission. */
    internal fun cachedBounds(uri: Uri, size: Long?, modified: Long?, mime: String): FileBounds? =
        boundsCache.get(uri)?.takeIf { it.size == size && it.modified == modified && it.mime == mime }

    internal fun fileBounds(uri: Uri, size: Long?, modified: Long?, mime: String): FileBounds {
        cachedBounds(uri, size, modified, mime)?.let { return it }
        var bounds = FileBounds(size, modified, mime)
        try {
            if (mime.startsWith("image/")) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                bounds = bounds.copy(width = options.outWidth.takeIf { it > 0 }, height = options.outHeight.takeIf { it > 0 })
            } else if (mime.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    fun key(k: Int) = retriever.extractMetadata(k)?.toLongOrNull()?.takeIf { it > 0 }
                    bounds = bounds.copy(
                        width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt(),
                        height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt(),
                        durationMillis = key(MediaMetadataRetriever.METADATA_KEY_DURATION))
                } finally { retriever.release() }
            }
        } catch (_: Exception) { /* Metadata is optional; retain the accessible file with unknown dimensions. */ }
        boundsCache.put(uri, bounds)
        return bounds
    }
    companion object {
        internal val lock = Any()
        private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        internal fun treeOf(document: Uri): Uri = DocumentsContract.buildTreeDocumentUri(document.authority!!, DocumentsContract.getTreeDocumentId(document))
    }
}
