package dev.ahmed.savlino.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.statuses.StatusSaver
import java.io.File

/** What came back from the folder picker. */
enum class OldDownloadsPick { GRANTED, WRONG_FOLDER, CANCELLED }

/** A file listed through the grant; [modified] keys the shared metadata cache. */
internal class FoundFile(val media: SavedMedia, val modified: Long?)

/**
 * Files an earlier install saved in Download/Nezzel. After a reinstall Android treats them as another app's, so they
 * come back only through one persisted read+write tree grant of that folder (no broad media permission). The offer is
 * automatic, the grant never is. Own prefs, device-local (the app does not back up). Never logs names or URIs.
 */
class OldDownloads(private val context: Context, private val onFound: () -> Unit = {}) {
    internal val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    /** Pre-pointed at Download/Nezzel; the system screen asks "Use this folder" + "Allow". */
    fun grantIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, "primary:$FOLDER"))
        .addFlags(FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    /** Only Download/Nezzel itself is accepted; anything else is given back. */
    fun onPicked(tree: Uri?): OldDownloadsPick = synchronized(lock) {
        if (tree == null) return OldDownloadsPick.CANCELLED
        if (!isNezzelFolder(tree)) {
            releaseIfUnused(tree)
            return OldDownloadsPick.WRONG_FOLDER
        }
        try { resolver.takePersistableUriPermission(tree, FLAGS) } catch (_: SecurityException) { return OldDownloadsPick.WRONG_FOLDER }
        val previous = stored()
        val first = previous == null
        if (!prefs.edit().putString(TREE, tree.toString()).commit()) { releaseIfUnused(tree); return OldDownloadsPick.WRONG_FOLDER }
        // A new pick (another volume) replaces the old one; never leave an untracked grant behind.
        if (previous != null && previous != tree) releaseIfUnused(previous)
        if (first) onFound()
        OldDownloadsPick.GRANTED
    }

    /** The stored grant while the system still holds it; a dropped grant is forgotten quietly. */
    fun active(): Uri? = synchronized(lock) {
        val tree = stored() ?: return null
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            prefs.edit().remove(TREE).apply()
            return null
        }
        tree
    }

    val dismissed: Boolean get() = prefs.getBoolean(DISMISSED, false)
    fun dismiss() = prefs.edit().putBoolean(DISMISSED, true).apply()
    /** Decided once at the first app start; see [markFirstStart]. */
    val eligible: Boolean get() = prefs.getBoolean(ELIGIBLE, false)

    /** Shared by the Downloads card; the Settings row only needs no grant. */
    fun offerVisible(): Boolean = offer(eligible, active() != null, dismissed)

    /** Gives the grant back unless the save-folder feature uses the same tree. */
    fun forget() = synchronized(lock) {
        val tree = stored() ?: return
        check(prefs.edit().remove(TREE).commit())
        releaseIfUnused(tree)
    }

    fun holds(tree: Uri): Boolean = stored() == tree

    /**
     * One children query for the folder and one for its Statuses subfolder; no per-file probe here. A failed listing
     * (folder deleted, provider busy or restarting) lists nothing and keeps the grant: a null cursor can't tell "gone"
     * from "transient". Only the system dropping the permission ends it, see [active].
     */
    @androidx.annotation.WorkerThread
    internal fun list(cancellation: CancellationSignal? = null): List<FoundFile> = listOrNull(cancellation).orEmpty()

    /** As [list], but null when a query failed, so a caller can keep its last listing; no grant is a real empty list. */
    @androidx.annotation.WorkerThread
    internal fun listOrNull(cancellation: CancellationSignal? = null): List<FoundFile>? {
        val tree = active() ?: return emptyList()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val top = try { children(tree, root, cancellation) }
            catch (cancelled: OperationCanceledException) { throw cancelled }
            catch (_: Exception) { return null }
            ?: return null
        val statuses = top.firstOrNull { it.dir && it.name.equals(STATUSES, ignoreCase = true) }
        val nested = statuses?.let {
            try { children(tree, it.id, cancellation) }
            catch (cancelled: OperationCanceledException) { throw cancelled }
            catch (_: Exception) { null }
        }.orEmpty()
        return (top + nested).filter { !it.dir && it.mime in MIME_TYPES && !it.name.startsWith('.') }.take(CAP).map { child ->
            val relative = child.id.substringAfter(':').substringBeforeLast('/', "") + "/"
            FoundFile(SavedMedia(DocumentsContract.buildDocumentUriUsingTree(tree, child.id), child.name,
                child.name.substringBeforeLast('.').ifBlank { child.name }, child.mime, child.size,
                (child.modified ?: 0L) / 1000, null, null, null, relativePath = relative), child.modified)
        }
    }

    private class Child(val id: String, val name: String, val mime: String, val size: Long?, val modified: Long?) {
        val dir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(tree: Uri, parent: String, cancellation: CancellationSignal?): List<Child>? =
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), COLUMNS, android.os.Bundle.EMPTY, cancellation)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    cancellation?.throwIfCanceled()
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: continue
                    add(Child(id, name, mime, if (c.isNull(3)) null else c.getLong(3).takeIf { it >= 0 },
                        if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 }))
                }
            }
        }

    private fun stored(): Uri? = prefs.getString(TREE, null)?.let(Uri::parse)

    private fun releaseIfUnused(tree: Uri) {
        if (SaveDestinations(context).holds(tree)) return
        if (resolver.persistedUriPermissions.none { it.uri == tree }) return
        try { resolver.releasePersistableUriPermission(tree, FLAGS) } catch (_: SecurityException) { /* Already gone. */ }
    }

    companion object {
        internal const val PREFS = "nezzel-old-downloads"
        private const val TREE = "tree"
        private const val DISMISSED = "dismissed"
        private const val ELIGIBLE = "eligible"
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val FOLDER = "Download/Nezzel"
        private val STATUSES = StatusSaver.RELATIVE_PATH.trimEnd('/').substringAfterLast('/')
        private const val CAP = 2000
        private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val lock = Any()
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        /** The same types the MediaStore listing shows. */
        internal val MIME_TYPES = setOf("video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "audio/mp4", "audio/x-m4a",
            "audio/mpeg", "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")

        /** Card rule: a fresh install, no grant yet, not dismissed. A revoked grant brings it back. */
        fun offer(eligible: Boolean, granted: Boolean, dismissed: Boolean) = eligible && !granted && !dismissed

        /** Download/Nezzel on any volume, nothing above or below it. */
        fun isNezzelFolder(tree: Uri): Boolean = try {
            tree.authority == AUTHORITY && DocumentsContract.isTreeUri(tree) &&
                DocumentsContract.getTreeDocumentId(tree).substringAfter(':', "").trimEnd('/').equals(FOLDER, ignoreCase = true)
        } catch (_: IllegalArgumentException) { false }

        /**
         * Runs first in Application.onCreate. The first start of this install decides eligibility once: no settings,
         * job database or save-folder receipts from an earlier run means a fresh install.
         */
        fun markFirstStart(context: Context, earlier: () -> Boolean = {
            earlierRun(File(context.applicationInfo.dataDir, "shared_prefs"),
                context.getDatabasePath(dev.ahmed.savlino.jobs.JobStore.DATABASE_NAME))
        }) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(ELIGIBLE)) return
            prefs.edit().putBoolean(ELIGIBLE, !earlier()).apply()
        }

        internal fun earlierRun(sharedPrefs: File, jobsDatabase: File): Boolean =
            listOf("nezzel", "nezzel-destinations").any { File(sharedPrefs, "$it.xml").exists() } || jobsDatabase.exists()

        /** Hides found files that the MediaStore listing or a save-folder receipt already shows. */
        internal fun dedupe(found: List<FoundFile>, shown: List<SavedMedia>): List<FoundFile> {
            // A folder belongs to a concrete volume; identical names on internal storage and SD are different files.
            fun volume(value: String?): String? = when (val normalized = value?.lowercase()) {
                null, "", "external", "internal" -> null
                "primary" -> "external_primary"
                else -> normalized
            }
            fun key(volume: String, file: SavedMedia) = Triple(volume, file.relativePath?.trimEnd('/')?.lowercase(), file.filename)
            val media = shown.filter { it.uri.authority == "media" }.mapNotNull { file ->
                volume(file.volumeName ?: file.uri.pathSegments.firstOrNull())?.let { key(it, file) }
            }.toSet()
            val documents = shown.filter { it.uri.authority == AUTHORITY }.mapNotNull {
                try { DocumentsContract.getDocumentId(it.uri) } catch (_: IllegalArgumentException) { null }
            }.toSet()
            return found.filter { file ->
                val document = DocumentsContract.getDocumentId(file.media.uri)
                val sameMedia = volume(document.substringBefore(':', ""))?.let { key(it, file.media) in media } == true
                !sameMedia && document !in documents
            }
        }
    }
}
