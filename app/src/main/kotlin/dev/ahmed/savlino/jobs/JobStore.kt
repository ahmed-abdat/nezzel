package dev.ahmed.savlino.jobs

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import dev.ahmed.savlino.domain.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import dev.ahmed.savlino.storage.JobDirectory
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import dev.ahmed.savlino.storage.KeystoreLinkKeyer
import dev.ahmed.savlino.storage.KeystoreLinkCipher
import dev.ahmed.savlino.storage.LinkCipher
import org.json.JSONObject

/** The coordinator serializes recovery and execution before using this journal (U12). */
enum class JobStage(val terminal: Boolean = false) {
    ENQUEUED, TRANSFERRING, PAUSED, STAGED, PENDING_INSERTED, PUBLISHING,
    COMPLETED(true), FAILED(true), CANCELLED(true)
}

/** URL has no generated toString; only retryable failures retain it, for the confirmation lifetime. */
class JobRecord internal constructor(
    val jobId: String, val stage: JobStage, val sourceUrl: String?, val revision: Long,
    val engineVersion: String, val format: Format, val filename: String, val preset: Preset?,
    val pendingUri: Uri?, val displayName: String?, val createdAt: Long, val updatedAt: Long,
    val error: DownloadError?,
    /** Explicit fresh transfer only; terminal staging is deleted, except what a kept-source failure keeps (JobStore.keepsPartial). */
    val retryable: Boolean,
    val hasPartial: Boolean, val resumeRecipe: String?, val restartEpoch: Long,
    val transportResumes: Int, val stallRestarts: Int, val resumeResetBytes: Long,
) {
    /** Reconstruct only a still-confirmed nonterminal command. Recovery never starts it. */
    fun command(): DownloadCommand? {
        if (stage.terminal) return null
        val source = LinkRevision(LinkInput.parse(sourceUrl) ?: return null, revision)
        return (DownloadCommand.confirm(jobId, Inspection(source, engineVersion, listOf(format)), source,
            format.id, filename.substringBeforeLast('.')) as? ConfirmResult.Confirmed)?.command
    }
}

/** A saved file that still holds a Copy link or artwork; [published] is the URI the job wrote. */
class LinkedFile(val jobId: String, val published: Uri, val updatedAt: Long)

/** A finished job's kept link and what it saved: for "Look for better quality" and Try again. No toString (link). */
class Reopen internal constructor(val link: String, val format: Format, val preset: Preset?, val engineVersion: String,
    val filename: String)

/** A completed download of the same link and format: where it was published and when. */
class SavedBefore(val uri: Uri, val savedAt: Long)

