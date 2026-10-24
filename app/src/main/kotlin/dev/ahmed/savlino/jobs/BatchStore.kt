package dev.ahmed.savlino.jobs

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.PlaylistItem
import dev.ahmed.savlino.storage.KeystoreLinkCipher
import dev.ahmed.savlino.storage.LinkCipher
import java.util.UUID

// These records deliberately have no generated URL/title-bearing toString.
enum class BatchState { ACTIVE, PAUSED, WAITING_NETWORK, STOPPED, COMPLETED, EXPIRED }
enum class BatchItemState { WAITING, QUEUED, DONE, FAILED, SKIPPED }
class BatchRecord(val id: String, val title: String, val preset: Preset, val state: BatchState,
    val createdAt: Long, val error: DownloadError?)
class BatchItemRecord(val batchId: String, val position: Int, val itemId: String, val title: String,
    val state: BatchItemState, val jobId: String, val error: DownloadError?, val retryable: Boolean,
    /** A BALANCED request had to use SMALL; the later batch card can disclose this. */
    val fallback: Boolean)

/** Same database/upgrade owner as jobs; source URLs are authenticated ciphertext, never a plaintext fallback. */
class BatchStore internal constructor(internal val jobs: JobStore, private val cipher: LinkCipher = KeystoreLinkCipher.current(),
    private val now: () -> Long = System::currentTimeMillis) {
    private val db get() = jobs.writableDatabase

    /** Call only after the one explicit Download-N confirmation. Nothing is persisted while selecting. */
    @Synchronized fun create(id: String, title: String, preset: Preset, items: List<PlaylistItem>): Boolean {
        if (!JobStore.validJobId(id) || items.isEmpty() || items.size > 200 || items.any { !it.selectable } ||
            items.map { it.id }.distinct().size != items.size) return false
        val sealed = items.map { item ->
            val link = LinkInput.parse(item.sourceUrl) ?: return false
            if (YouTubeLinkKind.classify(link) != YouTubeLinkKind.Video) return false
            cipher.seal(link.rawUrl) ?: return false
        }
        db.beginTransaction()
        try {
            if (get(id) != null) return false
            db.insertOrThrow("batches", null, ContentValues().apply {
                put("batch_id", id); put("title", PreviewMetadata.text(title, 300).orEmpty()); put("preset", preset.name)
                put("state", BatchState.ACTIVE.name); put("created_at", now())
            })
            items.forEachIndexed { position, item ->
                db.insertOrThrow("batch_items", null, ContentValues().apply {
                    put("batch_id", id); put("position", position); put("item_id", item.id); put("title", PreviewMetadata.text(item.title, 300).orEmpty())
                    put("source_url", sealed[position]); put("state", BatchItemState.WAITING.name)
                    // Allocate before inspection/enqueue. Crash recovery adopts this exact ID, never a second job.
                    put("job_id", "batch-${UUID.randomUUID()}")
                })
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun get(id: String): BatchRecord? = db.query("batches", null, "batch_id=?", arrayOf(id), null, null, null)
        .use { if (it.moveToFirst()) batch(it) else null }
    @Synchronized fun all(): List<BatchRecord> = db.query("batches", null, null, null, null, null, "created_at, rowid")
        .use { c -> buildList { while (c.moveToNext()) add(batch(c)) } }
    @Synchronized fun items(id: String): List<BatchItemRecord> = db.query("batch_items", null, "batch_id=?", arrayOf(id), null, null, "position")
        .use { c -> buildList { while (c.moveToNext()) add(item(c)) } }

    /** Unavailable Keystore is transient; a lost key is permanent and its blob is discarded. */
    @Synchronized internal fun source(item: BatchItemRecord): String? {
        val blob = db.query("batch_items", arrayOf("source_url"), "batch_id=? AND position=?", args(item), null, null, null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null } ?: return null
        return when (val opened = cipher.open(blob)) {
            is LinkCipher.Opened.Link -> opened.text
            LinkCipher.Opened.Unavailable -> null
            LinkCipher.Opened.Gone -> { finish(item, BatchItemState.FAILED, DownloadError.FormatExpired); null }
        }
    }

    @Synchronized internal fun queued(item: BatchItemRecord, fallback: Boolean = false) {
        db.update("batch_items", ContentValues().apply {
            put("state", BatchItemState.QUEUED.name); put("fallback", if (fallback) 1 else 0); putNull("error"); put("retryable", 0)
        }, "batch_id=? AND position=? AND state='WAITING'", args(item))
    }

    /** Exactly-once terminal reconciliation also persists breaker streaks across process death. */
    @Synchronized internal fun finish(item: BatchItemRecord, state: BatchItemState, error: DownloadError? = null,
        retryable: Boolean = false): Boolean {
        require(state in setOf(BatchItemState.DONE, BatchItemState.FAILED, BatchItemState.SKIPPED))
        db.beginTransaction()
        try {
            val keep = retryable && get(item.batchId)?.let { now() - it.createdAt < LIFETIME_MS } == true
            val allowed = if (state == BatchItemState.DONE) "('WAITING','QUEUED','FAILED')" else "('WAITING','QUEUED')"
            val changed = db.update("batch_items", ContentValues().apply {
                put("state", state.name); put("error", error?.name); put("retryable", if (keep) 1 else 0)
                if (!keep) putNull("source_url")
            }, "batch_id=? AND position=? AND state IN $allowed", args(item)) == 1
            if (changed) {
                val network = error in setOf(DownloadError.Network, DownloadError.Offline)
                val refused = error in setOf(DownloadError.SourceRefused, DownloadError.LoginRequired)
                db.execSQL("UPDATE batches SET network_failures=" + (if (network) "network_failures+1" else "0") +
                    ", refused_failures=" + (if (refused) "refused_failures+1" else "0") +
                    ", consecutive_failures=" + (if (state == BatchItemState.FAILED) "consecutive_failures+1" else "0") +
                    " WHERE batch_id=?", arrayOf(item.batchId))
                db.execSQL("UPDATE batches SET state=CASE WHEN network_failures>=5 THEN 'WAITING_NETWORK' ELSE 'PAUSED' END, error=? " +
                    "WHERE batch_id=? AND state='ACTIVE' AND (network_failures>=5 OR refused_failures>=3 OR consecutive_failures>=5 OR ?='InsufficientSpace')",
                    arrayOf(error?.name, item.batchId, error?.name))
            }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    @Synchronized internal fun state(id: String, state: BatchState, error: DownloadError? = null): Boolean {
        val from = get(id)?.state ?: return false
        if (from in setOf(BatchState.STOPPED, BatchState.EXPIRED) ||
            (from == BatchState.COMPLETED && state != BatchState.ACTIVE)) return false
        return db.update("batches", ContentValues().apply {
            put("state", state.name); put("error", error?.name)
            if (state == BatchState.ACTIVE) { put("network_failures", 0); put("refused_failures", 0); put("consecutive_failures", 0) }
        }, "batch_id=?", arrayOf(id)) == 1
    }

    @Synchronized internal fun stop(id: String): List<String> {
        db.beginTransaction()
        try {
            if (!state(id, BatchState.STOPPED)) return emptyList()
            // An enqueue may have committed before its WAITING item was reconciled.
            db.execSQL("UPDATE batch_items SET state='QUEUED' WHERE batch_id=? AND state='WAITING' AND job_id IN " +
                "(SELECT job_id FROM jobs WHERE stage NOT IN ('COMPLETED','FAILED','CANCELLED'))", arrayOf(id))
            db.update("batch_items", ContentValues().apply {
                put("state", BatchItemState.SKIPPED.name); put("error", DownloadError.Cancelled.name)
            }, "batch_id=? AND state='WAITING'", arrayOf(id))
            db.update("batch_items", ContentValues().apply { putNull("source_url"); put("retryable", 0) }, "batch_id=?", arrayOf(id))
            val ids = items(id).filter { it.state == BatchItemState.QUEUED }.map { it.jobId }
            db.setTransactionSuccessful()
            return ids
        } finally { db.endTransaction() }
    }

    @Synchronized internal fun requestRetries(id: String): Boolean {
        scrubExpired()
        val row = get(id) ?: return false
        if (row.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) || now() - row.createdAt >= LIFETIME_MS) return false
        return db.update("batch_items", ContentValues().apply {
            put("state", BatchItemState.WAITING.name); putNull("error")
        }, "batch_id=? AND state='FAILED' AND retryable=1 AND source_url IS NOT NULL", arrayOf(id)) > 0
    }

    @Synchronized internal fun finishIfIdle(id: String) {
        if (items(id).none { it.state in setOf(BatchItemState.WAITING, BatchItemState.QUEUED) })
            db.execSQL("UPDATE batches SET state='COMPLETED' WHERE batch_id=? AND state IN ('ACTIVE','PAUSED','WAITING_NETWORK')", arrayOf(id))
    }

    @Synchronized fun scrubExpired() {
        val cutoff = now() - LIFETIME_MS
        db.beginTransaction()
        try {
            db.execSQL("UPDATE batches SET state='EXPIRED', error='FormatExpired' WHERE created_at<=? " +
                "AND state IN ('ACTIVE','PAUSED','WAITING_NETWORK') AND EXISTS " +
                "(SELECT 1 FROM batch_items WHERE batch_items.batch_id=batches.batch_id AND state='WAITING' " +
                "AND job_id NOT IN (SELECT job_id FROM jobs))", arrayOf(cutoff))
            db.execSQL("UPDATE batch_items SET state='SKIPPED', error='FormatExpired' WHERE state='WAITING' AND batch_id IN " +
                "(SELECT batch_id FROM batches WHERE created_at<=?) AND job_id NOT IN (SELECT job_id FROM jobs)", arrayOf(cutoff))
            db.execSQL("UPDATE batch_items SET source_url=NULL, retryable=0 WHERE batch_id IN (SELECT batch_id FROM batches WHERE created_at<=?)", arrayOf(cutoff))
            val expiredHistory = "SELECT batch_id FROM batches WHERE created_at<=? AND state IN ('COMPLETED','STOPPED','EXPIRED')"
            val retentionCutoff = arrayOf<Any>(now() - RETENTION_MS)
            // Foreign keys are disabled: explicitly remove children in the same transaction.
            db.execSQL("DELETE FROM batch_items WHERE batch_id IN ($expiredHistory)", retentionCutoff)
            db.execSQL("DELETE FROM batches WHERE batch_id IN ($expiredHistory)", retentionCutoff)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun args(item: BatchItemRecord) = arrayOf(item.batchId, item.position.toString())
    private fun batch(c: Cursor) = BatchRecord(c.text("batch_id"), c.text("title"), Preset.valueOf(c.text("preset")),
        BatchState.valueOf(c.text("state")), c.getLong(c.getColumnIndexOrThrow("created_at")), c.optional("error")?.let(DownloadError::valueOf))
    private fun item(c: Cursor) = BatchItemRecord(c.text("batch_id"), c.getInt(c.getColumnIndexOrThrow("position")), c.text("item_id"), c.text("title"),
        BatchItemState.valueOf(c.text("state")), c.text("job_id"), c.optional("error")?.let(DownloadError::valueOf),
        c.getInt(c.getColumnIndexOrThrow("retryable")) == 1, c.getInt(c.getColumnIndexOrThrow("fallback")) == 1)
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optional(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    companion object {
        internal const val LIFETIME_MS = 86_400_000L
        internal const val RETENTION_MS = 30L * LIFETIME_MS
        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS batches (
                batch_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, preset TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('ACTIVE','PAUSED','WAITING_NETWORK','STOPPED','COMPLETED','EXPIRED')),
                created_at INTEGER NOT NULL, error TEXT,
                network_failures INTEGER NOT NULL DEFAULT 0, refused_failures INTEGER NOT NULL DEFAULT 0,
                consecutive_failures INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS batch_items (
                batch_id TEXT NOT NULL REFERENCES batches(batch_id), position INTEGER NOT NULL,
                item_id TEXT NOT NULL, source_url BLOB, title TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('WAITING','QUEUED','DONE','FAILED','SKIPPED')),
                job_id TEXT NOT NULL UNIQUE, error TEXT, retryable INTEGER NOT NULL DEFAULT 0,
                fallback INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(batch_id,position), UNIQUE(batch_id,item_id))""")
        }
    }
}
