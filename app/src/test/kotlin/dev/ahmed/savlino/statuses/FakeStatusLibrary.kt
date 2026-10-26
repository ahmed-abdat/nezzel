package dev.ahmed.savlino.statuses

import android.content.Intent
import android.net.Uri

/** Synthetic statuses for tests and captures: no real names, files or images. */
class FakeStatusLibrary(var access: StatusAccess = StatusAccess.NeedsGrant(listOf(APP)),
                        var grant: StatusGrantResult = StatusGrantResult.Granted,
                        var results: (StatusItem) -> StatusSaveResult = { StatusSaveResult.Saved(Uri.parse("content://media/external/file/${it.uri.lastPathSegment}")) }) : StatusLibrary {
    /** Every file copied, in order. */
    val saved = mutableListOf<StatusItem>()
    var readyShown = 0
    val picked = mutableListOf<Uri?>()
    var loads = 0
    var forgotten = false
    /** Set to the access a successful grant moves to. */
    var afterGrant: StatusAccess? = null
    /** When set, each save waits for it (captures of the saving state). */
    var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun grantIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    override suspend fun onPicked(tree: Uri?): StatusGrantResult {
        picked += tree
        if (grant == StatusGrantResult.Granted) afterGrant?.let { access = it }
        return grant
    }
    override suspend fun load(): StatusAccess { loads++; return access }
    /** Runs before one item's copy (a test can hold just that one). */
    var gate: suspend (StatusItem) -> Unit = {}
    /** False: saved files come back unmarked, like a status with unknown size or time. */
    var marks = true
    override suspend fun saveOne(item: StatusItem): StatusSaveResult {
        hold?.await()
        gate(item)
        saved += item
        val out = results(item)
        // Saved items come back marked on the next load, as the real library does.
        if (out is StatusSaveResult.Saved && marks) (access as? StatusAccess.Ready)?.let { ready ->
            access = ready.copy(items = ready.items.map { if (it.uri == item.uri) it.copy(saved = true, isNew = false) else it })
        }
        return out
    }
    override fun readyShown() { readyShown++ }
    override suspend fun forget() { forgotten = true }

    companion object {
        val APP = StatusApp("com.whatsapp", "WhatsApp")
        val BUSINESS = StatusApp("com.whatsapp.w4b", "WhatsApp Business")
        val MAIN = StatusSource(APP.packageName, 1)
        val SECOND = StatusSource(APP.packageName, 2)
        val THIRD = StatusSource(BUSINESS.packageName, 1)

        fun item(n: Int, video: Boolean, saved: Boolean = false, new: Boolean = false, source: StatusSource = MAIN) = StatusItem(
            Uri.parse("content://fake.statuses/document/status-$n"), source, video, if (video) "video/mp4" else "image/jpeg",
            sizeBytes = 1_000_000L + n, lastModifiedMillis = 1_700_000_000_000L - n * 60_000L, saved = saved, isNew = new)

        /** Nine statuses newest first: three new, three saved, photos and videos mixed (mockup grid). */
        fun mixed(source: StatusSource = MAIN, offset: Int = 0) = listOf(
            item(offset + 1, true, new = true, source = source), item(offset + 2, false, new = true, source = source),
            item(offset + 3, true, new = true, source = source), item(offset + 4, false, saved = true, source = source),
            item(offset + 5, true, source = source), item(offset + 6, true, saved = true, source = source),
            item(offset + 7, false, source = source), item(offset + 8, true, source = source), item(offset + 9, false, saved = true, source = source))

        fun ready(items: List<StatusItem> = mixed(), sources: List<StatusSource> = items.map { it.source }.distinct(),
                  apps: List<StatusApp> = listOf(APP)) = StatusAccess.Ready(apps, sources, items)
    }
}
