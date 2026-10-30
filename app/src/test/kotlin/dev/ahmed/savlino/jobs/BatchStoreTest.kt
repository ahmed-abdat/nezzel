package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.PlaylistItem
import dev.ahmed.savlino.storage.*
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BatchStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var jobs: JobStore
    private lateinit var batches: BatchStore
    private var now = System.currentTimeMillis()
    @Before fun warmSQLite() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val cipher = batchCipher()
        jobs = JobStore(app, links = cipher)
        jobs.writableDatabase
        batches = BatchStore(jobs, cipher) { now }
    }
    @After fun close() { jobs.close() }

    @Test fun confirmedPlaylistRowsGetTheSheetPreviewsAndKeepThemThroughThePrune() {
        val items = batchItems(3).mapIndexed { index, item ->
            PlaylistItem(item.id, item.sourceUrl, item.title, item.durationSeconds,
                thumbnailUrl = if (index == 1) null else "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg")
        }
        assertTrue(batches.create("batch", "Fixture", Preset.BEST, items))
        val rows = batches.items("batch")
        val loaded = mutableListOf<String>()
        kotlinx.coroutines.runBlocking {
            saveBatchArtwork(jobs.artwork, rows, items) { url ->
                loaded += url; android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        assertEquals(listOf(true, false, true), rows.sortedBy { it.position }.map { jobs.artwork.has(it.jobId) })
        jobs.pruneTerminal() // startup: no job row exists yet, the batch still owns these ids
        assertEquals(listOf(true, false, true), rows.sortedBy { it.position }.map { jobs.artwork.has(it.jobId) })
        kotlinx.coroutines.runBlocking { saveBatchArtwork(jobs.artwork, rows, items) { loaded += it; null } }
        assertEquals(2, loaded.size) // rows that already have artwork are not fetched again
    }

    @Test fun batchCallsDoNotWaitOnTheJobStoreMonitor() {
        // 0.5.0-beta4 on the phone: a batch transaction re-read jobs.writableDatabase (synchronized on the JobStore)
        // while a JobStore caller held that monitor and waited for the transaction's connection.
        assertTrue(batches.create("batch", "Fixture", Preset.BALANCED, batchItems(2)))
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread { synchronized(jobs) { held.countDown(); release.await() } }.apply { start() }
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val done = java.util.concurrent.CountDownLatch(1)
            Thread { batches.scrubExpired(); batches.finishIfIdle("batch"); done.countDown() }.start()
            assertTrue("batch store blocked on the JobStore monitor", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); holder.join() }
    }

    @Test fun confirmationIsAtomicCappedAndStoresOnlySealedUrls() {
        assertTrue(batches.create("batch", "Fixture", Preset.BALANCED, batchItems(3)))
        assertFalse(batches.create("batch", "Duplicate", Preset.BEST, batchItems(1)))
        assertEquals(3, batches.items("batch").size)
        val row = batches.items("batch").first()
        assertEquals("https://www.youtube.com/watch?v=v0000000000", batches.source(row))
        jobs.readableDatabase.rawQuery("SELECT typeof(source_url), source_url FROM batch_items", null).use { c ->
            while (c.moveToNext()) { assertEquals("blob", c.getString(0)); assertFalse(String(c.getBlob(1)).contains("youtube.com")) }
        }
        assertFalse(batches.create("too-big", "Fixture", Preset.BEST, batchItems(201)))
        assertNull(batches.get("too-big"))
    }

    @Test fun staleWaitingItemsExpireAndEveryUrlIsScrubbed() {
        batches.create("old", "Fixture", Preset.BEST, batchItems(3))
        now += 86_400_001
        batches.scrubExpired()
        assertEquals(BatchState.EXPIRED, batches.get("old")!!.state)
        assertTrue(batches.items("old").all { it.state == BatchItemState.SKIPPED && it.error == DownloadError.Interrupted })
        jobs.readableDatabase.rawQuery("SELECT COUNT(*) FROM batch_items WHERE source_url IS NOT NULL", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertFalse(batches.requestRetries("old"))
    }

    @Test fun noCiphertextMeansNoBatchAndAnUnavailableKeyNeverLeaksOrDropsAWaitingUrl() {
        val cipher = batchCipher()
        val cannotSeal = BatchStore(jobs, object : LinkCipher by cipher {
            override fun seal(link: String): ByteArray? = null
        })
        assertFalse(cannotSeal.create("unsealed", "Fixture", Preset.BEST, batchItems(1)))
        assertNull(cannotSeal.get("unsealed"))
        val unavailable = BatchStore(jobs, object : LinkCipher by cipher {
            override fun open(blob: ByteArray) = LinkCipher.Opened.Unavailable
        })
        assertTrue(unavailable.create("locked", "Fixture", Preset.BEST, batchItems(1)))
        val item = unavailable.items("locked").single()
        assertNull(unavailable.source(item))
        assertEquals(BatchItemState.WAITING, unavailable.items("locked").single().state)
        assertNotNull(BatchStore(jobs, cipher).source(item))
        val gone = BatchStore(jobs, object : LinkCipher by cipher {
            override fun open(blob: ByteArray) = LinkCipher.Opened.Gone
        })
        assertNull(gone.source(item))
        assertEquals(BatchItemState.FAILED, gone.items("locked").single().state)
        assertEquals(DownloadError.ExecutionRestricted, gone.items("locked").single().error)
        assertNull(BatchStore(jobs, cipher).source(item))
    }

    @Test fun lastItemBreakerKeepsNetworkWaitingUntilRetryableWorkIsResolved() {
        for ((index, error) in listOf(DownloadError.Network, DownloadError.SourceRefused, DownloadError.Failed).withIndex()) {
            val id = "last-$index"
            val count = if (error == DownloadError.SourceRefused) 3 else 5
            batches.create(id, "Fixture", Preset.BEST, batchItems(count))
            batches.items(id).forEach { batches.finish(it, BatchItemState.FAILED, error, true) }
            assertEquals(if (error == DownloadError.Network) BatchState.WAITING_NETWORK else BatchState.PAUSED, batches.get(id)!!.state)
            batches.finishIfIdle(id)
            assertEquals(if (error == DownloadError.Network) BatchState.WAITING_NETWORK else BatchState.COMPLETED, batches.get(id)!!.state)
            assertTrue(batches.items(id).all { it.state == BatchItemState.FAILED })
            if (error == DownloadError.Network) {
                assertTrue(batches.requestRetries(id, automatic = true))
                assertEquals(BatchState.ACTIVE, batches.get(id)!!.state)
                assertTrue(batches.items(id).all { it.state == BatchItemState.WAITING })
                batches.items(id).forEach { batches.finish(it, BatchItemState.FAILED, error, retryable = false) }
                batches.finishIfIdle(id)
                assertEquals(BatchState.COMPLETED, batches.get(id)!!.state)
            }
        }
    }

    @Test fun terminalBatchRetentionDeletesItemsButKeepsRecentAndUnfinishedBatches() {
        for (id in listOf("completed", "stopped", "expired", "unfinished"))
            batches.create(id, "Fixture", Preset.BEST, batchItems(1))
        batches.finish(batches.items("completed").single(), BatchItemState.DONE)
        batches.finishIfIdle("completed")
        batches.stop("stopped")
        batches.state("expired", BatchState.EXPIRED)
        batches.queued(batches.items("unfinished").single())
        now += 30L * 86_400_000
        batches.create("recent", "Fixture", Preset.BEST, batchItems(1))
        batches.scrubExpired()
        for (id in listOf("completed", "stopped", "expired")) {
            assertNull(batches.get(id)); assertTrue(batches.items(id).isEmpty())
        }
        assertNotNull(batches.get("recent")); assertNotNull(batches.get("unfinished"))
        jobs.readableDatabase.rawQuery("SELECT COUNT(*) FROM batch_items WHERE batch_id NOT IN (SELECT batch_id FROM batches)", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun clearLinkHistoryDeletesEndedBatchesAndKeepsARunningOne() {
        batches.create("batch", "Fixture", Preset.BEST, batchItems(2))
        batches.create("ended", "Fixture", Preset.BEST, batchItems(2))
        batches.stop("ended")
        jobs.clearShareLinks()
        assertEquals(listOf("batch"), batches.all().map { it.id })
        assertNull(batches.get("ended"))
        jobs.readableDatabase.rawQuery("SELECT COUNT(*) FROM batch_items WHERE batch_id='ended'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertEquals(2, batches.items("batch").size)
        assertEquals(batchItems(2).map { it.sourceUrl }, batches.items("batch").map { batches.source(it) })
    }

    @Test fun storedItemAndBatchTitlesAreBounded() {
        val longTitle = "x".repeat(1000)
        batches.create("batch", longTitle, Preset.BEST,
            listOf(PlaylistItem("abcdefghijk", "https://youtube.com/watch?v=abcdefghijk", longTitle, 60)))
        assertEquals(300, batches.get("batch")!!.title.length)
        assertEquals(300, batches.items("batch").single().title.length)
    }

    @Test fun versionSevenFromBeta2GainsTheConsecutiveFailureColumnAndKeepsBatches() {
        batches.create("beta2", "Fixture", Preset.BEST, batchItems(2))
        // 0.5.0-beta2 shipped v7 batches without consecutive_failures.
        with(jobs.writableDatabase) {
            execSQL("CREATE TABLE batches_beta2 AS SELECT batch_id, title, preset, state, created_at, error, network_failures, refused_failures FROM batches")
            execSQL("DROP TABLE batches"); execSQL("ALTER TABLE batches_beta2 RENAME TO batches")
            version = 7
        }
        jobs.close()
        jobs = JobStore(app)
        batches = BatchStore(jobs, batchCipher()) { now }
        assertEquals(JobStore.DATABASE_VERSION, jobs.writableDatabase.version)
        val columns = jobs.readableDatabase.rawQuery("PRAGMA table_info(batches)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue("consecutive_failures" in columns)
        assertEquals(listOf("beta2"), batches.all().map { it.id })
        assertFalse(jobs.remove("missing")) // Runs the batch-counter UPDATE that failed on the phone.
    }

    @Test fun reupgradeDiscardsBatchesLeftBehindByVersionSix() {
        batches.create("old-confirmation", "Fixture", Preset.BEST, batchItems(2))
        jobs.writableDatabase.version = 6 // v6 leaves unknown batch tables alone on downgrade.
        jobs.close()
        jobs = JobStore(app)
        batches = BatchStore(jobs, batchCipher()) { now }
        assertEquals(JobStore.DATABASE_VERSION, jobs.writableDatabase.version)
        assertTrue(batches.all().isEmpty())
        jobs.readableDatabase.rawQuery("SELECT COUNT(*) FROM batch_items", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertTrue(batches.create("fresh-confirmation", "Fixture", Preset.BEST, batchItems(1)))
    }

    @Test fun versionSixToSevenAddsOnlyBatchTablesAndPreservesEveryJobColumnAndRow() {
        val source = LinkRevision(LinkInput.parse("https://youtube.com/watch?v=abcdefghijk")!!, 3)
        val format = Format("18", "mp4", true, true, size = Size.Exact(123))
        val command = (DownloadCommand.confirm("existing", Inspection(source, "fixture", listOf(format)), source, "18", "Fixture") as ConfirmResult.Confirmed).command
        jobs.enqueue(command, Preset.BEST)
        for (id in listOf("paused", "completed", "transferring", "failed")) {
            val other = (DownloadCommand.confirm(id, Inspection(source, "fixture", listOf(format)), source, "18", "Fixture") as ConfirmResult.Confirmed).command
            jobs.enqueue(other, Preset.BEST)
        }
        jobs.pause("paused")
        jobs.startTransfer("transferring", "fixture-recipe"); jobs.transferPartial("transferring", true)
        jobs.fail("failed", DownloadError.Network)
        val pending = PendingPublication(android.net.Uri.parse("content://media/external/downloads/1"), "fixture.mp4")
        jobs.startTransfer("completed"); jobs.markStaged("completed"); jobs.recordPending("completed", pending)
        jobs.markPublishing("completed"); jobs.complete("completed", PublishedFile(pending.uri, pending.displayName))
        fun snapshot(): List<List<Any?>> = jobs.readableDatabase.rawQuery("SELECT * FROM jobs ORDER BY job_id", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { index ->
                when (c.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(index).toList()
                    else -> c.getString(index)
                }
            }) }
        }
        val rowsBefore = snapshot()
        val before = jobs.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE name='jobs'", null).use { it.moveToFirst(); it.getString(0) }
        jobs.writableDatabase.execSQL("DROP TABLE batch_items")
        jobs.writableDatabase.execSQL("DROP TABLE batches")
        jobs.writableDatabase.version = 6
        jobs.close()
        jobs = JobStore(app)
        assertEquals(JobStore.DATABASE_VERSION, jobs.writableDatabase.version)
        val after = jobs.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE name='jobs'", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals(before, after)
        assertEquals(rowsBefore, snapshot())
        assertEquals(command.format, jobs.get("existing")!!.format)
        assertEquals(command.source.link.rawUrl, jobs.get("existing")!!.sourceUrl)
        assertEquals(JobStage.ENQUEUED, jobs.get("existing")!!.stage)
        assertEquals(Preset.BEST, jobs.get("existing")!!.preset)
        assertEquals(JobStage.TRANSFERRING, jobs.get("transferring")!!.stage)
        assertTrue(jobs.get("transferring")!!.hasPartial)
        assertEquals(JobStage.FAILED, jobs.get("failed")!!.stage)
        assertTrue(jobs.get("failed")!!.retryable)
        assertEquals(0, BatchStore(jobs, batchCipher()).all().size)
        assertTrue(BatchStore(jobs, batchCipher()).create("new-batch", "Fixture", Preset.BEST, batchItems(1)))
    }

    @Test fun oneLinkConfirmCreatesOneBatchWithSealedLinksAndHostOnlyTitles() {
        val raw = listOf("https://www.tiktok.com/@name/video/123?secret=token", "https://youtu.be/abcdefghijk", "https://example.org/clip")
        assertTrue(batches.createLinks("links", "3 links", raw.map { LinkInput.parse(it)!! }, Preset.SMALL))
        assertEquals(1, batches.all().size)
        val rows = batches.items("links")
        assertEquals(listOf("tiktok.com", "youtu.be", "example.org"), rows.map { it.title })
        assertEquals(raw, rows.map { batches.source(it) })
        jobs.readableDatabase.rawQuery("SELECT source_url, title, item_id FROM batch_items", null).use { c ->
            while (c.moveToNext()) {
                val blob = String(c.getBlob(0), Charsets.ISO_8859_1)
                raw.forEach { assertFalse(blob.contains(it.substringAfter("://"))) }
                assertFalse(c.getString(1).contains("/")); assertTrue(c.getString(2).startsWith(BatchStore.LINK_ITEM))
            }
        }
        assertTrue(PlaylistBatch(batches.get("links")!!, rows).links)
    }

    @Test fun onlyOurOwnLinkItemIdsCountAsPastedLinksNeverAYouTubeIdThatStartsTheSame() {
        assertTrue(BatchStore.isLinkItem("link-0")); assertTrue(BatchStore.isLinkItem("link-199"))
        assertFalse(BatchStore.isLinkItem("link-a1B2c3")); assertFalse(BatchStore.isLinkItem("link-123456"))
        assertFalse(BatchStore.isLinkItem("dQw4w9WgXcQ"))
    }
    @Test fun playlistCreateStillTakesOnlyYouTubeVideos() {
        assertFalse(batches.create("p", "P", Preset.BEST, listOf(PlaylistItem("t", "https://www.tiktok.com/@a/video/1", "T", 60))))
        assertTrue(batches.create("p", "P", Preset.BEST, batchItems(1)))
        assertFalse(PlaylistBatch(batches.get("p")!!, batches.items("p")).links)
    }

    // Death right after a person's Retry must leave a batch the feeder still feeds, and recovery must know the old job.
    @Test fun aPersonsRetryActivatesTheBatchWithItsReservationAndRecoveryKnowsTheReplacedJob() {
        batches.create("r", "R", Preset.BEST, batchItems(1))
        val failed = batches.items("r").single()
        assertTrue(batches.finish(failed, BatchItemState.FAILED, DownloadError.Failed, retryable = false))
        batches.finishIfIdle("r")
        assertEquals(BatchState.COMPLETED, batches.get("r")!!.state)
        val (changed, replaced) = batches.requestUserRetries("r")
        assertTrue(changed)
        assertEquals(listOf(failed.jobId), replaced)
        assertEquals(BatchState.ACTIVE, batches.get("r")!!.state)
        val retried = batches.items("r").single()
        assertEquals(BatchItemState.WAITING, retried.state)
        assertTrue(batches.replaced(failed.jobId))
        assertFalse(batches.replaced(retried.jobId))
        assertFalse(batches.replaced(failed.jobId + "9"))
    }

    @Test fun aPlaylistVideoKeepsOneCountKeyAcrossTheFreshAttemptAndAPersonsRetry() {
        batches.create("k", "K", Preset.BEST, batchItems(2))
        val first = batches.items("k")[1]
        val key = batches.countKey(first.jobId)!!
        val ends = dev.ahmed.savlino.usage.CountedEnds(app.getSharedPreferences("count-key-test", 0), batches::hasCountKey)
        assertTrue(ends.first(key)) // the first attempt fails and counts its end
        assertTrue(batches.refresh(first))
        val fresh = batches.items("k")[1]
        assertNotEquals(first.jobId, fresh.jobId)
        assertEquals(key, batches.countKey(fresh.jobId))
        assertTrue(batches.finish(fresh, BatchItemState.FAILED, DownloadError.Failed, retryable = false))
        batches.requestUserRetries("k", position = 1)
        val retried = batches.items("k")[1]
        assertNotEquals(fresh.jobId, retried.jobId)
        assertEquals(key, batches.countKey(retried.jobId))
        assertFalse("Neither retry counts a second end", ends.first(batches.countKey(retried.jobId)!!))
        assertNotEquals(key, batches.countKey(batches.items("k")[0].jobId))
        assertNull(batches.countKey(first.jobId))
        assertTrue(batches.hasCountKey(key))
        assertFalse(batches.hasCountKey("b:k:9"))
    }
}

internal fun batchItems(count: Int) = (0 until count).map {
    val id = "v${it.toString().padStart(10, '0')}"
    PlaylistItem(id, "https://www.youtube.com/watch?v=$id", "Fixture $it", 60)
}
internal fun batchCipher(): LinkCipher = AesGcmLinkCipher(object : LinkKeySource {
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun load(create: Boolean) = key
    override fun delete() { key = null }
})
