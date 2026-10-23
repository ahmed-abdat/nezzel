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
    /** Explicit fresh transfer only; terminal staging is always deleted. */
    val retryable: Boolean,
    val hasPartial: Boolean, val resumeRecipe: String?, val restartEpoch: Long,
) {
    /** Reconstruct only a still-confirmed nonterminal command. Recovery never starts it. */
    fun command(): DownloadCommand? {
        if (stage.terminal) return null
        val source = LinkRevision(LinkInput.parse(sourceUrl) ?: return null, revision)
        return (DownloadCommand.confirm(jobId, Inspection(source, engineVersion, listOf(format)), source,
            format.id, filename.substringBeforeLast('.')) as? ConfirmResult.Confirmed)?.command
    }
}

/** A completed download of the same link and format: where it was published and when. */
class SavedBefore(val uri: Uri, val savedAt: Long)

/** Blocking platform SQLite, schema v4. Older rows migrate without losing publication ownership. */
/** [sourceKeys]: keyed tags for "saved before"; null (no AndroidKeyStore) stores no tag and finds nothing. */
class JobStore(context: Context, private val sourceKeys: LinkKeyer? = KeystoreLinkKeyer.deferred) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, 4) {
    private val jobRoot = File(context.filesDir.canonicalFile, "downloads")
    private val terminalLock = Any()
    private val infoCache = dev.ahmed.savlino.engine.YtdlpInfoCache(context.noBackupFilesDir)

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
                CHECK (has_partial = 0 OR stage IN ('ENQUEUED','TRANSFERRING','PAUSED')),
                CHECK ((stage IN ('COMPLETED','CANCELLED') AND source_url IS NULL)
                    OR (stage = 'FAILED' AND (source_url IS NULL OR retryable = 1))
                    OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL)),
                CHECK (stage NOT IN ('PENDING_INSERTED','PUBLISHING','COMPLETED') OR pending_uri IS NOT NULL)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion in 1..3 && newVersion == 4) {
            // SQLiteOpenHelper owns the transaction, including the CHECK-constraint rebuild.
            db.execSQL("ALTER TABLE jobs RENAME TO jobs_old")
            onCreate(db)
            val columns = V2_COLUMNS + if (oldVersion >= 3) ", source_key" else ""
            db.execSQL("INSERT INTO jobs ($columns) SELECT $columns FROM jobs_old")
            db.execSQL("UPDATE jobs SET retryable = 0 WHERE stage = 'FAILED' AND source_url IS NULL")
            db.execSQL("DROP TABLE jobs_old")
            if (oldVersion < 3) backfillSourceKeys(db)
        } else recreate(db)
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
        if (row.stage != JobStage.FAILED || !row.retryable || row.sourceUrl == null ||
            !cleanupJobStaging(jobRoot, jobId)) return@synchronized false
        if (!infoCache.remove(jobId)) return@synchronized false
        synchronized(this) {
            // CAS also protects calls through another SQLite helper. Keep created_at: formats expire.
            writableDatabase.update("jobs", ContentValues().apply {
                put("stage", JobStage.ENQUEUED.name); put("updated_at", System.currentTimeMillis())
                putNull("error"); put("retryable", 0); putNull("pending_uri"); putNull("display_name")
            }, "job_id = ? AND stage = 'FAILED' AND retryable = 1 AND source_url IS NOT NULL AND created_at >= ?",
                arrayOf(jobId, (System.currentTimeMillis() - CONFIRMATION_LIFETIME_MS).toString())) == 1
        }
    }

    /** Remove local terminal metadata and its private staging, never a published MediaStore file. */
    fun remove(jobId: String): Boolean = synchronized(terminalLock) {
        val removed = synchronized(this) {
            writableDatabase.delete("jobs", "job_id = ? AND stage IN ('COMPLETED','FAILED','CANCELLED')",
                arrayOf(jobId)) == 1
        }
        if (removed) cleanupJobStaging(jobRoot, jobId)
        removed
    }

    @Synchronized fun startTransfer(jobId: String, recipe: String? = null): Boolean {
        scrubExpiredSources()
        return transition(jobId, setOf(JobStage.ENQUEUED), JobStage.TRANSFERRING,
            ContentValues().apply {
                put("resume_recipe", recipe)
                put("restart_epoch", Math.addExact(get(jobId)?.restartEpoch ?: 0, 1))
            })
    }
    @Synchronized internal fun restartTransfer(jobId: String): Long? {
        if (get(jobId)?.stage != JobStage.TRANSFERRING) return null
        writableDatabase.execSQL("UPDATE jobs SET restart_epoch=restart_epoch+1, has_partial=0 WHERE job_id=? AND stage='TRANSFERRING'", arrayOf(jobId))
        return get(jobId)?.restartEpoch
    }
    @Synchronized internal fun requeue(jobId: String, hasPartial: Boolean = false): Boolean {
        scrubExpiredSources()
        return transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING), JobStage.ENQUEUED,
            ContentValues().apply { put("has_partial", if (hasPartial) 1 else 0); putNull("error") })
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
        return transition(jobId, setOf(JobStage.PAUSED), JobStage.ENQUEUED)
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
        row?.pendingUri == published.uri &&
            transition(jobId, setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING), JobStage.COMPLETED,
                ContentValues().apply { put("display_name", published.displayName); key?.let { put("source_key", it) } })
    }

    /** Pending ownership must be resolved first by recovery; late cancellation cannot undo commit. */
    fun cancel(jobId: String): Boolean = finishTerminal(jobId) {
        transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED, JobStage.STAGED), JobStage.CANCELLED,
            ContentValues().apply { put("error", DownloadError.Cancelled.name) })
    }

    /** Retry keeps only a still-confirmed URL; partial bytes are never resumed. */
    fun fail(jobId: String, error: DownloadError, retryable: Boolean = retryableError(error)): Boolean = finishTerminal(jobId) {
        transition(jobId, setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED, JobStage.STAGED), JobStage.FAILED,
            ContentValues().apply { put("error", error.name); put("retryable", if (retryable && retryableError(error)) 1 else 0) })
    }

    /** Persist the failure reason if provider reconciliation must be deferred. */
    @Synchronized internal fun requestPublicationFailure(jobId: String, reason: DownloadError) {
        require(reason in setOf(DownloadError.Cancelled, DownloadError.Interrupted, DownloadError.Network,
            DownloadError.InsufficientSpace, DownloadError.FolderUnavailable))
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
            "job_id = ? AND stage = 'FAILED' AND pending_uri = ?", arrayOf(jobId, uri.toString()))
    }

    /** Only call after the publisher confirms absence; retain the URI until that acknowledgement. */
    internal fun publicationAbandoned(jobId: String): Boolean = finishTerminal(jobId) {
        val reason = get(jobId)?.error
        val cancelled = reason == DownloadError.Cancelled
        transition(jobId, setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING),
            if (cancelled) JobStage.CANCELLED else JobStage.FAILED, ContentValues().apply {
                put("error", if (cancelled) DownloadError.Cancelled.name
                    else if (reason in setOf(DownloadError.Interrupted, DownloadError.Network,
                        DownloadError.InsufficientSpace, DownloadError.FolderUnavailable)) reason!!.name else DownloadError.SaveFailed.name)
                put("retryable", if (!cancelled) 1 else 0); putNull("pending_uri")
            })
    }

    // SQLite's lock ends before filesystem work. Cleanup failure is retried at startup.
    private fun finishTerminal(jobId: String, update: () -> Boolean): Boolean = synchronized(terminalLock) {
        val changed = synchronized(this) { update() }
        if (changed) {
            infoCache.remove(jobId)
            cleanupJobStaging(jobRoot, jobId)
        }
        changed
    }

    /** Remove is restricted to failures; a live job or completed publication is never discarded. */
    @Synchronized fun removeFailed(jobId: String): Boolean = writableDatabase.delete("jobs",
        "job_id = ? AND stage = ?", arrayOf(jobId, JobStage.FAILED.name)) == 1

    @Synchronized fun pruneTerminal() {
        scrubExpiredSources()
        writableDatabase.execSQL("""
            DELETE FROM jobs WHERE rowid IN (
                SELECT rowid FROM jobs WHERE stage IN ('COMPLETED','FAILED','CANCELLED')
                ORDER BY updated_at DESC, rowid DESC LIMIT -1 OFFSET 100
            )
        """.trimIndent())
    }

    private fun transition(id: String, from: Set<JobStage>, to: JobStage, values: ContentValues = ContentValues()): Boolean {
        values.put("stage", to.name)
        values.put("updated_at", System.currentTimeMillis())
        if (to !in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED)) values.put("has_partial", 0)
        if (to.terminal) {
            val retain = to == JobStage.FAILED && values.getAsInteger("retryable") == 1 &&
                get(id)?.createdAt?.let { System.currentTimeMillis() - it <= CONFIRMATION_LIFETIME_MS } == true
            if (!retain) { values.putNull("source_url"); values.put("retryable", 0) }
        }
        val placeholders = from.joinToString(",") { "?" }
        return writableDatabase.update("jobs", values, "job_id = ? AND stage IN ($placeholders)",
            arrayOf(id, *from.map { it.name }.toTypedArray())) == 1
    }

    private fun scrubExpiredSources() {
        val cutoff = System.currentTimeMillis() - CONFIRMATION_LIFETIME_MS
        val expired = writableDatabase.query("jobs", arrayOf("job_id"),
            "stage IN ('ENQUEUED','PAUSED','TRANSFERRING') AND created_at < ?", arrayOf(cutoff.toString()),
            null, null, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        writableDatabase.execSQL("UPDATE jobs SET stage='FAILED', error='FormatExpired', source_url=NULL, " +
            "retryable=0, has_partial=0, updated_at=? WHERE stage IN ('ENQUEUED','PAUSED','TRANSFERRING') AND created_at < ?",
            arrayOf(System.currentTimeMillis(), cutoff))
        for (id in expired) { infoCache.remove(id); cleanupJobStaging(jobRoot, id) }
        writableDatabase.execSQL("UPDATE jobs SET source_url = NULL, retryable = 0 " +
            "WHERE stage = 'FAILED' AND source_url IS NOT NULL AND created_at < ?", arrayOf(cutoff))
    }

    private fun read(cursor: Cursor): JobRecord {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return JobRecord(text("job_id"), JobStage.valueOf(text("stage")), text("source_url"), long("revision"),
            text("engine_version"), decodeFormat(text("format_json")), text("filename"), text("preset")?.let(Preset::valueOf),
            text("pending_uri")?.let(Uri::parse), text("display_name"), long("created_at"), long("updated_at"),
            text("error")?.let(DownloadError::valueOf), long("retryable") == 1L,
            long("has_partial") == 1L, text("resume_recipe"), long("restart_epoch"))
    }

    companion object {
        const val DATABASE_NAME = "nezzel-jobs.db"
        private const val V2_COLUMNS = "job_id, stage, source_url, revision, engine_version, format_json, filename, preset, " +
            "pending_uri, display_name, created_at, updated_at, error, retryable"

        internal const val CONFIRMATION_LIFETIME_MS = 24L * 60 * 60 * 1000
        internal fun retryableError(error: DownloadError) = error in setOf(DownloadError.Network,
            DownloadError.Offline, DownloadError.InsufficientSpace, DownloadError.MergeFailed,
            DownloadError.SaveFailed, DownloadError.FolderUnavailable, DownloadError.ExecutionRestricted, DownloadError.Interrupted,
            DownloadError.Failed, DownloadError.EngineUnavailable)
        internal fun validJobId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))

        private fun encodeFormat(format: Format): String = JSONObject().apply {
            put("id", format.id); put("sourceId", format.sourceFormatId); put("audioId", format.audioFormatId)
            put("container", format.container); put("video", format.hasVideo); put("audio", format.hasAudio)
            put("videoCodec", format.videoCodec); put("audioCodec", format.audioCodec)
            put("width", format.width); put("height", format.height); put("bitrate", format.bitrateKbps)
            put("merge", format.requiresMerge); put("conversion", format.conversion?.name)
            format.sourceContainer?.let { put("sourceContainer", it) }
            if (format.animatedGif) put("animatedGif", true)
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
                optBoolean("animatedGif", false))
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
    else directory.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
        .fold(0L) { bytes, file -> Math.addExact(bytes, file.length()) }
} catch (_: Exception) { 0L }