/** Blocking platform SQLite, schema v7. Older rows migrate without losing publication ownership. */
/** [sourceKeys]: keyed tags for "saved before"; null (no AndroidKeyStore) stores no tag and finds nothing. */
class JobStore(context: Context, private val sourceKeys: LinkKeyer? = KeystoreLinkKeyer.deferred,
    private val links: LinkCipher? = KeystoreLinkCipher.current()) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    private val jobRoot = File(context.filesDir.canonicalFile, "downloads")
    /** Row artwork lives and dies with the row: Remove, the 100-row prune. */
    val artwork = JobArtwork(context.filesDir)
    private val terminalLock = Any()
    /** Called under this helper's monitor; observers must not acquire coordinator locks or read the store. */
    internal var onExpired: (JobRecord) -> Unit = {}
    private val infoCache = dev.ahmed.savlino.engine.YtdlpInfoCache(context.noBackupFilesDir)
    @Volatile private var opened: SQLiteDatabase? = null
    /**
     * The open database without this helper's monitor once opened. [writableDatabase] synchronizes on this object, so a
     * BatchStore transaction (holding the only connection) that reached it again would wait on a JobStore caller that
     * holds the monitor while waiting for that connection: the 0.5.0-beta4 playlist deadlock.
     */
    internal val database: SQLiteDatabase get() = opened?.takeIf { it.isOpen } ?: writableDatabase.also { opened = it }

    init {
        setOpenParams(SQLiteDatabase.OpenParams.Builder().setJournalMode("DELETE")
            .setSynchronousMode("FULL").build())
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // Scrub deleted/overwritten DB payloads, including expired failed-job URLs.
        // DELETE-mode rollback journals are unlinked after commit, not zeroed on disk.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE jobs (
                job_id TEXT PRIMARY KEY NOT NULL,
                stage TEXT NOT NULL CHECK(stage IN ('ENQUEUED','TRANSFERRING','PAUSED','STAGED',
                    'PENDING_INSERTED','PUBLISHING','COMPLETED','FAILED','CANCELLED')),
                source_url TEXT, revision INTEGER NOT NULL, engine_version TEXT NOT NULL,
                format_json TEXT NOT NULL, filename TEXT NOT NULL, preset TEXT,
                pending_uri TEXT, display_name TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, error TEXT, retryable INTEGER NOT NULL DEFAULT 0,
                source_key TEXT, has_partial INTEGER NOT NULL DEFAULT 0 CHECK(has_partial IN (0,1)),
                resume_recipe TEXT, restart_epoch INTEGER NOT NULL DEFAULT 0 CHECK(restart_epoch >= 0),
                transport_resumes INTEGER NOT NULL DEFAULT 0 CHECK(transport_resumes >= 0),
                stall_restarts INTEGER NOT NULL DEFAULT 0 CHECK(stall_restarts >= 0),
                resume_reset_bytes INTEGER NOT NULL DEFAULT 0 CHECK(resume_reset_bytes >= 0),
                $SHARE_LINK_COLUMN,
                CHECK (has_partial = 0 OR stage IN ('ENQUEUED','TRANSFERRING','PAUSED')),
                CHECK ((stage IN ('COMPLETED','CANCELLED') AND source_url IS NULL)
                    OR (stage = 'FAILED' AND (source_url IS NULL OR retryable = 1))
                    OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL)),
                CHECK (stage NOT IN ('PENDING_INSERTED','PUBLISHING','COMPLETED') OR pending_uri IS NOT NULL)
            )
        """.trimIndent())
        BatchStore.createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion != DATABASE_VERSION) return recreate(db)
        if (oldVersion in 1..3) {
            // The rebuild creates the current shape (share_link and batches included); no step follows it.
            // SQLiteOpenHelper owns the transaction, including the CHECK-constraint rebuild.
            db.execSQL("ALTER TABLE jobs RENAME TO jobs_old")
            onCreate(db)
            val columns = V2_COLUMNS + if (oldVersion >= 3) ", source_key" else ""
            db.execSQL("INSERT INTO jobs ($columns) SELECT $columns FROM jobs_old")
            db.execSQL("UPDATE jobs SET retryable = 0 WHERE stage = 'FAILED' AND source_url IS NULL")
            db.execSQL("DROP TABLE jobs_old")
            if (oldVersion < 3) backfillSourceKeys(db)
            return
        }
        if (oldVersion !in 4 until DATABASE_VERSION) return recreate(db)
        // One step per shipped version, in order: a v4 phone runs 4, 5, 6 and 7. A new version adds one step here and
        // never edits an old one. BatchStore.createTables writes the current shape, so later batch steps must tolerate it.
        for (version in oldVersion until DATABASE_VERSION) when (version) {
            4 -> {
                addColumn(db, "jobs", "transport_resumes INTEGER NOT NULL DEFAULT 0 CHECK(transport_resumes >= 0)")
                addColumn(db, "jobs", "stall_restarts INTEGER NOT NULL DEFAULT 0 CHECK(stall_restarts >= 0)")
                addColumn(db, "jobs", "resume_reset_bytes INTEGER NOT NULL DEFAULT 0 CHECK(resume_reset_bytes >= 0)")
            }
            5 -> addColumn(db, "jobs", SHARE_LINK_COLUMN)
            6 -> {
                // v6 ignores unknown tables on downgrade; old confirmations must never revive.
                db.execSQL("DROP TABLE IF EXISTS batch_items")
                db.execSQL("DROP TABLE IF EXISTS batches")
                BatchStore.createTables(db)
            }
            // 0.5.0-beta2 shipped v7 batches before the consecutive-failure guard existed.
            7 -> addColumn(db, "batches", "consecutive_failures INTEGER NOT NULL DEFAULT 0")
            else -> error("no upgrade step from database version $version")
        }
    }

    /** ALTER TABLE ADD COLUMN unless a current-shape table already has it. */
    private fun addColumn(db: SQLiteDatabase, table: String, definition: String) {
        val name = definition.substringBefore(' ')
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        if (name !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $definition")
    }

    /** Inside the upgrade transaction: rows that still hold their link (active, retryable) get its tag. */
    private fun backfillSourceKeys(db: SQLiteDatabase) {
        db.query("jobs", arrayOf("job_id", "source_url"), "source_url IS NOT NULL AND source_key IS NULL",
            null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = sourceKey(cursor.getString(1)) ?: continue
                db.update("jobs", ContentValues().apply { put("source_key", key) }, "job_id = ?", arrayOf(cursor.getString(0)))
            }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    private fun recreate(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS batch_items")
        db.execSQL("DROP TABLE IF EXISTS batches")
        db.execSQL("DROP TABLE IF EXISTS jobs")
        onCreate(db)
    }

    @Synchronized fun enqueue(command: DownloadCommand, preset: Preset? = null): Boolean {
        require(validJobId(command.jobId))
        // Never REPLACE: a duplicate command must not reset an existing/terminal job.
        if (get(command.jobId) != null) return false
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("job_id", command.jobId); put("stage", JobStage.ENQUEUED.name)
            put("source_url", command.source.link.rawUrl); put("revision", command.source.revision)
            put("engine_version", command.engineVersion); put("format_json", encodeFormat(command.format))
            put("filename", command.filename); put("preset", preset?.name)
            put("created_at", now); put("updated_at", now)
            put("source_key", tag(command.source.link.normalizedUrl))
        }
        // CONFLICT_IGNORE protects duplicate enqueues from a second helper/connection as well.
        return writableDatabase.insertWithOnConflict("jobs", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Serialize confirmation persistence with terminal writes; cancellation cannot leave a new cache behind. */
    @Synchronized internal fun prepareInfoJson(jobId: String, prepare: () -> Unit) {
        if (get(jobId)?.stage == JobStage.ENQUEUED) prepare()
    }
    internal fun discardInfoJson(jobId: String) = infoCache.remove(jobId)
    internal fun sweepInfoJson() {
        infoCache.sweep()
        all().filter { it.stage.terminal }.forEach { infoCache.remove(it.jobId) }
    }

    @Synchronized fun get(jobId: String): JobRecord? {
        scrubExpiredSources()
        return readableDatabase.query("jobs", null, "job_id = ?", arrayOf(jobId), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null }
    }

    @Synchronized fun all(): List<JobRecord> {
        scrubExpiredSources()
        return readableDatabase.query("jobs", null, null, null, null, null, "rowid ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } }
    }

    /**
     * Saved files from this link, newest first per format id: only completed jobs, matched by the one-way
     * [sourceKey] (the link itself is never kept after a job ends).
     */
    @Synchronized fun savedFormats(normalizedUrl: String): Map<String, SavedBefore> {
        val found = LinkedHashMap<String, SavedBefore>()
        val key = tag(normalizedUrl) ?: return found
        readableDatabase.query("jobs", arrayOf("format_json", "pending_uri", "updated_at"), "stage = ? AND source_key = ?",
            arrayOf(JobStage.COMPLETED.name, key), null, null, "updated_at DESC, rowid DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val id = decodeFormat(cursor.getString(0)).id
                val uri = cursor.getString(1)?.let(Uri::parse) ?: continue
                found.putIfAbsent(id, SavedBefore(uri, cursor.getLong(2)))
            }
        }
        return found
    }

    private fun tag(normalizedUrl: String): String? = sourceKeys?.key(LinkKeyer.JOB_SOURCE, normalizedUrl)
    private fun sourceKey(rawUrl: String): String? = LinkInput.parse(rawUrl)?.normalizedUrl?.let(::tag)

    /** Data-side explicit retry. The caller schedules it; no network or WorkManager call occurs here. */
    fun retry(jobId: String): Boolean = synchronized(terminalLock) {
        val row = get(jobId) ?: return@synchronized false
        // A still-owned MediaStore row could become a second copy of the file: recovery must resolve it first.
        // A tree receipt outlives the row (detached folder), so its orphan sweep covers a Retry.
        if (row.stage != JobStage.FAILED || !row.retryable || row.sourceUrl == null ||
            ownsMediaStoreSave(row) ||
            !(keepsPartial(row) || cleanupJobStaging(jobRoot, jobId))) return@synchronized false
        if (!infoCache.remove(jobId)) return@synchronized false
        synchronized(this) {
            // CAS also protects calls through another SQLite helper. Keep created_at: formats expire.
            writableDatabase.update("jobs", ContentValues().apply {
                put("stage", JobStage.ENQUEUED.name); put("updated_at", System.currentTimeMillis())
                putNull("error"); put("retryable", 0); putNull("pending_uri"); putNull("display_name")
                put("transport_resumes", 0); put("stall_restarts", 0); put("resume_reset_bytes", 0)
            }, "job_id = ? AND stage = 'FAILED' AND retryable = 1 AND source_url IS NOT NULL AND created_at >= ?",
                arrayOf(jobId, (System.currentTimeMillis() - CONFIRMATION_LIFETIME_MS).toString())) == 1
        }
    }

    /** Remove local terminal metadata and its private staging, never a published MediaStore file. */
    fun remove(jobId: String): Boolean = synchronized(terminalLock) {
        val removed = synchronized(this) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("UPDATE batches SET network_failures=0, refused_failures=0, consecutive_failures=0 WHERE batch_id IN " +
                    "(SELECT batch_id FROM batch_items WHERE job_id=? AND state IN ('WAITING','QUEUED','FAILED') " +
                    "AND job_id IN (SELECT job_id FROM jobs WHERE stage='COMPLETED'))", arrayOf(jobId))
                db.execSQL("UPDATE batch_items SET state='DONE', error=NULL, retryable=0, source_url=NULL " +
                    "WHERE job_id=? AND state IN ('WAITING','QUEUED','FAILED') AND job_id IN " +
                    "(SELECT job_id FROM jobs WHERE stage='COMPLETED')", arrayOf(jobId))
                val deleted = db.delete("jobs", "job_id = ? AND stage IN ('COMPLETED','FAILED','CANCELLED')", arrayOf(jobId)) == 1
                db.setTransactionSuccessful()
                deleted
            } finally { db.endTransaction() }
        }
        if (removed) { cleanupJobStaging(jobRoot, jobId); artwork.delete(jobId) }
        removed
    }

    @Synchronized fun startTransfer(jobId: String, recipe: String? = null): Boolean {
        scrubExpiredSources()
        val row = get(jobId)
        val epoch = Math.addExact(row?.restartEpoch ?: 0, 1)
        return transition(jobId, setOf(JobStage.ENQUEUED), JobStage.TRANSFERRING,
            ContentValues().apply {
                put("resume_recipe", recipe)
                put("restart_epoch", epoch)
                if (row?.resumeRecipe != recipe) put("resume_reset_bytes", 0)
            })
    }
    @Synchronized internal fun restartTransfer(jobId: String): Long? {
        if (get(jobId)?.stage != JobStage.TRANSFERRING) return null
        writableDatabase.execSQL("UPDATE jobs SET restart_epoch=restart_epoch+1, has_partial=0, resume_reset_bytes=0 WHERE job_id=? AND stage='TRANSFERRING'", arrayOf(jobId))
        return get(jobId)?.restartEpoch
    }
    @Synchronized internal fun requeue(jobId: String, hasPartial: Boolean = false): Boolean {
        scrubExpiredSources()
        return transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING), JobStage.ENQUEUED,
            ContentValues().apply { put("has_partial", if (hasPartial) 1 else 0); putNull("error") })
    }
    /**
     * Startup only: a death between staged and published left the finished file in staging. Run the job again
     * (it adopts that file and only saves) instead of failing it. A pending row must already be acknowledged gone.
     */
    // A death during every save (low memory on a big copy) must not loop: it spends the stall budget.
    @Synchronized internal fun requeueFinished(jobId: String): Boolean {
        val stalls = get(jobId)?.stallRestarts ?: return false
        // FAILED: an unresolved save whose pending row is now confirmed gone (it kept its finished file).
        return stalls < 3 && transition(jobId, setOf(JobStage.STAGED, JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.FAILED), JobStage.ENQUEUED,
            ContentValues().apply { put("has_partial", 1); putNull("error"); put("retryable", 0); putNull("pending_uri"); putNull("display_name")
                put("stall_restarts", stalls + 1) })
    }

    /** A validated staged output can save directly; repeated deaths still spend the save restart budget. */
    @Synchronized internal fun retryStagedPublication(jobId: String): Boolean {
        val stalls = get(jobId)?.stallRestarts ?: return false
        return stalls < 3 && transition(jobId, setOf(JobStage.STAGED), JobStage.STAGED,
            ContentValues().apply { put("stall_restarts", stalls + 1) })
    }
    /** Persist the reset position so trickles accumulate across attempts without reopening the budget. */
    @Synchronized internal fun resetAutomaticResumes(jobId: String, bytes: Long) {
        val row = get(jobId) ?: return
        val total = (row.format.size as? Size.Exact)?.bytes
        val completedSmallTransfer = total != null && total in 1 until RESUME_PROGRESS_STEP_BYTES &&
            row.resumeResetBytes < total && bytes >= total
        if (bytes - row.resumeResetBytes < RESUME_PROGRESS_STEP_BYTES && !completedSmallTransfer) return
        writableDatabase.update("jobs", ContentValues().apply {
            put("transport_resumes", 0); put("stall_restarts", 0); put("resume_reset_bytes", bytes)
        }, "job_id=? AND stage IN ('ENQUEUED','TRANSFERRING','PAUSED')", arrayOf(jobId))
    }

    /** Consume the durable budget atomically with requeue, across worker/process restarts. */
    @Synchronized internal fun requeueAutomatic(jobId: String, cause: DownloadFailureCause, hasPartial: Boolean = true): Boolean {
        scrubExpiredSources()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = db.query("jobs", null, "job_id=?", arrayOf(jobId), null, null, null)
                .use { if (it.moveToFirst()) read(it) else null } ?: return false
            val transport = row.transportResumes
            val stalls = row.stallRestarts
            val admitted = when (cause) {
                DownloadFailureCause.TRANSPORT -> transport < 1
                DownloadFailureCause.STALL -> stalls < 3
                else -> false
            }
            if (!admitted) return false
            val changed = transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING), JobStage.ENQUEUED,
                ContentValues().apply {
                    put("has_partial", if (hasPartial) 1 else 0); putNull("error")
                    put("transport_resumes", transport + if (cause == DownloadFailureCause.TRANSPORT) 1 else 0)
                    put("stall_restarts", stalls + if (cause == DownloadFailureCause.STALL) 1 else 0)
                })
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    @Synchronized fun pause(jobId: String, hasPartial: Boolean? = null): Boolean {
        scrubExpiredSources()
        return transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING), JobStage.PAUSED,
            ContentValues().apply { hasPartial?.let { put("has_partial", if (it) 1 else 0) }; putNull("error") })
    }
    @Synchronized internal fun transferPartial(jobId: String, hasPartial: Boolean) {
        writableDatabase.update("jobs", ContentValues().apply { put("has_partial", if (hasPartial) 1 else 0) },
            "job_id=? AND stage='TRANSFERRING'", arrayOf(jobId))
    }
    @Synchronized internal fun pausedPartial(jobId: String, hasPartial: Boolean) {
        writableDatabase.update("jobs", ContentValues().apply { put("has_partial", if (hasPartial) 1 else 0) },
            "job_id=? AND stage='PAUSED'", arrayOf(jobId))
    }
    @Synchronized fun resume(jobId: String): Boolean {
        scrubExpiredSources()
        return transition(jobId, setOf(JobStage.PAUSED), JobStage.ENQUEUED,
            ContentValues().apply {
                put("transport_resumes", 0); put("stall_restarts", 0)
                put("resume_reset_bytes", stagingBytes(File(jobRoot, jobId)))
            })
    }

    internal fun cancelEnqueued(jobId: String): Boolean = finishTerminal(jobId) {
        transition(jobId, setOf(JobStage.ENQUEUED, JobStage.PAUSED), JobStage.CANCELLED,
            ContentValues().apply { put("error", DownloadError.Cancelled.name) })
    }

    @Synchronized fun markStaged(jobId: String): Boolean = transition(jobId,
        setOf(JobStage.TRANSFERRING), JobStage.STAGED)

    @Synchronized fun recordPending(jobId: String, pending: PendingPublication): Boolean = transition(jobId,
        setOf(JobStage.STAGED), JobStage.PENDING_INSERTED, ContentValues().apply {
            put("pending_uri", pending.uri.toString()); put("display_name", pending.displayName)
        })
    @Synchronized fun markPublishing(jobId: String): Boolean = transition(jobId,
        setOf(JobStage.PENDING_INSERTED), JobStage.PUBLISHING)

    fun complete(jobId: String, published: PublishedFile): Boolean = finishTerminal(jobId) {
        val row = get(jobId)
        // The link is cleared below; a row from before tags (or a lost backfill) gets its tag now.
        val key = row?.sourceUrl?.let(::sourceKey)
        // Copy link: only the reviewed intake link, sealed in the same UPDATE that scrubs source_url.
        val link = if (row?.pendingUri == published.uri) row.sourceUrl?.let { links?.seal(it) } else null
        row?.pendingUri == published.uri &&
            // FAILED/CANCELLED: an unresolved publication (still owning this URI) turned out committed; a
            // committed file wins a late cancellation.
            transition(jobId, setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.FAILED, JobStage.CANCELLED), JobStage.COMPLETED,
                ContentValues().apply {
                    put("display_name", published.displayName); key?.let { put("source_key", it) }; link?.let { put("share_link", it) }
                }).also { if (it) supersede(jobId, published.uri) }
    }

    /** A folder or MediaStore can hand a freed URI to a new file: older rows at that URI lose their link and artwork. */
    private fun supersede(jobId: String, uri: Uri) {
        val older = writableDatabase.query("jobs", arrayOf("job_id"), "stage = 'COMPLETED' AND pending_uri = ? AND job_id != ?",
            arrayOf(uri.toString(), jobId), null, null, null).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        for (id in older) forgetFile(id)
    }

    /** Pending ownership must be resolved first by recovery; late cancellation cannot undo commit. */
    fun cancel(jobId: String): Boolean = finishTerminal(jobId) {
        transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED, JobStage.STAGED), JobStage.CANCELLED,
            ContentValues().apply { put("error", DownloadError.Cancelled.name) })
    }

    /** Retry keeps only a still-confirmed URL; partial bytes are resumed only when [keepPartial] (see [keepsPartial]). */
    fun fail(jobId: String, error: DownloadError, retryable: Boolean = error.retryable,
        keepPartial: Boolean = false): Boolean = finishTerminal(jobId, keepStaging = keepPartial && error.keepsSource) {
        transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED, JobStage.STAGED), JobStage.FAILED,
            ContentValues().apply {
                put("error", error.name); put("retryable", if (retryable && error.retryable) 1 else 0)
            })
    }

    /** Persist the failure reason if provider reconciliation must be deferred. */
    @Synchronized internal fun requestPublicationFailure(jobId: String, reason: DownloadError) {
        require(reason == DownloadError.Cancelled || reason.stopsSave || reason == DownloadError.PostTransferFailed)
        writableDatabase.update("jobs", ContentValues().apply { put("error", reason.name) },
            "job_id = ? AND stage IN ('PENDING_INSERTED','PUBLISHING') AND (error IS NULL OR error != 'Cancelled')", arrayOf(jobId))
    }

    /** A lost tree grant is retryable; keep its receipt/URI so cleanup is never mistaken for confirmed absence. */
    internal fun folderUnavailable(jobId: String): Boolean = finishTerminal(jobId) {
        transition(jobId, setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING), JobStage.FAILED,
            ContentValues().apply { put("error", DownloadError.FolderUnavailable.name); put("retryable", 1) })
    }

    @Synchronized internal fun clearFailedPublication(jobId: String, uri: Uri) {
        writableDatabase.update("jobs", ContentValues().apply { putNull("pending_uri") },
            "job_id = ? AND stage IN ('FAILED','CANCELLED') AND pending_uri = ?", arrayOf(jobId, uri.toString()))
    }

    /** Only call after the publisher confirms absence; retain the URI until that acknowledgement. */
    internal fun publicationAbandoned(jobId: String): Boolean = endPublication(jobId, keepUri = false)

    /**
     * The provider threw or did not acknowledge the delete: end the job anyway (Retry, playlist slot, engine
     * maintenance), keeping the URI so recovery deletes or adopts it later. Retry waits for that (see [retry]).
     */
    internal fun publicationUnresolved(jobId: String): Boolean = endPublication(jobId, keepUri = true)

    // An unresolved MediaStore save keeps its staging (see [keepsPartial]): once the row is gone it saves again.
    private fun endPublication(jobId: String, keepUri: Boolean): Boolean = finishTerminal(jobId,
        keepStaging = get(jobId)?.let { it.error == DownloadError.PostTransferFailed ||
            keepUri && ownsMediaStoreSave(it) && it.error != DownloadError.Cancelled } == true) {
        val reason = get(jobId)?.error
        val cancelled = reason == DownloadError.Cancelled
        transition(jobId, setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING),
            if (cancelled) JobStage.CANCELLED else JobStage.FAILED, ContentValues().apply {
                put("error", if (cancelled) DownloadError.Cancelled.name
                    else if (reason?.stopsSave == true || reason == DownloadError.PostTransferFailed) reason.name else DownloadError.SaveFailed.name)
                put("retryable", if (!cancelled) 1 else 0); if (!keepUri) putNull("pending_uri")
            })
    }

    // SQLite's lock ends before filesystem work. Cleanup failure is retried at startup.
    private fun finishTerminal(jobId: String, keepStaging: Boolean = false, update: () -> Boolean): Boolean = synchronized(terminalLock) {
        val changed = synchronized(this) { update() }
        if (changed) {
            infoCache.remove(jobId)
            if (!keepStaging) cleanupJobStaging(jobRoot, jobId)
        }
        changed
    }

    /** Saved files that still carry a Copy link blob or row artwork (at most the 100 kept terminal rows). */
    @Synchronized fun linkedFiles(): List<LinkedFile> = readableDatabase.query("jobs",
        arrayOf("job_id", "pending_uri", "updated_at", "share_link IS NOT NULL"),
        "stage = 'COMPLETED' AND pending_uri IS NOT NULL", null, null, null, "updated_at DESC, rowid DESC").use { c ->
        buildList { while (c.moveToNext()) {
            if (c.getInt(3) == 1 || artwork.has(c.getString(0))) add(LinkedFile(c.getString(0), Uri.parse(c.getString(1)), c.getLong(2)))
        } }
    }

    /** The saved file is confirmed gone: its link and its artwork go too; the row stays. */
    fun forgetFile(jobId: String) { dropShareLink(jobId); artwork.delete(jobId) }

    /** The reviewed link of a saved file, or null. A lost key or bad blob drops it; a busy Keystore only hides it. */
    fun shareLink(jobId: String): String? {
        val blob = synchronized(this) {
            readableDatabase.query("jobs", arrayOf("share_link"), "job_id = ? AND stage = 'COMPLETED'", arrayOf(jobId),
                null, null, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }
        } ?: return null
        return when (val opened = links?.open(blob) ?: return null) {
            is LinkCipher.Opened.Link -> opened.text
            LinkCipher.Opened.Unavailable -> null
            LinkCipher.Opened.Gone -> { dropShareLink(jobId); null }
        }
    }

    /** A COMPLETED row whose sealed link still opens, with its confirmed format; null otherwise. Never throws on a bad blob. */
    fun reopen(jobId: String): Reopen? {
        val row = get(jobId)?.takeIf { it.stage == JobStage.COMPLETED } ?: return null
        val link = shareLink(jobId) ?: return null
        return Reopen(link, row.format, row.preset, row.engineVersion, row.filename)
    }

    @Synchronized fun dropShareLink(jobId: String) {
        writableDatabase.update("jobs", ContentValues().apply { putNull("share_link") }, "job_id = ?", arrayOf(jobId))
    }

    /**
     * Clear link history: every blob and "Saved before" tag on ended rows, and the link a failure kept for Retry
     * (that row stops being retryable), in one transaction; then the key if no active batch still needs it.
     * Active jobs keep the link they need.
     * Under [terminalLock], so no completion seals across it.
     */
    fun clearShareLinks(): Unit = synchronized(terminalLock) {
        synchronized(this) {
            val db = writableDatabase
            val failed = mutableListOf<String>()
            db.beginTransaction()
            try {
                db.query("jobs", arrayOf("job_id"), "stage = 'FAILED' AND source_url IS NOT NULL", null, null, null, null)
                    .use { while (it.moveToNext()) failed += it.getString(0) }
                db.update("jobs", ContentValues().apply { putNull("source_url"); put("retryable", 0) },
                    "stage = 'FAILED' AND source_url IS NOT NULL", null)
                db.update("jobs", ContentValues().apply { putNull("share_link"); putNull("source_key") },
                    "stage IN ('COMPLETED','FAILED','CANCELLED') AND (share_link IS NOT NULL OR source_key IS NOT NULL)", null)
                // Finished playlists go with the links; a running one keeps the links it still has to read.
                val ended = "SELECT batch_id FROM batches WHERE state IN ('COMPLETED','STOPPED','EXPIRED')"
                db.execSQL("DELETE FROM batch_items WHERE batch_id IN ($ended)")
                db.execSQL("DELETE FROM batches WHERE batch_id IN ($ended)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            for (id in failed) get(id)?.let(onExpired)
            // Batch sources share this key. Keep it until no retained source needs to open again.
            val batchNeedsKey = db.query("batch_items", arrayOf("batch_id"), "source_url IS NOT NULL",
                null, null, null, "1").use { it.moveToFirst() }
            if (!batchNeedsKey) links?.forget()
        }
    }

    /** Remove is restricted to failures; a live job or completed publication is never discarded. */
    @Synchronized fun removeFailed(jobId: String): Boolean = (writableDatabase.delete("jobs",
        "job_id = ? AND stage = ?", arrayOf(jobId, JobStage.FAILED.name)) == 1).also { if (it) artwork.delete(jobId) }

    @Synchronized fun pruneTerminal() {
        scrubExpiredSources()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Preserve completions before pruning their last evidence, even if the feeder has not run.
            db.execSQL("UPDATE batches SET network_failures=0, refused_failures=0, consecutive_failures=0 WHERE batch_id IN " +
                "(SELECT batch_id FROM batch_items WHERE state IN ('WAITING','QUEUED','FAILED') " +
                "AND job_id IN (SELECT job_id FROM jobs WHERE stage='COMPLETED'))")
            db.execSQL("UPDATE batch_items SET state='DONE', error=NULL, retryable=0, source_url=NULL " +
                "WHERE state IN ('WAITING','QUEUED','FAILED') AND job_id IN (SELECT job_id FROM jobs WHERE stage='COMPLETED')")
            db.execSQL("""
            DELETE FROM jobs WHERE rowid IN (
                SELECT rowid FROM jobs WHERE stage IN ('COMPLETED','FAILED','CANCELLED')
                -- No sweep finds an orphaned MediaStore pending row: keep its owner until recovery resolves it.
                AND NOT (stage IN ('FAILED','CANCELLED') AND pending_uri IS NOT NULL AND pending_uri NOT LIKE '%/tree/%')
                ORDER BY updated_at DESC, rowid DESC LIMIT -1 OFFSET 100
            )
            """.trimIndent())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // Playlist items not queued yet already hold their artwork.
        val kept = all().mapTo(HashSet()) { it.jobId }
        db.rawQuery("SELECT job_id FROM batch_items", null).use { while (it.moveToNext()) kept += it.getString(0) }
        artwork.retainOnly(kept)
    }

    /** In-memory handoff of the pre-scrub source after a successful terminal write; never persisted. */
    internal var onTerminalSource: (JobRecord, JobStage) -> Unit = { _, _ -> }

    private fun transition(id: String, from: Set<JobStage>, to: JobStage, values: ContentValues = ContentValues()): Boolean {
        values.put("stage", to.name)
        values.put("updated_at", System.currentTimeMillis())
        if (to !in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED)) values.put("has_partial", 0)
        // Capture accounting data without running expiry: a received cancellation must win before any scrub.
        val source = if (to.terminal) readableDatabase.query("jobs", null, "job_id = ?", arrayOf(id), null, null, null)
            .use { if (it.moveToFirst()) read(it) else null } else null
        if (to.terminal) {
            val retain = to == JobStage.FAILED && values.getAsInteger("retryable") == 1 &&
                get(id)?.createdAt?.let { System.currentTimeMillis() - it <= CONFIRMATION_LIFETIME_MS } == true
            if (!retain) { values.putNull("source_url"); values.put("retryable", 0) }
        }
        val placeholders = from.joinToString(",") { "?" }
        val changed = writableDatabase.update("jobs", values, "job_id = ? AND stage IN ($placeholders)",
            arrayOf(id, *from.map { it.name }.toTypedArray())) == 1
        if (changed && source != null) onTerminalSource(source, to)
        return changed
    }

    private fun scrubExpiredSources() {
        val cutoff = (System.currentTimeMillis() - CONFIRMATION_LIFETIME_MS).toString()
        val live = "stage IN ('ENQUEUED','PAUSED','TRANSFERRING') AND created_at < ?"
        val db = writableDatabase
        fun expired() = db.query("jobs", arrayOf("job_id"), live, arrayOf(cutoff), null, null, null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val changed = ArrayList<String>()
        if (expired().isNotEmpty()) {
            // Another helper (own monitor) may move a row to STAGED meanwhile: only rows this UPDATE changed lose staging.
            db.beginTransaction()
            try {
                for (id in expired()) {
                    if (db.update("jobs", ContentValues().apply {
                            put("stage", JobStage.FAILED.name); put("error", DownloadError.FormatExpired.name)
                            putNull("source_url"); put("retryable", 0); put("has_partial", 0)
                            put("updated_at", System.currentTimeMillis())
                        }, "job_id = ? AND $live", arrayOf(id, cutoff)) == 1) {
                        changed += id
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        // A live writer in this process deletes its own staging once the engine has exited.
        for (id in changed) {
            infoCache.remove(id)
            if (id !in liveWriters) cleanupJobStaging(jobRoot, id)
            val row = db.query("jobs", null, "job_id = ?", arrayOf(id), null, null, null)
                .use { if (it.moveToFirst()) read(it) else null }
            if (row != null) onExpired(row)
        }
        db.execSQL("UPDATE jobs SET source_url = NULL, retryable = 0 " +
            "WHERE stage = 'FAILED' AND source_url IS NOT NULL AND created_at < ?", arrayOf(cutoff))
    }

    private fun read(cursor: Cursor): JobRecord {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return JobRecord(text("job_id"), JobStage.valueOf(text("stage")), text("source_url"), long("revision"),
            text("engine_version"), decodeFormat(text("format_json")), text("filename"), text("preset")?.let(Preset::valueOf),
            text("pending_uri")?.let(Uri::parse), text("display_name"), long("created_at"), long("updated_at"),
            text("error")?.let(DownloadError::fromName), long("retryable") == 1L,
            long("has_partial") == 1L, text("resume_recipe"), long("restart_epoch"),
            long("transport_resumes").toInt(), long("stall_restarts").toInt(), long("resume_reset_bytes"))
    }

    companion object {
        const val DATABASE_NAME = "nezzel-jobs.db"
        private const val V2_COLUMNS = "job_id, stage, source_url, revision, engine_version, format_json, filename, preset, " +
            "pending_uri, display_name, created_at, updated_at, error, retryable"

        internal const val RESUME_PROGRESS_STEP_BYTES = 1024L * 1024
        internal const val CONFIRMATION_LIFETIME_MS = 24L * 60 * 60 * 1000
        /**
         * An error that keeps its source ([DownloadError.keepsSource]: battery, refused foreground, failed conversion or local finalization) fails at once
         * but keeps its staging so Retry continues from it (has_partial stays 0 on FAILED rows: the staging bytes are the record;
         * a stop before any byte keeps nothing). Remove, Clear failed and the confirmation lifetime (startup cleanup once
         * Retry is gone) delete it.
         */
        internal fun keepsPartial(row: JobRecord) = row.stage == JobStage.FAILED &&
            (row.error?.keepsSource == true || ownsMediaStoreSave(row)) && row.retryable && row.sourceUrl != null &&
            System.currentTimeMillis() - row.createdAt <= CONFIRMATION_LIFETIME_MS
        /** A MediaStore row the provider has not let go of yet; tree receipts outlive their rows. */
        internal fun ownsMediaStoreSave(row: JobRecord) = row.pendingUri?.let { !android.provider.DocumentsContract.isTreeUri(it) } == true
        /** Process-wide, across helpers: jobs whose engine/copy may still write staging. */
        internal val liveWriters: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val DATABASE_VERSION = 8
        private const val SHARE_LINK_COLUMN = "share_link BLOB CHECK(share_link IS NULL OR stage = 'COMPLETED')"
        internal fun validJobId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))

        private fun encodeFormat(format: Format): String = JSONObject().apply {
            put("id", format.id); put("sourceId", format.sourceFormatId); put("audioId", format.audioFormatId)
            put("container", format.container); put("video", format.hasVideo); put("audio", format.hasAudio)
            put("videoCodec", format.videoCodec); put("audioCodec", format.audioCodec)
            put("width", format.width); put("height", format.height); put("bitrate", format.bitrateKbps)
            put("merge", format.requiresMerge); put("conversion", format.conversion?.name)
            format.sourceContainer?.let { put("sourceContainer", it) }
            if (format.animatedGif) put("animatedGif", true)
            if (format.sourceHasNoSound) put("sourceHasNoSound", true)
            when (val size = format.size) {
                is Size.Exact -> { put("sizeKind", "exact"); put("bytes", size.bytes) }
                is Size.Estimated -> { put("sizeKind", "estimated"); put("bytes", size.bytes) }
                Size.Unknown -> put("sizeKind", "unknown")
            }
        }.toString()

        private fun decodeFormat(encoded: String): Format = JSONObject(encoded).run {
            fun string(key: String) = if (isNull(key)) null else getString(key)
            fun int(key: String) = if (isNull(key)) null else getInt(key)
            val size = when (getString("sizeKind")) {
                "exact" -> Size.Exact(getLong("bytes"))
                "estimated" -> Size.Estimated(getLong("bytes"))
                "unknown" -> Size.Unknown
                else -> error("Invalid journal size kind")
            }
            Format(getString("id"), getString("container"), getBoolean("video"), getBoolean("audio"),
                string("videoCodec"), string("audioCodec"), int("width"), int("height"), int("bitrate"), size,
                string("audioId"), getBoolean("merge"), string("conversion")?.let(AudioConversion::valueOf), getString("sourceId"), string("sourceContainer"),
                optBoolean("animatedGif", false), optBoolean("sourceHasNoSound", false))
        }
    }
}


/** Delete one valid private job directory through the storage capability, never through a URI. */
internal fun cleanupJobStaging(root: File, id: String): Boolean = try {
    if (!JobStore.validJobId(id) || root.absoluteFile != root.canonicalFile ||
        Files.isSymbolicLink(root.toPath())) false
    else {
        val path = File(root, id)
        if (Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path.toPath())) Files.delete(path.toPath())
            else {
                check(Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS))
                JobDirectory.Job(root, path).cleanup()
            }
        }
        true
    }
} catch (_: Exception) { false }

/** Count only real files without following links. Unknown contents are validated before adoption. */
internal fun stagingBytes(directory: File): Long = try {
    if (directory.absoluteFile != directory.canonicalFile || Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) 0L
    else directory.listFiles().orEmpty().filter {
        !Files.isSymbolicLink(it.toPath()) && it.isFile && it.name != DirectResume.NAME
    }
        .fold(0L) { bytes, file -> Math.addExact(bytes, file.length()) }
} catch (_: Exception) { 0L }
