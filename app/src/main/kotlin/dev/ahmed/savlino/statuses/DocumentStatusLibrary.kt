package dev.ahmed.savlino.statuses

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Statuses through READ-only document-tree grants. Walks only the known path segments below each grant (one child
 * query per level), never the whole tree. Never logs names, URIs or counts.
 */
class DocumentStatusLibrary(
    context: Context,
    private val saveOne: suspend (StatusSaveSource) -> StatusSaveResult,
    private val grants: StatusGrants = StatusGrants(context),
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StatusLibrary {
    private val resolver = context.contentResolver
    private val packages = context.packageManager
    private val marks = StatusMarks(context.getSharedPreferences("nezzel-status-marks", Context.MODE_PRIVATE))

    override fun grantIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(StatusFolders.AUTHORITY, StatusFolders.initialDocumentId(sdk)))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    override suspend fun onPicked(tree: Uri?): StatusGrantResult = withContext(io) {
        if (tree == null) return@withContext StatusGrantResult.Cancelled
        val probes = treeProbes(tree) ?: return@withContext StatusGrantResult.WrongFolder
        try { grants.add(tree) } catch (_: SecurityException) { return@withContext StatusGrantResult.WrongFolder }
        val walk = Walk(tree)
        val root = DocumentsContract.getTreeDocumentId(tree)
        if (walk.children(root) != null) walk.find(root, StatusFolders.pathOf(root).orEmpty(), probes, 0)
        if (walk.found.isEmpty() && !walk.appFolderSeen) {
            grants.discard(tree)
            StatusGrantResult.WrongFolder
        } else StatusGrantResult.Granted.also { grants.found() }
    }

    override suspend fun load(): StatusAccess = withContext(io) {
        val apps = installedApps()
        if (apps.isEmpty()) return@withContext StatusAccess.NotInstalled
        // A stored grant that can never hold statuses (bad shape) is released, not reported as lost.
        val trees = grants.active().filter { tree -> (treeProbes(tree) != null).also { if (!it) grants.discard(tree) } }
        if (trees.isEmpty()) return@withContext if (grants.isLost) StatusAccess.AccessLost(apps) else StatusAccess.NeedsGrant(apps)
        val folders = linkedMapOf<String, Pair<Walk, Found>>()
        val walks = mutableListOf<Walk>()
        var working = 0
        for (tree in trees) {
            currentCoroutineContext().ensureActive()
            val probes = treeProbes(tree) ?: continue // filtered above
            val walk = Walk(tree)
            val root = DocumentsContract.getTreeDocumentId(tree)
            walks += walk
            // The root query is the liveness check. A failure may be transient (provider busy, volume not ready),
            // so the grant is only skipped this load; it is released only on forget or once the system drops it.
            if (walk.children(root) == null) continue
            working++
            walk.find(root, StatusFolders.pathOf(root).orEmpty(), probes, 0)
            // Same folder through two grants: provider document ids are tree-independent, keep the first.
            walk.found.forEach { folders.putIfAbsent(it.documentId, walk to it) }
        }
        if (working == 0) return@withContext StatusAccess.AccessLost(apps)
        val installed = apps.map { it.packageName }.toSet()
        val kept = folders.values.filter { it.second.packageName in installed }
        // Account numbers follow every found folder, not only non-empty ones: labels must not shift when an
        // account's statuses expire (chips show only with more than one source).
        val indexes = kept.groupBy { it.second.packageName }.mapValues { (_, found) ->
            found.map { StatusFolders.accountOf(it.second.path) }.distinct().sortedWith(StatusFolders.accountOrder)
        }
        val listed = kept.flatMap { (walk, folder) ->
            val account = StatusFolders.accountOf(folder.path)
            val source = StatusSource(folder.packageName, indexes.getValue(folder.packageName).indexOf(account) + 1)
            walk.children(folder.documentId).orEmpty().filter { it.isStatus }.map { source to (walk to it) }
        }
        val seen = marks.seen()
        val saved = marks.saved()
        val items = listed.map { (source, entry) ->
            val (walk, child) = entry
            StatusItem(DocumentsContract.buildDocumentUriUsingTree(walk.tree, child.documentId), source,
                child.mimeType.startsWith("video/"), child.mimeType, child.size, child.modified,
                saved = marks.isSaved(saved, child.documentId, child.size, child.modified),
                isNew = seen != null && marks.seenKey(child.documentId) !in seen)
        }.sortedWith(compareByDescending<StatusItem, Long?>(nullsFirst<Long>()) { it.lastModifiedMillis })
        // A partial load (a failed query) must not forget what the failed folders showed last time.
        marks.see(listed.map { it.second.second.documentId }, replace = walks.none { it.failed })
        val sources = items.map { it.source }.distinct()
            .sortedWith(compareBy({ StatusFolders.PACKAGES.indexOf(it.packageName) }, { it.accountIndex }))
        StatusAccess.Ready(apps, sources, items)
    }

    override suspend fun save(items: List<StatusItem>): List<StatusSaveResult> = items.map { item ->
        currentCoroutineContext().ensureActive()
        val result = saveOne(StatusSaveSource(item.uri, item.mimeType, item.sizeBytes, item.lastModifiedMillis))
        if (result is StatusSaveResult.Saved) withContext(io) {
            marks.markSaved(DocumentsContract.getDocumentId(item.uri), item.sizeBytes, item.lastModifiedMillis)
        }
        result
    }

    override suspend fun forget() = withContext(io) { grants.forget() }

    private fun treeProbes(tree: Uri): List<StatusFolders.Probe>? {
        if (tree.authority != StatusFolders.AUTHORITY || !DocumentsContract.isTreeUri(tree)) return null
        val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: IllegalArgumentException) { return null }
        return StatusFolders.probes(root, sdk).takeIf { it.isNotEmpty() }
    }

    private fun installedApps(): List<StatusApp> = StatusFolders.PACKAGES.mapNotNull { name ->
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) packages.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
            else @Suppress("DEPRECATION") packages.getApplicationInfo(name, 0)
        } catch (_: PackageManager.NameNotFoundException) { null }
        info?.takeIf(ApplicationInfo::enabled)?.let { StatusApp(name, packages.getApplicationLabel(it).toString()) }
    }

    private data class Child(val documentId: String, val name: String, val mimeType: String, val size: Long?, val modified: Long?) {
        val isDirectory get() = mimeType == Document.MIME_TYPE_DIR
        val isStatus get() = !isDirectory && name != ".nomedia" && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))
    }

    private data class Found(val packageName: String, val documentId: String, val path: List<String>)

    /** One grant during one load: child queries are made once per folder. */
    private inner class Walk(val tree: Uri) {
        val found = mutableListOf<Found>()
        var appFolderSeen = false
        var failed = false
        private val listed = hashMapOf<String, List<Child>?>()

        fun children(documentId: String): List<Child>? =
            listed.getOrPut(documentId) { query(documentId).also { if (it == null) failed = true } }

        fun find(documentId: String, path: List<String>, probes: List<StatusFolders.Probe>, depth: Int) {
            if (probes.any { depth >= it.appFolderDepth }) appFolderSeen = true
            probes.filter { it.segments.size == depth }.forEach { found += Found(it.packageName, documentId, path) }
            val deeper = probes.filter { it.segments.size > depth }
            if (deeper.isEmpty()) return
            // A folder that vanished mid-walk is just absent.
            for (child in children(documentId).orEmpty()) {
                if (!child.isDirectory) continue
                val next = deeper.filter { it.segments[depth] == StatusFolders.ANY || it.segments[depth] == child.name }
                if (next.isNotEmpty()) find(child.documentId, path + child.name, next, depth + 1)
            }
        }

        private fun query(documentId: String): List<Child>? = try {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId), COLUMNS, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        add(Child(id, c.getString(1).orEmpty(), c.getString(2)?.lowercase(Locale.ROOT).orEmpty(),
                            if (c.isNull(3)) null else c.getLong(3).takeIf { it >= 0 },
                            if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 }))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

        fun create(context: Context, saver: StatusSaver): StatusLibrary =
            DocumentStatusLibrary(context.applicationContext, { saver.save(it) })
    }
}

