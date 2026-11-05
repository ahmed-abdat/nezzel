package dev.ahmed.savlino.jobs

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.ahmed.savlino.domain.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every shipped version upgrades, step by step, to the shape a fresh install gets, and keeps its rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class JobStoreSchemaTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun clean() { app.deleteDatabase(JobStore.DATABASE_NAME) }

    @Test fun everyShippedVersionReachesTheFreshShape() {
        val fresh = JobStore(app).use { shape(it.writableDatabase) }
        for (version in 4 until JobStore.DATABASE_VERSION) {
            app.deleteDatabase(JobStore.DATABASE_NAME)
            shipped(version)
            JobStore(app).use { store ->
                val db = store.writableDatabase
                assertEquals("v$version", JobStore.DATABASE_VERSION, db.version)
                assertEquals("v$version", fresh, shape(db))
                db.rawQuery("SELECT stage, filename FROM jobs WHERE job_id = 'kept'", null).use {
                    check(it.moveToFirst()) { "v$version lost its job" }
                    assertEquals("COMPLETED", it.getString(0)); assertEquals("clip.mp4", it.getString(1))
                }
            }
        }
    }

    @Test fun versionEightKeepsEveryColumnAndBatchWhileAllowingExpiredFormatLinks() {
        shipped(8)
        val before = SQLiteDatabase.openDatabase(app.getDatabasePath(JobStore.DATABASE_NAME).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { db ->
            val now = System.currentTimeMillis()
            db.execSQL("DELETE FROM jobs")
            for ((index, stage) in listOf("FAILED", "COMPLETED", "ENQUEUED").withIndex()) {
                db.execSQL("""INSERT INTO jobs (job_id, stage, source_url, revision, engine_version,
                    format_json, filename, preset, pending_uri, display_name, created_at, updated_at,
                    error, retryable, source_key, has_partial, resume_recipe, restart_epoch,
                    transport_resumes, stall_restarts, resume_reset_bytes, share_link)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(
                    stage, stage, if (stage == "COMPLETED") null else "https://example.com/$stage", index + 7,
                    "engine-$index", """{"id":"format-$index","sourceId":"source-$index","container":"mp4",
                        "video":true,"audio":true,"merge":false,"sizeKind":"unknown"}""", "clip-$index.mp4", "BEST",
                    if (stage == "COMPLETED") "content://media/7" else null,
                    if (stage == "COMPLETED") "saved.mp4" else null, now - index, now + index,
                    if (stage == "FAILED") "Network" else null, if (stage == "FAILED") 1 else 0,
                    "source-key-$index", if (stage == "ENQUEUED") 1 else 0, "recipe-$index", index + 2,
                    index + 3, index + 4, 123456789L + index, if (stage == "COMPLETED") byteArrayOf(0, 1, -1) else null))
            }
            db.execSQL("""INSERT INTO batches (batch_id, title, preset, state, created_at,
                network_failures, refused_failures, consecutive_failures)
                VALUES ('batch', 'kept batch', 'BEST', 'ACTIVE', ?, 2, 3, 4)""", arrayOf(now))
            db.execSQL("""INSERT INTO batch_items (batch_id, position, item_id, source_url, title,
                state, job_id, error, retryable, fallback)
                VALUES ('batch', 0, 'item', ?, 'kept item', 'FAILED', 'FAILED', 'Network', 1, 1)""",
                arrayOf(byteArrayOf(2, 0, -1)))
            db.rawQuery("PRAGMA foreign_key_list(batch_items)", null).use {
                check(it.moveToFirst())
                assertEquals("batches", it.getString(it.getColumnIndexOrThrow("table")))
                check(!it.moveToNext())
            }
            listOf("jobs", "batches", "batch_items").associateWith { rows(db, it) }
        }
        JobStore(app).use { store ->
            val db = store.writableDatabase
            assertEquals(9, db.version)
            for ((table, expected) in before) assertEquals(table, expected, rows(db, table))
            db.execSQL("UPDATE jobs SET error = 'FormatExpired', retryable = 0 WHERE job_id = 'FAILED'")
            val failed = store.get("FAILED")!!
            assertEquals(DownloadError.FormatExpired, failed.error)
            assertEquals("https://example.com/FAILED", failed.sourceUrl)
            assertFalse(failed.retryable)
            assertFalse(store.retry("FAILED"))
        }
    }

    /** Compare every stored value, including opaque blobs and columns absent from JobRecord. */
    private fun rows(db: SQLiteDatabase, table: String): List<Map<String, Any?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.columnNames.withIndex().associate { (index, name) ->
                    name to when (c.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index).toList()
                        else -> c.getString(index)
                    }
                })
            }
        }

    /** Columns per table as SQLite reports them: ALTER-added and created columns compare equal. */
    private fun shape(db: SQLiteDatabase): Map<String, List<String>> =
        listOf("jobs", "batches", "batch_items").associateWith { table ->
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildList { while (c.moveToNext()) add("${c.getString(1)} ${c.getString(2)} ${c.getInt(3)} ${c.getString(4)}") }
            }.sorted()
        }

    /** The database as that release left it: jobs grew columns at 5 and 6, batches arrived at 7 and grew at 8. */
    private fun shipped(version: Int) {
        val db = SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(JobStore.DATABASE_NAME).apply { parentFile!!.mkdirs() }, null)
        val resumeColumns = if (version >= 5) """
                transport_resumes INTEGER NOT NULL DEFAULT 0 CHECK(transport_resumes >= 0),
                stall_restarts INTEGER NOT NULL DEFAULT 0 CHECK(stall_restarts >= 0),
                resume_reset_bytes INTEGER NOT NULL DEFAULT 0 CHECK(resume_reset_bytes >= 0),""" else ""
        val shareColumn = if (version >= 6) "share_link BLOB CHECK(share_link IS NULL OR stage = 'COMPLETED')," else ""
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
                $resumeColumns $shareColumn
                CHECK (has_partial = 0 OR stage IN ('ENQUEUED','TRANSFERRING','PAUSED')),
                CHECK ((stage IN ('COMPLETED','CANCELLED') AND source_url IS NULL)
                    OR (stage = 'FAILED' AND (source_url IS NULL OR retryable = 1))
                    OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL)),
                CHECK (stage NOT IN ('PENDING_INSERTED','PUBLISHING','COMPLETED') OR pending_uri IS NOT NULL)
            )
        """.trimIndent())
        if (version >= 7) {
            db.execSQL("""CREATE TABLE batches (
                batch_id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, preset TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('ACTIVE','PAUSED','WAITING_NETWORK','STOPPED','COMPLETED','EXPIRED')),
                created_at INTEGER NOT NULL, error TEXT,
                network_failures INTEGER NOT NULL DEFAULT 0, refused_failures INTEGER NOT NULL DEFAULT 0)""")
            BatchStore.createTables(db) // batch_items: unchanged since v7
            if (version >= 8) db.execSQL("ALTER TABLE batches ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("""INSERT INTO jobs (job_id, stage, revision, engine_version, format_json, filename, pending_uri,
            created_at, updated_at) VALUES ('kept', 'COMPLETED', 1, 'e', '{}', 'clip.mp4', 'content://media/1', 1, 1)""")
        db.version = version
        db.close()
    }
}
