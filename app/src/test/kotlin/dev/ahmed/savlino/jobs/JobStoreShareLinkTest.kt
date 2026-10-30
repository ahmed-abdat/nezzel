package dev.ahmed.savlino.jobs

import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.AesGcmLinkCipher
import dev.ahmed.savlino.storage.LinkCipher
import dev.ahmed.savlino.storage.LinkKeySource
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software stand-in for the AndroidKeyStore alias: same cipher code, a key that can vanish or fail. */
class MemoryLinkKeys : LinkKeySource {
    var key: SecretKey? = null
    var failing = false
    var loads = 0
    override fun load(create: Boolean): SecretKey? {
        loads++
        if (failing) throw java.security.KeyStoreException("busy")
        return key ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it } else null
    }
    override fun delete() { key = null }
}

@RunWith(RobolectricTestRunner::class)
// SDK coverage: JobStore.kt share-link persistence has no API branch; injected ciphers exercise success/failure independently of SDK.
@Config(sdk = [29], manifest = Config.NONE)
class JobStoreShareLinkTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val link = "https://example.com/watch?v=private-token&x=%2f"
    private val keys = MemoryLinkKeys()
    private val cipher = AesGcmLinkCipher(keys)

    @After fun tearDown() { app.deleteDatabase(JobStore.DATABASE_NAME) }

    private fun command(id: String): DownloadCommand {
        val format = Format("choice", "mp4", true, true, size = Size.Exact(10))
        val source = LinkRevision(LinkInput.parse(link)!!, 1)
        return (DownloadCommand.confirm(id, Inspection(source, "engine", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
    }

    private fun uri(id: String) = Uri.parse("content://media/external_primary/downloads/${id.hashCode() and 0xffff}")

    private fun JobStore.completed(id: String, at: Uri = uri(id)): Boolean {
        check(enqueue(command(id))); check(startTransfer(id)); check(markStaged(id))
        check(recordPending(id, PendingPublication(at, "$id.mp4"))); check(markPublishing(id))
        return complete(id, PublishedFile(at, "$id.mp4"))
    }

    private fun JobStore.blob(id: String): ByteArray? = readableDatabase.rawQuery(
        "SELECT share_link FROM jobs WHERE job_id = ?", arrayOf(id)).use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }

    @Test fun completeStoresCiphertextNotTheLinkAndScrubsTheSource() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("done"))
        val blob = store.blob("done")!!
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("private-token"))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("example.com"))
        assertNull(store.get("done")!!.sourceUrl)
        assertEquals(link, store.shareLink("done"))
        assertEquals(listOf("done"), store.linkedFiles().map { it.jobId })
        assertEquals(uri("done"), store.linkedFiles().single().published)
    } }

    @Test fun noCipherOrSealFailureStoresNothingButStillCompletes() {
        JobStore(app, null, null).use { store ->
            assertTrue(store.completed("plain"))
            assertNull(store.blob("plain")); assertNull(store.get("plain")!!.sourceUrl)
        }
        keys.failing = true
        JobStore(app, null, cipher).use { store ->
            assertTrue(store.completed("failed-seal"))
            assertNull(store.blob("failed-seal")); assertNull(store.get("failed-seal")!!.sourceUrl)
            assertTrue(store.linkedFiles().isEmpty())
        }
    }

    @Test fun onlyCompletedRowsMayHoldALink() { JobStore(app, null, cipher).use { store ->
        store.enqueue(command("live"))
        assertThrows(SQLiteConstraintException::class.java) {
            store.writableDatabase.execSQL("UPDATE jobs SET share_link = x'00' WHERE job_id = 'live'")
        }
        assertTrue(store.completed("done"))
        assertThrows(SQLiteConstraintException::class.java) {
            store.writableDatabase.execSQL("UPDATE jobs SET stage = 'FAILED' WHERE job_id = 'done'")
        }
    } }

    @Test fun removeAndPruneDeleteTheLinkWithTheRow() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("removed"))
        assertTrue(store.remove("removed"))
        assertNull(store.blob("removed"))
        for (i in 0..100) assertTrue(store.completed("bulk-$i"))
        store.writableDatabase.execSQL("UPDATE jobs SET updated_at = 1 WHERE job_id = 'bulk-0'")
        store.pruneTerminal()
        assertNull(store.get("bulk-0")); assertNull(store.blob("bulk-0"))
        assertEquals(100, store.linkedFiles().size)
    } }

    @Test fun clearForgetsEveryLinkAndTheKeyAndLaterLinksStillWork() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("a")); assertTrue(store.completed("b"))
        val oldBlob = store.blob("a")!!
        store.clearShareLinks()
        assertNull(store.blob("a")); assertNull(store.blob("b"))
        assertEquals(LinkCipher.Opened.Gone, cipher.open(oldBlob)) // a DB copy taken before the clear can't be opened
        assertTrue(store.completed("after")) // same process, no restart
        assertEquals(link, store.shareLink("after"))
    } }

    @Test fun clearForgetsSavedBeforeAndRetryLinksButNotActiveJobs() { JobStore(app, IsolatedTestApplication.FAKE_KEYER, cipher).use { store ->
        val expired = mutableListOf<String>()
        store.onExpired = { expired += it.jobId }
        val normalized = LinkInput.parse(link)!!.normalizedUrl
        assertTrue(store.completed("done"))
        check(store.enqueue(command("failed"))); check(store.startTransfer("failed")); check(store.fail("failed", DownloadError.Network))
        check(store.enqueue(command("live")))
        assertTrue(store.get("failed")!!.retryable)
        assertTrue(store.savedFormats(normalized).isNotEmpty())
        store.clearShareLinks()
        assertTrue(store.savedFormats(normalized).isEmpty())
        store.readableDatabase.rawQuery("SELECT job_id, source_url, source_key, share_link, retryable FROM jobs", null).use { c ->
            val rows = buildMap { while (c.moveToNext()) put(c.getString(0), listOf(c.getString(1), c.getString(2), c.isNull(3), c.getInt(4))) }
            assertEquals(listOf(null, null, true, 0), rows["done"])
            assertEquals(listOf(null, null, true, 0), rows["failed"])
            assertEquals(link, rows["live"]!![0]); assertNotNull(rows["live"]!![1])
        }
        assertEquals(listOf("failed"), expired) // the Downloads row loses its Retry now, not after a restart
    } }

    @Test fun busyKeystoreHidesButKeepsALostKeyDrops() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("kept")); assertTrue(store.completed("lost"))
        val fresh = AesGcmLinkCipher(keys) // a new process: nothing cached
        JobStore(app, null, fresh).use { reopened ->
            keys.failing = true
            assertNull(reopened.shareLink("kept"))
            assertNotNull(reopened.blob("kept"))
            keys.failing = false
            assertEquals(link, reopened.shareLink("kept"))
        }
        keys.delete()
        JobStore(app, null, AesGcmLinkCipher(keys)).use { reopened ->
            assertNull(reopened.shareLink("lost"))
            assertNull(reopened.blob("lost"))
        }
    } }

    @Test fun tamperedBlobIsDropped() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("bad"))
        store.writableDatabase.execSQL("UPDATE jobs SET share_link = x'${"00".repeat(40)}' WHERE job_id = 'bad'")
        assertNull(store.shareLink("bad"))
        assertNull(store.blob("bad"))
    } }

    @Test fun durableJournalAndSecureDeleteStillHoldOnTheCurrentSchema() { JobStore(app, null, cipher).use { store ->
        val db = store.writableDatabase
        assertEquals(JobStore.DATABASE_VERSION, db.version)
        for ((pragma, expected) in listOf("journal_mode" to "delete", "secure_delete" to "1"))
            db.rawQuery("PRAGMA $pragma", null).use { assertTrue(it.moveToFirst()); assertEquals(expected, it.getString(0).lowercase()) }
    } }
    /** C9: a file confirmed gone takes its link and artwork; the row stays, and a row with only artwork still counts. */
    /** A folder can hand a deleted file's document URI to a new file: only the newest row there keeps link and artwork. */
    @Test fun reusedUriLeavesOnlyTheNewestFileItsLinkAndArtwork() { JobStore(app, null, cipher).use { store ->
        val doc = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANezzel/document/primary%3ANezzel%2Fclip.mp4")
        assertTrue(store.completed("first", doc)); assertTrue(store.artwork.save("first", byteArrayOf(1)))
        assertTrue(store.artwork.save("second", byteArrayOf(2))); assertTrue(store.completed("second", doc))
        assertNull(store.blob("first")); assertFalse(store.artwork.has("first"))
        assertNotNull(store.blob("second")); assertTrue(store.artwork.has("second"))
        assertEquals(listOf("second"), store.linkedFiles().map { it.jobId })
    } }

    @Test fun forgetFileDropsLinkAndArtworkButKeepsTheRow() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("gone")); assertTrue(store.completed("art-only"))
        assertTrue(store.artwork.save("gone", byteArrayOf(1, 2, 3)))
        store.dropShareLink("art-only"); assertTrue(store.artwork.save("art-only", byteArrayOf(1)))
        assertEquals(setOf("gone", "art-only"), store.linkedFiles().map { it.jobId }.toSet())
        store.forgetFile("gone")
        assertNull(store.blob("gone")); assertFalse(store.artwork.has("gone"))
        assertEquals(JobStage.COMPLETED, store.get("gone")!!.stage)
        store.forgetFile("art-only")
        assertTrue(store.linkedFiles().isEmpty())
    } }
}
