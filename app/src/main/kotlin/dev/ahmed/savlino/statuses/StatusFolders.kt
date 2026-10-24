package dev.ahmed.savlino.statuses

/**
 * Where WhatsApp-family apps keep viewed statuses, relative to a storage volume root. Pure: no Android types.
 * A34/API 36 keeps them under `WhatsApp/accounts/<id>/Media/.Statuses`; older builds use `WhatsApp/Media/.Statuses`
 * (API 30+: below `Android/media/<pkg>/`; API 29: at the volume root). Every layout stays probed on every API level:
 * a missing folder only costs one child query.
 */
object StatusFolders {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val WHATSAPP = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    val PACKAGES = listOf(WHATSAPP, BUSINESS)
    /** Stands for one account folder name in [patterns]. */
    const val ANY = "*"
    private const val PRIMARY = "primary"

    /** A status folder path pattern of one app; [segments] may hold [ANY]. */
    data class Pattern(val packageName: String, val segments: List<String>)

    /**
     * What to walk below a granted tree to reach one status folder pattern. From [appFolderDepth] on, the walk is
     * inside the app's own folder: a grant there is right even before any status was viewed.
     */
    data class Probe(val packageName: String, val segments: List<String>, val appFolderDepth: Int)

    private fun appDir(packageName: String) = if (packageName == BUSINESS) "WhatsApp Business" else "WhatsApp"

    /** Scoped layouts first on API 30+, the volume-root legacy layout first on API 29. */
    fun patterns(sdk: Int): List<Pattern> {
        val scoped = PACKAGES.flatMap { pkg ->
            val base = listOf("Android", "media", pkg, appDir(pkg))
            listOf(Pattern(pkg, base + listOf("accounts", ANY, "Media", ".Statuses")), Pattern(pkg, base + listOf("Media", ".Statuses")))
        }
        val legacy = PACKAGES.map { Pattern(it, listOf(appDir(it), "Media", ".Statuses")) }
        return if (sdk >= 30) scoped + legacy else legacy + scoped
    }

    /**
     * Document id of the folder the picker opens at. API 30+: `Android/media`, which covers every app and account
     * (owner 2026-10-24). API 29 has no `Android/media` WhatsApp folder yet and still allows the volume root.
     */
    fun initialDocumentId(sdk: Int) = if (sdk >= 30) "$PRIMARY:Android/media" else "$PRIMARY:"

    /** `primary:Android/media` → `Android`, `media`. Null for anything that is not an external-storage document id. */
    fun pathOf(documentId: String): List<String>? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        return documentId.substring(colon + 1).split('/').filter { it.isNotEmpty() }
    }

    /**
     * Probes left to walk below a tree whose root is [treeDocumentId]. The tree may be any folder on the way to a
     * status folder or the status folder itself; empty means it can never hold statuses (WrongFolder, no query needed).
     */
    fun probes(treeDocumentId: String, sdk: Int): List<Probe> {
        val tree = pathOf(treeDocumentId) ?: return emptyList()
        return patterns(sdk).mapNotNull { pattern ->
            if (tree.size > pattern.segments.size) return@mapNotNull null
            val matches = tree.indices.all { i -> pattern.segments[i] == ANY || pattern.segments[i] == tree[i] }
            val appFolder = pattern.segments.indexOf(appDir(pattern.packageName)) + 1
            if (matches) Probe(pattern.packageName, pattern.segments.drop(tree.size), maxOf(0, appFolder - tree.size)) else null
        }
    }

    /** The account folder name of a found status folder path, or null for the single-account layouts. */
    fun accountOf(path: List<String>): String? {
        val index = path.lastIndexOf("accounts")
        return if (index >= 0 && index + 1 < path.size) path[index + 1] else null
    }

    /** Folder order behind "Account 1/2": the single-account layout first, then account folders by number. */
    val accountOrder: Comparator<String?> = compareBy<String?>({ it != null }, { it?.toLongOrNull() ?: Long.MAX_VALUE }, { it })
}
