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
                        arrayOf(stage.name, stage.name, source,
                            """{"id":"18","sourceId":"18","container":"mp4","video":true,"audio":true,"merge":false,"sizeKind":"unknown"}""",
                            if (stage in setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.COMPLETED)) "content://media/downloads/7" else null,
                            System.currentTimeMillis(), System.currentTimeMillis(),
                            if (stage == JobStage.FAILED) "Network" else null, if (stage == JobStage.FAILED) 1 else 0))
                    if (version == 3) db.execSQL("UPDATE jobs SET source_key='preserved-key' WHERE job_id=?", arrayOf(stage.name))
                }
                db.version = version
            }
            JobStore(app, null).use { store ->
                assertEquals(4, store.readableDatabase.version)
                assertEquals(8, store.all().size)
                for (row in store.all()) {
                    assertEquals(row.jobId, row.stage.name)
                    assertFalse(row.hasPartial); assertNull(row.resumeRecipe); assertEquals(0L, row.restartEpoch)
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

    @Test fun partialCheckRejectsPublicationAndTerminalStagesAndNonBooleanValues() {
        JobStore(app, null).use { store ->
            store.enqueue(command("check"))
            for (stage in JobStage.entries) {
                for (partial in listOf(0, 1, 2, -1)) {
                    val allowed = partial in 0..1 && (partial == 0 || stage in setOf(JobStage.ENQUEUED, JobStage.TRANSFERRING, JobStage.PAUSED))
                    try {
                        store.writableDatabase.execSQL("UPDATE jobs SET stage=?,has_partial=?,source_url=?,pending_uri=? WHERE job_id='check'",
                            arrayOf(stage.name, partial, if (stage.terminal) null else "https://example.org/watch", "content://media/downloads/1"))
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
                    arrayOf(System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000, id))
                val row = store.get(id)!!
                assertEquals(JobStage.FAILED, row.stage); assertEquals(DownloadError.FormatExpired, row.error)
                assertNull(row.sourceUrl); assertFalse(row.retryable); assertFalse(row.hasPartial)
                assertFalse(dir.exists()); assertFalse(store.resume(id)); assertFalse(store.retry(id))
            }
        }
    }
}
