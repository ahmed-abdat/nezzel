package dev.ahmed.savlino.jobs

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.JobDirectory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], manifest = Config.NONE)
class ResumeJournalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun command(id: String): DownloadCommand {
        val source = LinkRevision(LinkInput.parse("https://example.org/watch")!!, 1)
        val format = Format("18", "mp4", true, true)
        return (DownloadCommand.confirm(id, Inspection(source, "version", listOf(format)), source,
            "18", "clip") as ConfirmResult.Confirmed).command
    }

    @Test fun eachLegacyVersionPreservesEveryStageAndPublicationOwnership() {
        for (version in 1..3) {
            app.deleteDatabase(JobStore.DATABASE_NAME)
            val path = app.getDatabasePath(JobStore.DATABASE_NAME).apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("""CREATE TABLE jobs (job_id TEXT PRIMARY KEY NOT NULL, stage TEXT NOT NULL,
                    source_url TEXT, revision INTEGER NOT NULL, engine_version TEXT NOT NULL,
                    format_json TEXT NOT NULL, filename TEXT NOT NULL, preset TEXT, pending_uri TEXT,
                    display_name TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    error TEXT, retryable INTEGER NOT NULL DEFAULT 0 ${if (version == 3) ", source_key TEXT" else ""})""")
                for (stage in JobStage.entries.filter { it != JobStage.PAUSED }) {
                    val terminal = stage.terminal
                    val source = if (terminal && (stage != JobStage.FAILED || version == 1)) null else "https://example.org/watch"
                    db.execSQL("""INSERT INTO jobs (job_id,stage,source_url,revision,engine_version,format_json,
                        filename,pending_uri,display_name,created_at,updated_at,error,retryable)
                        VALUES (?,?,?,1,'version',?,'clip.mp4',?,'provider.mp4',?,?,?,?)""",
                        arrayOf<Any?>(stage.name, stage.name, source,
                            """{"id":"18","sourceId":"18","container":"mp4","video":true,"audio":true,"merge":false,"sizeKind":"unknown"}""",
                            if (stage in setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.COMPLETED)) "content://media/downloads/7" else null,
                            System.currentTimeMillis(), System.currentTimeMillis(),
                            if (stage == JobStage.FAILED) "Network" else null, if (stage == JobStage.FAILED) 1 else 0))
                    if (version == 3) db.execSQL("UPDATE jobs SET source_key='preserved-key' WHERE job_id=?", arrayOf(stage.name))
                }
                db.version = version
            }
            JobStore(app, null).use { store ->
                assertEquals(5, store.readableDatabase.version)
                assertEquals(8, store.all().size)
                for (row in store.all()) {
                    assertEquals(row.jobId, row.stage.name)
                    assertFalse(row.hasPartial); assertNull(row.resumeRecipe); assertEquals(0L, row.restartEpoch)
                    assertEquals(0L, row.resumeResetBytes)
                    assertEquals(row.stage == JobStage.FAILED && version > 1, row.retryable)
                    if (row.stage in setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.COMPLETED))
                        assertEquals("content://media/downloads/7", row.pendingUri.toString())
                    if (version == 3) store.readableDatabase.rawQuery("SELECT source_key FROM jobs WHERE job_id=?", arrayOf(row.jobId)).use {
                        assertTrue(it.moveToFirst()); assertEquals("preserved-key", it.getString(0))
                    }
                }
                assertTrue(store.enqueue(command("new-paused"))); assertTrue(store.pause("new-paused"))
            }
        }
    }

    @Test fun versionFourMigrationKeepsPausedPartialAndPublicationAndInitializesBudgets() {
        // Build a real v4 table from the current schema with the U3 columns removed.
        JobStore(app, null).use { store ->
            store.enqueue(command("v4-paused")); store.startTransfer("v4-paused", "recipe"); store.pause("v4-paused", true)
            store.enqueue(command("v4-publishing")); store.startTransfer("v4-publishing", "recipe"); store.markStaged("v4-publishing")
            store.writableDatabase.execSQL("UPDATE jobs SET stage='PUBLISHING',pending_uri='content://media/downloads/7' WHERE job_id='v4-publishing'")
            val db = store.writableDatabase
            val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE name='jobs'", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }.replace("CREATE TABLE jobs", "CREATE TABLE jobs_v4")
                .replace(Regex("transport_resumes INTEGER NOT NULL DEFAULT 0 CHECK\\(transport_resumes >= 0\\),\\s*"), "")
                .replace(Regex("stall_restarts INTEGER NOT NULL DEFAULT 0 CHECK\\(stall_restarts >= 0\\),\\s*"), "")
                .replace(Regex("resume_reset_bytes INTEGER NOT NULL DEFAULT 0 CHECK\\(resume_reset_bytes >= 0\\),\\s*"), "")
            db.execSQL(sql)
            val columns = db.rawQuery("SELECT * FROM jobs_v4 LIMIT 0", null).use { it.columnNames.joinToString(",") }
            db.execSQL("INSERT INTO jobs_v4 ($columns) SELECT $columns FROM jobs")
            db.execSQL("DROP TABLE jobs")
            db.execSQL("ALTER TABLE jobs_v4 RENAME TO jobs")
            db.version = 4
        }
        JobStore(app, null).use { store ->
            assertEquals(5, store.readableDatabase.version)
            val paused = store.get("v4-paused")!!
            assertEquals(JobStage.PAUSED, paused.stage); assertTrue(paused.hasPartial)
            assertEquals("recipe", paused.resumeRecipe); assertEquals(1L, paused.restartEpoch)
            assertEquals(0, paused.transportResumes); assertEquals(0, paused.stallRestarts)
            assertEquals(0L, paused.resumeResetBytes)
            val publication = store.get("v4-publishing")!!
            assertEquals(JobStage.PUBLISHING, publication.stage)
            assertEquals("content://media/downloads/7", publication.pendingUri.toString())
        }
    }

    @Test fun automaticResumeBudgetsSurviveReopenAndGrowthResetsBothCounters() {
        JobStore(app, null).use { store ->
            store.enqueue(command("durable-budget")); store.startTransfer("durable-budget", "recipe")
            assertTrue(store.requeueAutomatic("durable-budget", DownloadFailureCause.TRANSPORT))
        }
        JobStore(app, null).use { store ->
            store.startTransfer("durable-budget", "recipe")
            // Re-extraction may clear bytes; it cannot reopen the budget without new progress.
            store.restartTransfer("durable-budget")
            assertFalse(store.requeueAutomatic("durable-budget", DownloadFailureCause.TRANSPORT))
            repeat(3) {
                assertTrue(store.requeueAutomatic("durable-budget", DownloadFailureCause.STALL))
                store.startTransfer("durable-budget", "recipe")
            }
            assertFalse(store.requeueAutomatic("durable-budget", DownloadFailureCause.STALL))
            store.resetAutomaticResumes("durable-budget", 1024L * 1024)
            assertTrue(store.requeueAutomatic("durable-budget", DownloadFailureCause.TRANSPORT))
            assertEquals(1, store.get("durable-budget")!!.transportResumes)
            assertEquals(0, store.get("durable-budget")!!.stallRestarts)
            assertTrue(store.requeueAutomatic("durable-budget", DownloadFailureCause.STALL))
            assertTrue(store.pause("durable-budget", true)); assertTrue(store.resume("durable-budget"))
            assertEquals(0, store.get("durable-budget")!!.transportResumes)
            assertEquals(0, store.get("durable-budget")!!.stallRestarts)
            assertTrue(store.requeueAutomatic("durable-budget", DownloadFailureCause.TRANSPORT))
            assertTrue(store.fail("durable-budget", DownloadError.Network))
            assertTrue(store.retry("durable-budget"))
            assertEquals(0, store.get("durable-budget")!!.transportResumes)
            assertEquals(0, store.get("durable-budget")!!.stallRestarts)
        }
    }

    @Test fun growthStepAccumulatesAcrossReopenAndResetsAtOneMebibyteOnly() {
        val step = 1024L * 1024
        JobStore(app, null).use { store ->
            store.enqueue(command("step")); store.startTransfer("step", "recipe")
            assertTrue(store.requeueAutomatic("step", DownloadFailureCause.STALL))
            store.startTransfer("step", "recipe")
            store.resetAutomaticResumes("step", step - 1)
            assertEquals(1, store.get("step")!!.stallRestarts)
            store.resetAutomaticResumes("step", step)
            assertEquals(0, store.get("step")!!.stallRestarts)
            assertEquals(step, store.get("step")!!.resumeResetBytes)
            assertTrue(store.requeueAutomatic("step", DownloadFailureCause.STALL))
        }
        JobStore(app, null).use { store ->
            store.startTransfer("step", "recipe")
            store.resetAutomaticResumes("step", 2 * step - 1)
            assertEquals(1, store.get("step")!!.stallRestarts)
            store.resetAutomaticResumes("step", 2 * step)
            assertEquals(0, store.get("step")!!.stallRestarts)
            assertEquals(2 * step, store.get("step")!!.resumeResetBytes)
        }
    }

    @Test fun partialCheckRejectsPublicationAndTerminalStagesAndNonBooleanValues() {
        JobStore(app, null).use { store ->
            store.enqueue(command("check"))
            for (stage in JobStage.entries) {
                for (partial in listOf(0, 1, 2, -1)) {
                    val allowed = partial in 0..1 && (partial == 0 || stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED))
                    try {
                        store.writableDatabase.execSQL("UPDATE jobs SET stage=?,has_partial=?,source_url=?,pending_uri=? WHERE job_id='check'",
                            arrayOf<Any?>(stage.name, partial, if (stage.terminal) null else "https://example.org/watch", "content://media/downloads/1"))
                        assertTrue("$stage/$partial accepted", allowed)
                    } catch (_: SQLiteConstraintException) { assertFalse("$stage/$partial rejected", allowed) }
                }
            }
        }
    }

    @Test fun pauseResumeAndCancelPreserveThenDestroyOnlyOwnedBytes() {
        JobStore(app, null).use { store ->
            store.enqueue(command("paused")); store.startTransfer("paused", "recipe")
            val part = JobDirectory(app.filesDir).create("paused").directory.resolve("output.mp4.part").apply { writeText("keep") }
            assertTrue(store.pause("paused", true)); assertFalse(store.fail("missing", DownloadError.Network))
            assertEquals(JobStage.PAUSED, store.get("paused")!!.stage)
            assertTrue(store.get("paused")!!.hasPartial); assertNotNull(store.get("paused")!!.sourceUrl)
            assertEquals("keep", part.readText()); assertEquals("recipe", store.get("paused")!!.resumeRecipe)
            assertTrue(store.resume("paused")); assertFalse(store.resume("paused"))
            assertEquals(JobStage.ENQUEUED, store.get("paused")!!.stage)
            assertTrue(store.pause("paused")); assertTrue(store.cancel("paused"))
            assertFalse(part.parentFile!!.exists()); assertNull(store.get("paused")!!.sourceUrl)
            assertFalse(store.get("paused")!!.hasPartial)
        }
    }

    @Test fun expiredResumableStagesFailScrubAndDeleteAtReadWithoutRecovery() {
        JobStore(app, null).use { store ->
            for (stage in listOf(JobStage.ENQUEUED, JobStage.PAUSED, JobStage.TRANSFERRING)) {
                val id = stage.name
                store.enqueue(command(id)); store.startTransfer(id, "recipe")
                if (stage == JobStage.PAUSED) store.pause(id, true)
                if (stage == JobStage.ENQUEUED) store.requeue(id, true)
                val dir = JobDirectory(app.filesDir).create(id).directory
                dir.resolve("output.mp4.part").writeText("bytes")
                store.writableDatabase.execSQL("UPDATE jobs SET created_at=? WHERE job_id=?",
                    arrayOf<Any?>(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000, id))
                val row = store.get(id)!!
                assertEquals(JobStage.FAILED, row.stage); assertEquals(DownloadError.FormatExpired, row.error)
                assertNull(row.sourceUrl); assertFalse(row.retryable); assertFalse(row.hasPartial)
                assertFalse(dir.exists()); assertFalse(store.resume(id)); assertFalse(store.retry(id))
            }
        }
    }

    @Test fun realLegacyChecksMigrateAndExpiredLiveRowsFailWithTheirStaging() {
        val stages = "'ENQUEUED','TRANSFERRING','STAGED','PENDING_INSERTED','PUBLISHING','COMPLETED','FAILED','CANCELLED'"
        val pending = "CHECK (stage NOT IN ('PENDING_INSERTED','PUBLISHING','COMPLETED') OR pending_uri IS NOT NULL)"
        val v1Url = "CHECK ((stage IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NULL) " +
            "OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL))"
        val v2Url = "CHECK ((stage IN ('COMPLETED','CANCELLED') AND source_url IS NULL) " +
            "OR (stage = 'FAILED' AND (source_url IS NULL OR retryable = 1)) " +
            "OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL))"
        val url = "https://example.org/watch"
        val format = """{"id":"18","sourceId":"18","container":"mp4","video":true,"audio":true,"merge":false,"sizeKind":"unknown"}"""
        for (version in 1..3) {
            app.deleteDatabase(JobStore.DATABASE_NAME)
            File(app.filesDir, "downloads").deleteRecursively()
            val path = app.getDatabasePath(JobStore.DATABASE_NAME).apply { parentFile!!.mkdirs() }
            val old = System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 60_000
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("""CREATE TABLE jobs (job_id TEXT PRIMARY KEY NOT NULL,
                    stage TEXT NOT NULL CHECK(stage IN ($stages)), source_url TEXT, revision INTEGER NOT NULL,
                    engine_version TEXT NOT NULL, format_json TEXT NOT NULL, filename TEXT NOT NULL, preset TEXT,
                    pending_uri TEXT, display_name TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    error TEXT, retryable INTEGER NOT NULL DEFAULT 0${if (version == 3) ", source_key TEXT" else ""},
                    ${if (version == 1) v1Url else v2Url}, $pending)""")
                fun insert(id: String, stage: JobStage, created: Long, failed: Boolean = false) = db.execSQL(
                    """INSERT INTO jobs (job_id,stage,source_url,revision,engine_version,format_json,filename,
                        created_at,updated_at,error,retryable) VALUES (?,?,?,1,'version',?,'clip.mp4',?,?,?,?)""",
                    arrayOf<Any?>(id, stage.name, url, format, created, created,
                        if (failed) "Network" else null, if (failed) 1 else 0))
                insert("old-queued", JobStage.ENQUEUED, old)
                insert("old-transfer", JobStage.TRANSFERRING, old)
                insert("fresh-queued", JobStage.ENQUEUED, System.currentTimeMillis())
                // The fixture's CHECKs are the real ones: v1 never kept a failed row's link.
                if (version == 1) try { insert("old-failed", JobStage.FAILED, old, failed = true); fail("v1 CHECK") }
                    catch (_: android.database.SQLException) { }
                else insert("old-failed", JobStage.FAILED, old, failed = true)
                db.version = version
            }
            for (id in listOf("old-queued", "old-transfer", "fresh-queued"))
                File(app.filesDir, "downloads/$id").apply { mkdirs(); resolve("output.mp4.part").writeText("bytes") }
            JobStore(app, null).use { store ->
                assertEquals(5, store.readableDatabase.version)
                for (id in listOf("old-queued", "old-transfer")) {
                    val row = store.get(id)!!
                    assertEquals(JobStage.FAILED, row.stage); assertEquals(DownloadError.FormatExpired, row.error)
                    assertNull(row.sourceUrl); assertFalse(row.retryable); assertFalse(row.hasPartial)
                    assertFalse(File(app.filesDir, "downloads/$id").exists())
                }
                val fresh = store.get("fresh-queued")!!
                assertEquals(JobStage.ENQUEUED, fresh.stage); assertNotNull(fresh.sourceUrl)
                assertTrue(File(app.filesDir, "downloads/fresh-queued/output.mp4.part").exists())
                if (version > 1) {
                    val failed = store.get("old-failed")!!
                    assertEquals(DownloadError.Network, failed.error); assertNull(failed.sourceUrl); assertFalse(failed.retryable)
                } else assertNull(store.get("old-failed"))
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE source_url IS NOT NULL AND created_at < ?",
                    arrayOf((System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS).toString())).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        }
    }
}
