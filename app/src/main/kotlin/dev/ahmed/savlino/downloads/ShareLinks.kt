package dev.ahmed.savlino.downloads

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.jobs.Reopen
import dev.ahmed.savlino.storage.SaveDestinations
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copy link for a saved file, and the sweep that forgets a link once its file is confirmed gone. All IO runs on [io]. */
class ShareLinks internal constructor(
    private val store: JobStore,
    private val gone: (Uri) -> Boolean?,
    private val io: CoroutineDispatcher,
    private val onGone: (String) -> Unit,
) {
    constructor(resolver: ContentResolver, store: JobStore, onGone: (String) -> Unit) :
        this(store, FileProbe(resolver)::gone, Dispatchers.IO, onGone)

    /** The reviewed link of [item], or null. Never throws: a failure only hides Copy link. */
    suspend fun link(item: SavedMedia): String? = withContext(io) {
        try { store.linkedFiles().firstOrNull { matches(it.published, item) }?.let { store.shareLink(it.jobId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** The job behind [item] when its link is still kept (same lookup as [link]), or null. Never throws. */
    suspend fun reopen(item: SavedMedia): Pair<String, Reopen>? = withContext(io) {
        try { store.linkedFiles().firstOrNull { matches(it.published, item) }?.let { file -> store.reopen(file.jobId)?.let { file.jobId to it } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /**
     * Absence from the list only selects candidates; each one is confirmed at its own URI.
     * Rows finished at or after [listedAt] may be newer than the list and are skipped.
     */
    suspend fun sweep(rows: List<SavedMedia>, listedAt: Long): Unit = withContext(io) {
        val candidates = try { store.linkedFiles() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withContext }
        for (file in candidates) {
            if (file.updatedAt >= listedAt || rows.any { matches(file.published, it) }) continue
            if (runCatching { gone(file.published) }.getOrNull() == true) {
                try { store.forgetFile(file.jobId) } catch (_: Exception) { continue }
                onGone(file.jobId)
            }
        }
    }

    internal companion object {
        /** MediaStore: same volume and `_id` (ids repeat across volumes). Folder documents: the exact URI. */
        fun matches(published: Uri, item: SavedMedia): Boolean =
            if (published.authority == MediaStore.AUTHORITY) item.uri.authority == MediaStore.AUTHORITY &&
                item.volumeName != null && published.pathSegments.firstOrNull() == item.volumeName &&
                published.lastPathSegment == item.uri.lastPathSegment
            else published == item.uri
    }
}

/** true = confirmed absent at its URI, false = still there, null = unknown (keep the link). */
internal class FileProbe(private val resolver: ContentResolver) {
    fun gone(uri: Uri): Boolean? = try {
        if (uri.authority == MediaStore.AUTHORITY) {
            // A trashed file can come back: it is still there.
            val args = Bundle().apply {
                if (Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count == 0 }
        } else document(uri)
    } catch (_: Exception) { null }

    private fun document(uri: Uri): Boolean? {
        val tree = SaveDestinations.treeOf(uri)
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) return null
        // DocumentsProvider answers a missing document with a null cursor; only a reachable folder makes that proof.
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), Bundle.EMPTY, null)
                ?.use { it.moveToFirst() } != true) return null
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), Bundle.EMPTY, null)?.use { it.count == 0 } ?: true
        } catch (error: Exception) {
            if (generateSequence<Throwable>(error) { it.cause }.any { it is FileNotFoundException }) true else null
        }
    }
}
