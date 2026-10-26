package dev.ahmed.savlino.statuses

import android.content.Intent
import android.net.Uri
import dev.ahmed.savlino.usage.UsageKeys

/** An installed WhatsApp-family app; the label and icon come from PackageManager. */
data class StatusApp(val packageName: String, val label: String)

/** One account folder of one app ("Account 1/2" by folder order; no names exist on disk). */
data class StatusSource(val packageName: String, val accountIndex: Int)

/** A status file as listed through the folder grant. Unknown size/time stay null, never guessed. */
data class StatusItem(val uri: Uri, val source: StatusSource, val isVideo: Boolean, val mimeType: String,
    val sizeBytes: Long?, val lastModifiedMillis: Long?, val saved: Boolean, val isNew: Boolean)

sealed interface StatusAccess {
    /** No WhatsApp-family app is installed. */
    data object NotInstalled : StatusAccess
    /** Apps are installed but no folder grant exists yet. */
    data class NeedsGrant(val apps: List<StatusApp>) : StatusAccess
    /** A grant existed but no longer works (revoked, folder moved). */
    data class AccessLost(val apps: List<StatusApp>) : StatusAccess
    /** Granted; [items] newest first, may be empty. [sources] lists only non-empty account folders. */
    data class Ready(val apps: List<StatusApp>, val sources: List<StatusSource>, val items: List<StatusItem>) : StatusAccess
}

/** What came back from the system folder picker. */
sealed interface StatusGrantResult {
    data object Granted : StatusGrantResult
    /** The user picked a folder that holds no WhatsApp statuses; reopen the picker. */
    data object WrongFolder : StatusGrantResult
    data object Cancelled : StatusGrantResult
}

/**
 * Seam between the statuses screen and storage. One READ-only grant (owner 2026-10-24: `Android/media`,
 * covering every app and account; per-app tree as fallback). Never logs names, URIs or counts.
 */
interface StatusLibrary {
    /** OPEN_DOCUMENT_TREE intent, pre-pointed at the widest grantable folder. */
    fun grantIntent(): Intent
    suspend fun onPicked(tree: Uri?): StatusGrantResult
    suspend fun load(): StatusAccess
    /**
     * Copies one status; cancelling the caller stops provider and MediaStore work. A committed copy stays [StatusSaveResult.Saved]
     * even when the cancel came late, and is marked for later loads; otherwise a cancelled caller gets the cancellation.
     */
    suspend fun saveOne(item: StatusItem): StatusSaveResult
    /** The tab showed at least one unsaved status in this visit (counts only). */
    fun readyShown() {}
    /** Releases the grant. */
    suspend fun forget()
}

/** Saves serially; results keep the input order. */
suspend fun StatusLibrary.save(items: List<StatusItem>): List<StatusSaveResult> = items.map { saveOne(it) }

/** Help-improve counts per file, the moment each copy ends: saved, or not saved (failed, gone, no space). Cancelling counts nothing. */
fun StatusLibrary.counted(count: (String) -> Unit): StatusLibrary = object : StatusLibrary by this {
    override suspend fun saveOne(item: StatusItem) = this@counted.saveOne(item).also { result ->
        when (result) {
            is StatusSaveResult.Saved -> count(UsageKeys.STATUSES_SAVED)
            StatusSaveResult.Failed, StatusSaveResult.Gone, StatusSaveResult.NoSpace -> count(UsageKeys.STATUSES_SAVE_FAILED)
            StatusSaveResult.Cancelled -> Unit
        }
    }
    override fun readyShown() = count(UsageKeys.STATUSES_READY)
}
