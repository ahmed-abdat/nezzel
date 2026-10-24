package dev.ahmed.savlino.jobs

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
        }
        db.execSQL("""INSERT INTO jobs (job_id, stage, revision, engine_version, format_json, filename, pending_uri,
            created_at, updated_at) VALUES ('kept', 'COMPLETED', 1, 'e', '{}', 'clip.mp4', 'content://media/1', 1, 1)""")
        db.version = version
        db.close()
    }
}
