package dev.ahmed.savlino.jobs

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import dev.ahmed.savlino.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], manifest = Config.NONE)
class JobStoreRetryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun legacyYoutubeClientFieldIsIgnoredIncludingUnknownDebugValues() {
        for ((index, client) in listOf("ANDROID_VR", "REMOVED_DEBUG_CLIENT").withIndex()) {
            val original = command("legacy-client-$index")
            JobStore(app).use { store ->
                store.enqueue(original)
                val encoded = store.readableDatabase.rawQuery("SELECT format_json FROM jobs WHERE job_id = ?",
                    arrayOf(original.jobId)).use { it.moveToFirst(); it.getString(0) }
                val legacy = org.json.JSONObject(encoded).put("youtubePlayerClient", client).toString()
                store.writableDatabase.execSQL("UPDATE jobs SET format_json = ? WHERE job_id = ?", arrayOf(legacy, original.jobId))
                assertEquals(original.format, store.get(original.jobId)!!.command()!!.format)
                store.fail(original.jobId, DownloadError.Network)
            }
            JobStore(app).use { store ->
                assertTrue(store.retry(original.jobId))
                assertEquals(original.format, store.get(original.jobId)!!.command()!!.format)
            }
        }
    }

    @Test fun conversionSourceContainerPersistsThroughReopenAndExactRetry() {
        for ((index, container) in listOf("mp3", "m4a", "mp4", null).withIndex()) {
            val id = "source-container-$index"
            val format = Format(id, "mp3", false, true, audioCodec = "mp3", bitrateKbps = 320,
                conversion = AudioConversion.MP3_320, sourceFormatId = "exact-source", sourceContainer = container)
            val source = command(id).source
            val confirmed = (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)),
                source, id, "clip") as ConfirmResult.Confirmed).command
            JobStore(app).use { store ->
                store.enqueue(confirmed)
                store.readableDatabase.rawQuery("SELECT format_json FROM jobs WHERE job_id = ?", arrayOf(id)).use {
                    assertTrue(it.moveToFirst())
                    val encoded = JSONObject(it.getString(0))
                    if (container == null) assertFalse(encoded.has("sourceContainer"))
                    else assertEquals(container, encoded.getString("sourceContainer"))
                }
                assertEquals(format, store.get(id)!!.command()!!.format)
                store.fail(id, DownloadError.Network)
            }
            JobStore(app).use { store ->
                assertEquals(5, store.readableDatabase.version)
                assertTrue(store.retry(id))
                assertEquals(format, store.get(id)!!.command()!!.format)
            }
        }
    }
    @Test fun gifAndContainerPersistThroughReopenAndRetry() {
        val gif = Format("gif", "mp4", true, true, animatedGif = true)
        val mkv = Format("vp9", "mkv", true, false, videoCodec = "vp9", height = 848, audioFormatId = "a", requiresMerge = true)
        for (format in listOf(gif, mkv)) {
            val id = "playback-${format.id}"
            val source = command(id).source
            val confirmed = (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)),
                source, format.id, "clip") as ConfirmResult.Confirmed).command
            JobStore(app).use { store ->
                store.enqueue(confirmed)
                assertEquals(format, store.get(id)!!.command()!!.format)
                store.fail(id, DownloadError.Network)
            }
            JobStore(app).use { store ->
                assertTrue(store.retry(id))
                assertEquals(format, store.get(id)!!.command()!!.format)
                assertTrue(store.get(id)!!.filename.endsWith(".${format.container}"))
            }
        }
    }
    @Test fun legacyV2AbsentOrJsonNullSourceContainerDecodesWithoutInventingProvenance() {
        for ((index, field) in listOf("", ",\"sourceContainer\":null").withIndex()) {
            val id = "legacy-conversion-$index"
            JobStore(app).use { store ->
                store.enqueue(command(id))
                val encoded = """{"id":"legacy-mp3","sourceId":"legacy-source","container":"mp3",
                    "video":false,"audio":true,"merge":false,"conversion":"MP3_128",
                    "audioCodec":"mp3","bitrate":128,"sizeKind":"unknown"$field}"""
                store.writableDatabase.execSQL("UPDATE jobs SET format_json = ? WHERE job_id = ?", arrayOf(encoded, id))
                val decoded = store.get(id)!!.command()!!.format
                assertNull(decoded.sourceContainer)
                assertEquals("legacy-source", decoded.sourceFormatId)
                assertEquals(AudioConversion.MP3_128, decoded.conversion)
                store.fail(id, DownloadError.Network)
            }
            JobStore(app).use { store ->
                assertTrue(store.retry(id))
                assertNull(store.get(id)!!.command()!!.format.sourceContainer)
                assertEquals("legacy-source", store.get(id)!!.command()!!.format.sourceFormatId)
            }
        }
    }

    @Test fun explicitRetryReenqueuesSameSelectionAndDeadlineExactlyOnceAfterReopen() {
        val command = command("retry")
        val created: Long
        JobStore(app).use { store ->
            store.enqueue(command, Preset.BEST)
            created = store.get("retry")!!.createdAt
            store.startTransfer("retry")
            File(app.filesDir, "downloads/retry").apply { mkdirs(); resolve("partial").writeText("bytes") }
            assertTrue(store.fail("retry", DownloadError.Network))
            assertFalse(File(app.filesDir, "downloads/retry").exists())
            val failed = store.get("retry")!!
            assertEquals(JobStage.FAILED, failed.stage)
            assertEquals(DownloadError.Network, failed.error)
            assertTrue(failed.retryable)
            assertEquals(command.source.link.rawUrl, failed.sourceUrl)
            assertNull(failed.command())
        }
        JobStore(app).use { store ->
            assertTrue(store.retry("retry"))
            assertFalse(store.retry("retry"))
            val row = store.get("retry")!!
            assertEquals(JobStage.ENQUEUED, row.stage)
            assertEquals(command.format, row.command()!!.format)
            assertEquals(command.engineVersion, row.engineVersion)
            assertEquals(command.source.revision, row.revision)
            assertEquals(command.filename, row.filename)
            assertEquals(Preset.BEST, row.preset)
            assertEquals(created, row.createdAt)
            assertNull(row.error)
            assertNull(row.pendingUri)
            assertFalse(row.retryable)
            assertEquals(1, store.all().size)
            assertFalse(File(app.filesDir, "downloads/retry").exists())
        }
    }

    @Test fun retryableErrorsRetainUrlButPermanentFailuresAndCancellationScrubIt() {
        val retryable = setOf(DownloadError.Network, DownloadError.Offline, DownloadError.InsufficientSpace,
            DownloadError.MergeFailed, DownloadError.SaveFailed, DownloadError.FolderUnavailable, DownloadError.ExecutionRestricted,
            DownloadError.Interrupted, DownloadError.Failed, DownloadError.EngineUnavailable)
        JobStore(app).use { store ->
            for (error in DownloadError.entries) {
                val id = "failure-${error.name}"
                store.enqueue(command(id))
                store.fail(id, error)
                val row = store.get(id)!!
                assertEquals(JobStage.FAILED, row.stage)
                assertEquals(error, row.error)
                assertEquals(error.name, error in retryable, row.retryable)
                assertEquals(error.name, error in retryable, row.sourceUrl != null)
                assertEquals(error.name, error in retryable, store.retry(id))
            }
            store.enqueue(command("cancelled")); store.cancel("cancelled")
            assertNull(store.get("cancelled")!!.sourceUrl)
            assertFalse(store.retry("cancelled"))
            assertFalse(store.retry("missing"))
            store.enqueue(command("stale-format"))
            store.fail("stale-format", DownloadError.FormatExpired, retryable = true)
            assertNull(store.get("stale-format")!!.sourceUrl)
            assertFalse(store.retry("stale-format"))
        }
    }

    @Test fun retryKeepsExactMergePartnerAndConversionInsteadOfSelectingAgain() {
        val formats = listOf(
            Format("merged", "mp4", true, true, size = Size.Estimated(500),
                audioFormatId = "audio/exact", requiresMerge = true, sourceFormatId = "video/exact"),
            Format("converted", "mp3", false, true, bitrateKbps = 320,
                conversion = AudioConversion.MP3_320, sourceFormatId = "audio/source"),
        )
        JobStore(app).use { store ->
            for (format in formats) {
                val source = command(format.id).source
                val confirmed = (DownloadCommand.confirm(format.id,
                    Inspection(source, "engine-42", listOf(format)), source, format.id, "clip") as ConfirmResult.Confirmed).command
                store.enqueue(confirmed)
                store.fail(format.id, DownloadError.MergeFailed)
                assertTrue(store.retry(format.id))
                assertEquals(format, store.get(format.id)!!.command()!!.format)
            }
        }
    }

    @Test fun failedUrlExpiresFromOriginalConfirmationEvenAfterRetry() {
        JobStore(app).use { store ->
            store.enqueue(command("expired")); store.fail("expired", DownloadError.Network)
            store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                arrayOf<Any>(System.currentTimeMillis() - 86_400_001L, "expired"))
            assertFalse(store.retry("expired"))
            assertNull(store.get("expired")!!.sourceUrl)
            assertFalse(store.get("expired")!!.retryable)
            assertEquals(DownloadError.Network, store.get("expired")!!.error)
            store.enqueue(command("retried")); store.fail("retried", DownloadError.Offline)
            assertTrue(store.retry("retried"))
            store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                arrayOf<Any>(System.currentTimeMillis() - 86_400_001L, "retried"))
            store.fail("retried", DownloadError.Network)
            assertNull(store.all().single { it.jobId == "retried" }.sourceUrl)
            assertFalse(store.retry("retried"))
        }
    }

    @Test fun readingJournalScrubsExpiredFailedUrlsWithoutRetryOrRecovery() {
        JobStore(app).use { store ->
            for (id in listOf("read-one", "read-all")) {
                store.enqueue(command(id)); store.fail(id, DownloadError.Network)
                store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                    arrayOf<Any>(System.currentTimeMillis() - 86_400_001L, id))
            }
            assertNull(store.get("read-one")!!.sourceUrl)
            assertTrue(store.all().all { it.sourceUrl == null && !it.retryable })
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE source_url IS NOT NULL", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun removeDeletesFailedUrlAndOnlyItsPrivateFiles() {
        JobStore(app).use { store ->
            store.enqueue(command("remove")); store.fail("remove", DownloadError.Network)
            val leftover = File(app.filesDir, "downloads/remove").apply { mkdirs(); resolve("partial").writeText("old") }
            store.enqueue(command("keep"))
            assertFalse(store.remove("keep"))
            assertTrue(store.remove("remove"))
            assertNull(store.get("remove"))
            assertFalse(store.retry("remove"))
            assertFalse(leftover.exists())
            assertEquals(JobStage.ENQUEUED, store.get("keep")!!.stage)
        }
    }

    @Test fun retryRequiresStagingCleanupAndConcurrentHelpersCannotBothReenqueue() {
        JobStore(app).use { store ->
            store.enqueue(command("blocked")); store.fail("blocked", DownloadError.SaveFailed)
            val invalidDirectory = File(app.filesDir, "downloads/blocked").apply { parentFile!!.mkdirs(); writeText("file") }
            assertFalse(store.retry("blocked"))
            assertEquals(JobStage.FAILED, store.get("blocked")!!.stage)
            assertEquals(DownloadError.SaveFailed, store.get("blocked")!!.error)
            invalidDirectory.delete()
            JobStore(app).use { other ->
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val results = listOf(pool.submit<Boolean> { store.retry("blocked") },
                        pool.submit<Boolean> { other.retry("blocked") }).map { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(1, results.count { it })
                    assertEquals(JobStage.ENQUEUED, store.get("blocked")!!.stage)
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun versionOneMigrationPreservesQueuedPendingAndCompletedRowsWithoutInventingLostUrls() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val path = app.getDatabasePath(JobStore.DATABASE_NAME).apply { parentFile!!.mkdirs() }
        val now = System.currentTimeMillis()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE jobs (
                job_id TEXT PRIMARY KEY NOT NULL, stage TEXT NOT NULL,
                source_url TEXT, revision INTEGER NOT NULL, engine_version TEXT NOT NULL,
                format_json TEXT NOT NULL, filename TEXT NOT NULL, preset TEXT,
                pending_uri TEXT, display_name TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, error TEXT, retryable INTEGER NOT NULL DEFAULT 0,
                CHECK ((stage IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NULL)
                    OR (stage NOT IN ('COMPLETED','FAILED','CANCELLED') AND source_url IS NOT NULL)),
                CHECK (stage NOT IN ('PENDING_INSERTED','PUBLISHING','COMPLETED') OR pending_uri IS NOT NULL)
            )""")
            val format = """{"id":"legacy-id","sourceId":"legacy/video","container":"mp4",
                "video":true,"audio":true,"merge":false,"sizeKind":"exact","bytes":1234}"""
            for (stage in JobStage.entries.filter { it != JobStage.PAUSED }) {
                val uri = if (stage in setOf(JobStage.PENDING_INSERTED, JobStage.PUBLISHING, JobStage.COMPLETED))
                    "content://media/external_primary/downloads/7" else null
                db.execSQL("INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                    stage.name, stage.name, if (stage.terminal) null else "https://example.com/legacy", 7,
                    "legacy-engine", format, "legacy.mp4", "BEST", uri, if (uri != null) "saved.mp4" else null,
                    now, now, if (stage == JobStage.FAILED) "Network" else null, if (stage == JobStage.FAILED) 1 else 0))
            }
            db.version = 1
        }
        JobStore(app).use { store ->
            assertEquals(5, store.writableDatabase.version)
            assertEquals(8, store.all().size)
            for (stage in JobStage.entries.filter { it != JobStage.PAUSED }) {
                val row = store.get(stage.name)!!
                assertEquals(stage, row.stage)
                assertEquals(now, row.createdAt)
                assertEquals("legacy/video", row.format.sourceFormatId)
                assertEquals(Size.Exact(1234), row.format.size)
                assertEquals(Preset.BEST, row.preset)
                assertEquals("legacy-engine", row.engineVersion)
                assertEquals("legacy.mp4", row.filename)
                if (stage.terminal) assertNull(row.sourceUrl) else assertEquals("https://example.com/legacy", row.sourceUrl)
            }
            assertNotNull(store.get("PENDING_INSERTED")!!.pendingUri)
            assertEquals("saved.mp4", store.get("COMPLETED")!!.displayName)
            assertFalse(store.retry("FAILED"))
            assertFalse(store.get("FAILED")!!.retryable)
            assertTrue(store.fail("ENQUEUED", DownloadError.Network))
            assertEquals("https://example.com/legacy", store.get("ENQUEUED")!!.sourceUrl)
            assertTrue(store.retry("ENQUEUED"))
            assertEquals("legacy/video", store.get("ENQUEUED")!!.command()!!.format.sourceFormatId)
        }
    }
}
