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
        jobs = JobStore(app, links = batchCipher())
        jobs.writableDatabase
        batches = BatchStore(jobs, batchCipher()) { now }
    }
    @After fun close() { jobs.close() }

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
        assertTrue(batches.items("old").all { it.state == BatchItemState.SKIPPED && it.error == DownloadError.FormatExpired })
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
        assertNull(BatchStore(jobs, cipher).source(item))
    }

    @Test fun versionSixToSevenAddsOnlyBatchTablesAndPreservesEveryJobColumnAndRow() {
        val source = LinkRevision(LinkInput.parse("https://youtube.com/watch?v=abcdefghijk")!!, 3)
        val format = Format("18", "mp4", true, true, size = Size.Exact(123))
        val command = (DownloadCommand.confirm("existing", Inspection(source, "fixture", listOf(format)), source, "18", "Fixture") as ConfirmResult.Confirmed).command
        jobs.enqueue(command, Preset.BEST)
        for (id in listOf("paused", "completed")) {
            val other = (DownloadCommand.confirm(id, Inspection(source, "fixture", listOf(format)), source, "18", "Fixture") as ConfirmResult.Confirmed).command
            jobs.enqueue(other, Preset.BEST)
        }
        jobs.pause("paused")
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
        assertEquals(7, jobs.writableDatabase.version)
        val after = jobs.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE name='jobs'", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals(before, after)
        assertEquals(rowsBefore, snapshot())
        assertEquals(command.format, jobs.get("existing")!!.format)
        assertEquals(command.source.link.rawUrl, jobs.get("existing")!!.sourceUrl)
        assertEquals(JobStage.ENQUEUED, jobs.get("existing")!!.stage)
        assertEquals(Preset.BEST, jobs.get("existing")!!.preset)
        assertEquals(0, BatchStore(jobs, batchCipher()).all().size)
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