/**
 * Saved marks (provider document id + size + time, hashed; unknown fields are never marked) and the ids seen on the
 * previous load. Prefs, not the job database: no schema migration. No names are stored.
 */
internal class StatusMarks(private val prefs: SharedPreferences) {
    fun saved(): Set<String> = savedList().toSet()

    fun isSaved(saved: Set<String>, documentId: String, size: Long?, modified: Long?) =
        savedKey(documentId, size, modified)?.let { it in saved } == true

    @Synchronized fun markSaved(documentId: String, size: Long?, modified: Long?) {
        val key = savedKey(documentId, size, modified) ?: return
        val list = savedList().filter { it != key } + key
        prefs.edit().putString(SAVED, list.takeLast(CAP).joinToString("\n")).apply()
    }

    /** Null before the first load: nothing counts as new without a previous visit. */
    fun seen(): Set<String>? = prefs.getString(SEEN, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet()

    @Synchronized fun see(documentIds: List<String>, replace: Boolean) {
        val keys = (if (replace) emptyList() else seen().orEmpty().toList()) + documentIds.map(::seenKey)
        prefs.edit().putString(SEEN, keys.distinct().takeLast(CAP).joinToString("\n")).apply()
    }

    fun seenKey(documentId: String) = hash("seen\u0000$documentId")

    private fun savedList() = prefs.getString(SAVED, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun savedKey(documentId: String, size: Long?, modified: Long?): String? {
        if (size == null || size < 0 || modified == null || modified <= 0) return null
        return hash("saved\u0000$documentId\u0000$size\u0000$modified")
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SAVED = "saved"
        const val SEEN = "seen"
        const val CAP = 2000
    }
}
