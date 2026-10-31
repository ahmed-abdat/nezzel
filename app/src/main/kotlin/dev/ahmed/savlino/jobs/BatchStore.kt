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
    /** The requested preset was unavailable: BALANCED used SMALL, or AUDIO_M4A used AUDIO_MP3. */
    val fallback: Boolean,
    /** Its sealed link is still stored, so a person's Retry can resolve it again. */
    val hasSource: Boolean = retryable)

/** Same database/upgrade owner as jobs; source URLs are authenticated ciphertext, never a plaintext fallback. */
class BatchStore internal constructor(internal val jobs: JobStore, private val cipher: LinkCipher = KeystoreLinkCipher.current(),
    private val now: () -> Long = System::currentTimeMillis) {
    private val db get() = jobs.database

    /** Call only after the one explicit Download-N confirmation. Nothing is persisted while selecting. */
    @Synchronized fun create(id: String, title: String, preset: Preset, items: List<PlaylistItem>): Boolean {
        if (items.any { !it.selectable }) return false
        val links = items.map { item ->
            val link = LinkInput.parse(item.sourceUrl) ?: return false
            if (YouTubeLinkKind.classify(link) != YouTubeLinkKind.Video) return false
            Triple(item.id, item.title, link)
        }
        return insert(id, title, preset, links)
    }

    /**
     * Pasted/shared links, after the same single confirmation. Any validated link; titles are the host only,
     * so no path or query is ever stored in plaintext. Item ids carry [LINK_ITEM] so presentation can tell them apart.
     */
    @Synchronized fun createLinks(id: String, title: String, links: List<ValidatedLink>, preset: Preset): Boolean =
        insert(id, title, preset, links.mapIndexed { position, link ->
            Triple("$LINK_ITEM$position", MultiLinkLabel.host(link), link)
        })

    private fun insert(id: String, title: String, preset: Preset, items: List<Triple<String, String, ValidatedLink>>): Boolean {
        if (!JobStore.validJobId(id) || items.isEmpty() || items.size > 200 ||
            items.map { it.first }.distinct().size != items.size) return false
        val sealed = items.map { cipher.seal(it.third.rawUrl) ?: return false }
        db.beginTransaction()
        try {
            if (get(id) != null) return false
            db.insertOrThrow("batches", null, ContentValues().apply {
                put("batch_id", id); put("title", PreviewMetadata.text(title, 300).orEmpty()); put("preset", preset.name)
                put("state", BatchState.ACTIVE.name); put("created_at", now())
            })
            items.forEachIndexed { position, (itemId, itemTitle) ->
                db.insertOrThrow("batch_items", null, ContentValues().apply {
                    put("batch_id", id); put("position", position); put("item_id", itemId); put("title", PreviewMetadata.text(itemTitle, 300).orEmpty())
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

    /** Read-only notification membership lookup, including the interval before QUEUED reconciliation. */
    @Synchronized fun containsJob(jobId: String): Boolean = db.query("batch_items", arrayOf("job_id"),
        "job_id=?", arrayOf(jobId), null, null, null, "1").use { it.moveToFirst() }

    /** "b:<batch>:<position>" for a playlist video, the same across the new job ids its retries get; null for a single link. */
    @Synchronized fun countKey(jobId: String): String? = db.query("batch_items", arrayOf("batch_id", "position"),
        "job_id=?", arrayOf(jobId), null, null, null, "1").use { if (it.moveToFirst()) "b:${it.getString(0)}:${it.getInt(1)}" else null }

    /** Whether the playlist video behind a [countKey] still has its row. */
    @Synchronized fun hasCountKey(key: String): Boolean {
        val batch = key.removePrefix("b:").substringBeforeLast(':')
        val position = key.substringAfterLast(':')
        return db.query("batch_items", arrayOf("job_id"), "batch_id=? AND position=?", arrayOf(batch, position),
            null, null, null, "1").use { it.moveToFirst() }
    }

    /** Whether a retry gave [jobId]'s video a new job id (the automatic fresh one or a person's Retry) and no row still uses it. */
    @Synchronized fun replaced(jobId: String): Boolean {
        if (containsJob(jobId)) return false
        val base = jobId.replace(USER_RETRY, "")
        return db.query("batch_items", arrayOf("job_id"), "substr(job_id, 1, ?)=?", arrayOf(base.length.toString(), base),
            null, null, null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }
                .any { it == jobId + FRESH_SUFFIX || REPLACEMENT.matches(it.substring(base.length)) }
        }
    }

    @Synchronized internal fun canRefresh(item: BatchItemRecord): Boolean {
        if (isLinkItem(item.itemId) || item.jobId.endsWith(FRESH_SUFFIX)) return false
        val batch = get(item.batchId) ?: return false
        if (batch.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) || now() - batch.createdAt >= 2 * LIFETIME_MS) return false
        return db.query("batch_items", arrayOf("job_id"),
            "batch_id=? AND position=? AND job_id=? AND source_url IS NOT NULL",
            arrayOf(item.batchId, item.position.toString(), item.jobId), null, null, null).use { it.moveToFirst() }
    }

    /**
     * The allocated job id also journals the one automatic fresh attempt. Rotate before inspecting so
     * death in the inspect/enqueue gap cannot restore the budget. Never replace a live/publishing job.
     */
    @Synchronized internal fun refresh(item: BatchItemRecord): Boolean {
        db.beginTransaction()
        try {
            val batch = get(item.batchId) ?: return false
            if (batch.state !in setOf(BatchState.ACTIVE, BatchState.COMPLETED) || !canRefresh(item)) return false
            val changed = db.update("batch_items", ContentValues().apply {
                put("job_id", item.jobId + FRESH_SUFFIX); put("state", BatchItemState.WAITING.name)
                putNull("error"); put("retryable", 0)
            }, "batch_id=? AND position=? AND job_id=? AND source_url IS NOT NULL " +
                "AND state IN ('WAITING','QUEUED','FAILED') AND NOT EXISTS " +
                "(SELECT 1 FROM jobs WHERE job_id=? AND (stage!='FAILED' OR pending_uri IS NOT NULL))",
                arrayOf(item.batchId, item.position.toString(), item.jobId, item.jobId)) == 1
            if (changed) db.execSQL("UPDATE batches SET state='ACTIVE' WHERE batch_id=?", arrayOf(item.batchId))
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    /** A failure recorded before links outlived failures: reseal the job's own link while the batch is alive. */
    @Synchronized internal fun adoptSource(item: BatchItemRecord, link: String): Boolean {
        if (item.state != BatchItemState.FAILED || item.hasSource) return false
        val batch = get(item.batchId) ?: return false
        if (batch.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) || now() - batch.createdAt >= LIFETIME_MS) return false
        val sealed = cipher.seal(link) ?: return false
        return db.update("batch_items", ContentValues().apply { put("source_url", sealed) },
            "batch_id=? AND position=? AND job_id=? AND state='FAILED' AND source_url IS NULL",
            arrayOf(item.batchId, item.position.toString(), item.jobId)) == 1
    }

    /** Unavailable Keystore is transient; a lost key is permanent and its blob is discarded. */
    @Synchronized internal fun source(item: BatchItemRecord): String? {
        val blob = db.query("batch_items", arrayOf("source_url"), "batch_id=? AND position=?", args(item), null, null, null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null } ?: return null
        return when (val opened = cipher.open(blob)) {
            is LinkCipher.Opened.Link -> opened.text
            LinkCipher.Opened.Unavailable -> null
            LinkCipher.Opened.Gone -> {
                // The key is gone, so this sealed link can never open again: drop it.
                finish(item, BatchItemState.FAILED, DownloadError.ExecutionRestricted)
                db.update("batch_items", ContentValues().apply { putNull("source_url") }, "batch_id=? AND position=?", args(item))
                null
            }
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
            val alive = get(item.batchId)?.let {
                now() - it.createdAt < (if (item.jobId.endsWith(FRESH_SUFFIX)) 2 * LIFETIME_MS else LIFETIME_MS)
            } == true
            val keep = retryable && alive
            val allowed = if (state == BatchItemState.DONE) "('WAITING','QUEUED','FAILED')" else "('WAITING','QUEUED')"
            val changed = db.update("batch_items", ContentValues().apply {
                put("state", state.name); put("error", error?.name); put("retryable", if (keep) 1 else 0)
                // A failed video keeps its sealed link for the person's own Retry until the batch lifetime scrubs it.
                if (!(state == BatchItemState.FAILED && alive)) putNull("source_url")
            }, "batch_id=? AND position=? AND state IN $allowed", args(item)) == 1
            if (changed) {
                val network = error?.spendsResumeBudget == true
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

    @Synchronized internal fun requestRetries(id: String, automatic: Boolean = false): Boolean {
        scrubExpired()
        val row = get(id) ?: return false
        val age = now() - row.createdAt
        if (row.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) || age >= 2 * LIFETIME_MS) return false
        val eligible = (if (age >= LIFETIME_MS) " AND job_id LIKE '%$FRESH_SUFFIX'" else "") +
            (if (automatic) " AND job_id NOT LIKE '%$FRESH_SUFFIX'" else "")
        db.beginTransaction()
        try {
            val changed = db.update("batch_items", ContentValues().apply {
                put("state", BatchItemState.WAITING.name); putNull("error")
            }, "batch_id=? AND state='FAILED' AND retryable=1 AND source_url IS NOT NULL" + eligible, arrayOf(id)) > 0
            // Activate with the reservation: death before the caller's own activation must not strand WAITING rows.
            if (changed) state(id, BatchState.ACTIVE)
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    /**
     * A person's Retry (the whole batch, or one video at [position]): every failed video whose link is still stored, whatever
     * the automatic retryable flag says. Retryable ones go back as they are; the others get a new fresh job id, so the feeder
     * resolves the link again instead of reusing the confirmation that failed. Never a stopped/expired batch or one past its
     * lifetime. Returns whether anything changed and the replaced job ids (their failed rows are removed by the caller).
     */
    @Synchronized internal fun requestUserRetries(id: String, position: Int? = null): Pair<Boolean, List<String>> {
        scrubExpired()
        val row = get(id) ?: return false to emptyList()
        val age = now() - row.createdAt
        if (row.state in setOf(BatchState.STOPPED, BatchState.EXPIRED) || age >= 2 * LIFETIME_MS) return false to emptyList()
        val at = if (position != null) " AND position=${position.toInt()}" else ""
        db.beginTransaction()
        try {
            val revived = requestRetriesAt(id, age, at)
            val replaced = items(id).filter { it.state == BatchItemState.FAILED && it.hasSource && (position == null || it.position == position) &&
                (!it.retryable || (age >= LIFETIME_MS && !it.jobId.endsWith(FRESH_SUFFIX))) }
                .filter { item ->
                    db.update("batch_items", ContentValues().apply {
                        put("job_id", userJobId(item.jobId)); put("state", BatchItemState.WAITING.name); putNull("error"); put("retryable", 0)
                    }, "batch_id=? AND position=? AND job_id=? AND state='FAILED' AND source_url IS NOT NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM jobs WHERE job_id=? AND (stage!='FAILED' OR pending_uri IS NOT NULL))",
                        arrayOf(item.batchId, item.position.toString(), item.jobId, item.jobId)) == 1
                }.map { it.jobId }
            if (revived || replaced.isNotEmpty()) state(id, BatchState.ACTIVE)
            db.setTransactionSuccessful()
            return (revived || replaced.isNotEmpty()) to replaced
        } finally { db.endTransaction() }
    }

    private fun requestRetriesAt(id: String, age: Long, at: String): Boolean = db.update("batch_items", ContentValues().apply {
        put("state", BatchItemState.WAITING.name); putNull("error")
    }, "batch_id=? AND state='FAILED' AND retryable=1 AND source_url IS NOT NULL" +
        (if (age >= LIFETIME_MS) " AND job_id LIKE '%$FRESH_SUFFIX'" else "") + at, arrayOf(id)) > 0

    /** The base id plus "-u<time>" and the fresh suffix: unique per tap, and fresh so the feeder skips cached formats. */
    private fun userJobId(jobId: String) = jobId.replace(USER_RETRY, "") + "-u" + now() + FRESH_SUFFIX

    @Synchronized internal fun finishIfIdle(id: String) {
        val items = items(id)
        if (get(id)?.state == BatchState.WAITING_NETWORK && items.any {
                it.state == BatchItemState.FAILED && it.retryable && it.hasSource
            }) return
        if (items.none { it.state in setOf(BatchItemState.WAITING, BatchItemState.QUEUED) })
            db.execSQL("UPDATE batches SET state='COMPLETED' WHERE batch_id=? AND state IN ('ACTIVE','PAUSED','WAITING_NETWORK')", arrayOf(id))
    }

    @Synchronized fun scrubExpired() {
        val cutoff = now() - LIFETIME_MS
        // A started playlist may refresh its confirmation once; unstarted rows still expire at 24h.
        // The second day bounds sealed-source retention even if death happens before fresh enqueue.
        val refreshable = "item_id NOT LIKE 'link-%' AND (job_id IN (SELECT job_id FROM jobs) OR job_id LIKE '%$FRESH_SUFFIX')"
        val retained = "(state IN ('WAITING','QUEUED','FAILED') AND $refreshable AND batch_id IN (SELECT batch_id FROM batches WHERE created_at>?))"
        val refreshCutoff = now() - 2 * LIFETIME_MS
        db.beginTransaction()
        try {
            db.execSQL("UPDATE batches SET state='EXPIRED', error='Interrupted' WHERE created_at<=? " +
                "AND state IN ('ACTIVE','PAUSED','WAITING_NETWORK') AND EXISTS " +
                "(SELECT 1 FROM batch_items WHERE batch_items.batch_id=batches.batch_id AND state='WAITING' " +
                "AND job_id NOT IN (SELECT job_id FROM jobs) AND job_id NOT LIKE '%$FRESH_SUFFIX') " +
                "AND NOT EXISTS (SELECT 1 FROM batch_items WHERE batch_items.batch_id=batches.batch_id " +
                "AND source_url IS NOT NULL AND $refreshable)", arrayOf(cutoff))
            db.execSQL("UPDATE batch_items SET state='SKIPPED', error='Interrupted' WHERE state='WAITING' AND batch_id IN " +
                "(SELECT batch_id FROM batches WHERE created_at<=?) AND job_id NOT IN (SELECT job_id FROM jobs) " +
                "AND NOT $retained", arrayOf(cutoff, refreshCutoff))
            db.execSQL("UPDATE batch_items SET source_url=NULL, retryable=0 WHERE batch_id IN " +
                "(SELECT batch_id FROM batches WHERE created_at<=?) AND NOT $retained", arrayOf(cutoff, refreshCutoff))
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
        BatchState.valueOf(c.text("state")), c.getLong(c.getColumnIndexOrThrow("created_at")), c.optional("error")?.let(DownloadError::fromName))
    private fun item(c: Cursor) = BatchItemRecord(c.text("batch_id"), c.getInt(c.getColumnIndexOrThrow("position")), c.text("item_id"), c.text("title"),
        BatchItemState.valueOf(c.text("state")), c.text("job_id"), c.optional("error")?.let(DownloadError::fromName),
        c.getInt(c.getColumnIndexOrThrow("retryable")) == 1, c.getInt(c.getColumnIndexOrThrow("fallback")) == 1,
        !c.isNull(c.getColumnIndexOrThrow("source_url")))
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optional(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    companion object {
        internal const val LIFETIME_MS = 86_400_000L
        internal const val RETENTION_MS = 30L * LIFETIME_MS
        internal const val LINK_ITEM = "link-"
        // "link-" + a position up to 199: at most 8 characters, so an 11-character YouTube video id never matches.
        private val LINK_ITEM_ID = Regex("link-\\d{1,3}")
        internal fun isLinkItem(itemId: String) = LINK_ITEM_ID.matches(itemId)
        internal const val FRESH_SUFFIX = "-fresh"
        private val USER_RETRY = Regex("(-u\\d+)?(-fresh)?$")
        private val REPLACEMENT = Regex("-u\\d+-fresh")
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
