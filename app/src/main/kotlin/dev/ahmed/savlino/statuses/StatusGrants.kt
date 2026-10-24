package dev.ahmed.savlino.statuses

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * READ-only folder grants for statuses, in their own prefs (not the save-folder grant). A set: a media-root grant and
 * narrower per-app grants may coexist. A grant missing from the system list is dropped and remembered as lost; a
 * folder that fails to answer keeps its grant (the failure may be transient).
 */
class StatusGrants(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("nezzel-status-grants", Context.MODE_PRIVATE)

    /** Throws SecurityException when the picker result carried no persistable grant. */
    @Synchronized fun add(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putStringSet(TREES, stored() + tree.toString()).apply()
    }

    /** A picked folder proved right: the earlier loss is over. A wrong pick keeps it. */
    @Synchronized fun found() = prefs.edit().putBoolean(LOST, false).apply()

    /** Stored grants the system still holds; the rest are dropped. */
    @Synchronized fun active(): List<Uri> {
        val held = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val stored = stored()
        val kept = stored.filter { it in held }
        if (kept.size != stored.size) prefs.edit().putStringSet(TREES, kept.toSet()).putBoolean(LOST, kept.isEmpty() || isLost).apply()
        return kept.sorted().map(Uri::parse)
    }

    /** True after the system dropped every grant, until a right pick or forget. */
    val isLost: Boolean @Synchronized get() = prefs.getBoolean(LOST, false)

    /** A picked folder that holds no statuses: release it without marking anything lost. */
    @Synchronized fun discard(tree: Uri) {
        release(tree)
        prefs.edit().putStringSet(TREES, stored() - tree.toString()).apply()
    }

    @Synchronized fun forget() {
        stored().forEach { release(Uri.parse(it)) }
        prefs.edit().clear().apply()
    }

    private fun stored(): Set<String> = prefs.getStringSet(TREES, null).orEmpty().toSet()

    // A read+write grant on the same tree belongs to the save-folder feature; keep it.
    private fun release(tree: Uri) {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return
        if (held.isWritePermission) return
        try { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
    }

    private companion object {
        const val TREES = "trees"
        const val LOST = "lost"
    }
}
