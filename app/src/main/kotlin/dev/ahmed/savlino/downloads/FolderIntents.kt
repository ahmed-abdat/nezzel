package dev.ahmed.savlino.downloads

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.DocumentsContract
import dev.ahmed.savlino.storage.SaveDestination

/** Resolves without launching, querying a provider, or acquiring a storage grant. */
object FolderIntents {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private val documentsUi = listOf("com.google.android.documentsui", "com.android.documentsui")

    fun forItem(item: SavedMedia, destination: SaveDestination, pm: PackageManager): Intent? =
        folderUri(item, destination)?.let { forFolder(it, pm) }

    fun folderUri(item: SavedMedia, destination: SaveDestination): Uri? {
        val uri = item.uri
        if (uri.scheme != "content" || uri.query != null || uri.fragment != null) return null
        return try {
            if (uri.authority == "media") {
                val path = (item.relativePath ?: "Download/Nezzel").trimEnd('/')
                if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == "." || it == ".." }) return null
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$path")
            } else {
                val tree = when {
                    DocumentsContract.isTreeUri(uri) -> uri
                    destination is SaveDestination.Tree && destination.uri.authority == uri.authority -> {
                        // Only a document from the same provider may use the current tree as a fallback.
                        DocumentsContract.getDocumentId(uri)
                        destination.uri
                    }
                    else -> return null
                }
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                // Document ids are opaque except for ExternalStorageProvider's volume:path ids.
                val parent = if (uri.authority == EXTERNAL_STORAGE) {
                    val id = DocumentsContract.getDocumentId(uri)
                    id.substringBeforeLast('/', treeId).takeIf {
                        it == treeId || it.startsWith("${treeId.trimEnd('/')}/") ||
                            (treeId.endsWith(':') && it.startsWith(treeId))
                    }
                } else null
                DocumentsContract.buildDocumentUriUsingTree(tree, parent ?: treeId)
            }
        } catch (_: IllegalArgumentException) { null }
    }

    fun forFolder(folder: Uri, pm: PackageManager): Intent? {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A chosen folder is ours through its persisted tree grant, so a non-system Files app gets read access to that
        // folder only. The default folder's URI is not ours to grant (that would throw); DocumentsUI needs no grant.
        if (DocumentsContract.isTreeUri(folder)) view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (pkg in documentsUi) {
            val targeted = Intent(view).setPackage(pkg)
            handlers(targeted, pm).firstOrNull()?.let { return targeted.setComponent(it) }
        }
        // Pin the sole handler so even a later package install cannot introduce an open-with chooser.
        handlers(view, pm).singleOrNull()?.let { return view.setComponent(it) }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = pm.resolveActivity(picker, PackageManager.MATCH_DEFAULT_ONLY)
            ?.takeIf(::usable)?.let(::component) ?: return null
        return picker.setComponent(resolved)
    }

    private fun handlers(intent: Intent, pm: PackageManager): List<ComponentName> =
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter(::usable).map(::component).distinct()

    private fun usable(info: ResolveInfo): Boolean {
        val activity = info.activityInfo ?: return false
        val name = activity.name ?: return false
        return activity.enabled && activity.exported && activity.packageName != "android" &&
            !name.contains("ResolverActivity", ignoreCase = true) && !name.contains("ChooserActivity", ignoreCase = true)
    }

    private fun component(info: ResolveInfo) = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
}
